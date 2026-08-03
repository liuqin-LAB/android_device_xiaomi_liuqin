/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

import android.animation.ValueAnimator
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import vendor.xiaomi.hardware.keyboardnanoapp_aidl.IKeyboardNanoapp_aidl
import vendor.xiaomi.hardware.keyboardnanoapp_aidl.INanoappCallback_aidl

class KeyboardController(
    context: Context,
    handler: Handler,
) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(handler.looper)
    private val resolver = context.contentResolver
    private val input = context.getSystemService(InputManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_NAME)
        ?.apply { setReferenceCounted(false) }

    private var started = false
    private var closed = false
    private var connection: TransportConnection? = null
    private val service: IKeyboardNanoapp_aidl? get() = connection?.client
    private val callbackRegistered: Boolean get() = connection != null

    private var status = KeyboardStatus()
    private var haveStatus = false
    private var connected = false
    private var batteryMillivolts = 0
    private var keyboardVersion: Int? = null
    private var touchpadVersion: Int? = null
    private var firmware: String? = null
    private var xm2022Mcu = false
    private var address: String? = null
    private var backlightSupported = false
    private var keyboardInfo = 0
    private var bleKeyboard = false
    private val localAddress: ByteArray by lazy { localBluetoothAddress() }

    private val angle = AngleStateController()
    private var pendingAttachWake = false
    private var sleeping = false
    private var screenOn = true
    private var stayAwakeActive = false
    private var lastWakeSentAt = 0L

    private var touchpadFeature: Boolean? = null
    private var backlightValue = -1
    private var backlightTarget = -1
    private var ambientBacklight: Int? = null
    private var backlightRequestAt = 0L
    private var continueLight = 0
    private var continueDark = 0
    private var backlightAuto = false
    private var backlightSetting = DEFAULT_BACKLIGHT
    private var capsLockOn = false
    private var capsLightValue: Int? = null

    private var keyboardG = FloatArray(3)
    private var keyboardGReady = false
    private var padG = FloatArray(3)
    private var padGReady = false

    private val poll = Runnable { pollOnce() }
    private val reconnect = Runnable { connect() }
    private val stopAwakeRunnable = Runnable { stopAwake() }
    private val backlightRetry = EffectRetry()
    private val capsRetry = EffectRetry()
    private val touchpadRetry = EffectRetry()
    private val nfcRetry = EffectRetry { client -> sendNfcTapFrames(client) }

    /** Bind queued callbacks to the transport that produced them. */
    private inner class TransportConnection(val client: IKeyboardNanoapp_aidl) :
        IBinder.DeathRecipient {
        private val binder = client.asBinder()
        private val callback = object : INanoappCallback_aidl.Stub() {
            override fun dataReceive_aidl(buf: ByteArray) {
                handler.post { if (isCurrent()) onData(buf) }
            }

            override fun errorReceive_aidl(errorCode: Int) {
                handler.post { if (isCurrent()) onError(errorCode) }
            }

            override fun getInterfaceVersion(): Int = INanoappCallback_aidl.VERSION

            override fun getInterfaceHash(): String = INanoappCallback_aidl.HASH
        }

        private fun isCurrent() = started && !closed && connection === this

        fun register() {
            binder.linkToDeath(this, 0)
            try {
                client.setCallback_aidl(callback)
            } catch (exception: RemoteException) {
                unlink()
                throw exception
            }
        }

        fun unlink() {
            runCatching { binder.unlinkToDeath(this, 0) }
        }

        override fun binderDied() {
            handler.post { if (isCurrent()) onTransportDied() }
        }
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            handler.post { applyGate() }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            handler.post { applyGate() }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            handler.post { applyGate() }
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            readSettings()
            applyGate()
        }
    }

    private val capsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            readCapsLock()
            applyCapsLight()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    stayAwake()
                    setPadSensorEnabled()
                    setLightSensorEnabled()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopAwake()
                    setPadSensorEnabled()
                    setLightSensorEnabled()
                }

                Intent.ACTION_USER_SWITCHED -> {
                    readSettings()
                    readCapsLock()
                    address?.let(::publishKeyboardAddress)
                    if (haveStatus && connected) publishKeyboardInfo(keyboardInfo)
                }
            }
            applyGate()
        }
    }

    private val padSensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            if (AngleStateController.motionJudge(x, y, z)) {
                padGReady = false
                return
            }
            if (!padGReady ||
                kotlin.math.abs(x - padG[0]) > 0.294f ||
                kotlin.math.abs(y - padG[1]) > 0.196f ||
                kotlin.math.abs(z - padG[2]) > 0.588f
            ) {
                padG[0] = x
                padG[1] = y
                padG[2] = z
                padGReady = true
                calculateAngle()
            }
        }
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LIGHT || !lightSensorRegistered ||
                !backlightAuto
            ) {
                return
            }
            val target = backlightFromLux(event.values[0])
            ambientBacklight = target
            animateBacklight(target)
        }
    }

    private val backlightAnimator = ValueAnimator.ofInt(0, 0).apply {
        duration = BACKLIGHT_ANIMATION_MS
        addUpdateListener { animation ->
            sendBacklight(animation.animatedValue as Int)
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        // A persisted status from the previous boot must not expose the
        // accessory settings before the first fresh pogo status report.
        KeyboardStatusStore.publish(context, status)
        screenOn = power?.isInteractive ?: true
        readSettings()
        readCapsLock()
        resolver.registerContentObserver(
            Settings.System.getUriFor(BACKLIGHT_BRIGHTNESS),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            Settings.System.getUriFor(BACKLIGHT_AUTO),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            Settings.System.getUriFor(CAPS_LOCK),
            false,
            capsObserver,
            UserHandle.USER_ALL,
        )
        input?.registerInputDeviceListener(inputListener, handler)
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
            Context.RECEIVER_EXPORTED,
        )
        connect()
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            started = false
            handler.removeCallbacks(poll)
            handler.removeCallbacks(reconnect)
            stopAwake()
            resolver.unregisterContentObserver(settingsObserver)
            resolver.unregisterContentObserver(capsObserver)
            input?.unregisterInputDeviceListener(inputListener)
            runCatching { context.unregisterReceiver(screenReceiver) }
            unregisterSensors()
            dropService()
            disableDevices()
            status = KeyboardStatus()
            KeyboardStatusStore.publish(context, status)
        }
    }

    private fun connect() {
        if (!started || closed || connection != null) return
        val client = ServiceManager.getService(SERVICE_NAME)?.let {
            IKeyboardNanoapp_aidl.Stub.asInterface(it)
        }
        if (client == null) {
            scheduleReconnect()
            return
        }
        val transport = TransportConnection(client)
        try {
            transport.register()
        } catch (exception: RemoteException) {
            Log.w(TAG, "Could not register the keyboard callback", exception)
            scheduleReconnect()
            return
        }
        connection = transport
        Log.i(TAG, "Keyboard transport connected")
        send(client, KeyboardProtocol.pogoStatusQuery())
        send(client, KeyboardProtocol.mcuVersionQuery())
        if (connected) refreshKeyboard()
        schedulePoll(0L)
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnect)
        if (started && !closed) handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
    }

    private fun dropService() {
        val transport = connection
        connection = null
        transport?.unlink()
        resetAppliedFeatures()
        angle.reset()
        pendingAttachWake = false
        keyboardGReady = false
        padGReady = false
    }

    private fun stayAwake() {
        if (!started || closed || !connected) return
        wakeLock?.acquire(STAY_AWAKE_MS)
        stayAwakeActive = true
        lastWakeSentAt = 0L
        handler.removeCallbacks(stopAwakeRunnable)
        handler.postDelayed(stopAwakeRunnable, STAY_AWAKE_MS)
    }

    private fun stopAwake() {
        handler.removeCallbacks(stopAwakeRunnable)
        stayAwakeActive = false
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.release()
        }
    }

    private fun pollOnce() {
        if (!started || closed) return
        val client = service
        if (client == null || !callbackRegistered) {
            // The transport can disappear at any time (service restart,
            // binder death); keep retrying until it is back.
            scheduleReconnect()
            schedulePoll(TICK_INTERVAL_MS)
            return
        }
        var alive = true
        val now = SystemClock.uptimeMillis()
        if (connected && stayAwakeActive && now - lastWakeSentAt >= WAKE_INTERVAL_MS) {
            lastWakeSentAt = now
            alive = send(client, KeyboardProtocol.wakeKeyboard())
        }
        alive = send(client, KeyboardProtocol.hallQuery()) && alive
        if (!alive) {
            handleTransportLoss("stopped responding")
            return
        }
        applyGate()
        schedulePoll(TICK_INTERVAL_MS)
    }

    private fun send(client: IKeyboardNanoapp_aidl, frame: ByteArray): Boolean = try {
        client.sendCmd_aidl(frame) >= 0
    } catch (exception: RemoteException) {
        Log.w(TAG, "Keyboard command failed", exception)
        false
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Keyboard command rejected", exception)
        false
    }

    private fun onData(buffer: ByteArray) {
        if (!started || closed) return
        val payload = KeyboardProtocol.unwrap(buffer) ?: return
        if (payload.size >= 4 && payload[0] == 0x27.toByte() &&
            payload[2] == 0xff.toByte() && payload[3] == 0xff.toByte()
        ) {
            return
        }

        KeyboardProtocol.parseStatus(payload)?.let { report ->
            val wasConnected = connected
            connected = report.connected
            batteryMillivolts = report.batteryMillivolts
            haveStatus = true
            if (wasConnected != connected) resetAppliedFeatures()
            if (!connected) {
                stopAwake()
                sleeping = false
                angle.reset()
                pendingAttachWake = false
            } else if (!wasConnected) {
                // An old Hall report must not enable a newly attached keyboard.
                angle.reset()
                pendingAttachWake = true
                stayAwake()
                refreshKeyboard()
                service?.let { send(it, KeyboardProtocol.hallQuery()) }
            }
            if (wasConnected != connected) {
                sendBleAttachBroadcast(connected)
            }
        }
        KeyboardProtocol.parseHall(payload)?.let { report ->
            if (!connected) return@let
            angle.updateHall(report.lidOpen, report.tabletOpen)
            if (pendingAttachWake) {
                // Decide once from the first report after attach, including a closed cover.
                pendingAttachWake = false
                if (angle.lidOpen && angle.tabletOpen) {
                    wakeDisplay(ATTACH_WAKE_REASON)
                }
            }
        }
        KeyboardProtocol.parseVersion(payload)?.let { report ->
            keyboardVersion = report.keyboardVersion
            touchpadVersion = report.touchpadVersion
            keyboardInfo = report.keyboardInfo
            bleKeyboard = report.bluetooth
            backlightSupported = report.keyboardInfo and KeyboardProtocol.KEYBOARD_LEVEL_HIGH != 0
            publishKeyboardInfo(report.keyboardInfo)
            if (supportsNfcTap()) sendNfcTapData()
        }
        KeyboardProtocol.parseMcuVersion(payload)?.let {
            firmware = it
            xm2022Mcu = it.startsWith("XM2022")
            capsLightValue = null
        }
        KeyboardProtocol.parseIdentity(payload)?.let { report ->
            address = report
            publishKeyboardAddress(report)
            sendBleAttachBroadcast(connected)
        }
        KeyboardProtocol.parseGSensor(payload)?.let { report ->
            keyboardG[0] = report.x
            keyboardG[1] = report.y
            keyboardG[2] = report.z
            keyboardGReady = true
            calculateAngle()
        }
        KeyboardProtocol.parseSleeping(payload)?.let {
            if (it != sleeping) {
                Log.i(TAG, "Keyboard sleep state=$it")
                sleeping = it
                if (it) {
                    resetAppliedFeatures()
                } else {
                    refreshKeyboard()
                }
            }
        }
        KeyboardProtocol.parseEffect(payload)?.let { effect ->
            backlightRetry.ack(effect)
            capsRetry.ack(effect)
        }
        KeyboardProtocol.parseNfcTouched(payload)?.let {
            if (it && !screenOn) wakeDisplay(NFC_WAKE_REASON)
        }
        KeyboardProtocol.parseKeyboardRequest(payload)?.let { request ->
            if (request == 0 || request == 1) onKeyboardRequestInit()
        }
        if (KeyboardProtocol.parseKeyboardReset(payload)) onKeyboardReset()
        if (payload.isNotEmpty()) publish()
        applyGate()
    }

    private fun onKeyboardRequestInit() {
        Log.i(TAG, "Keyboard requested initialization")
        resetAppliedFeatures()
        service?.let { send(it, KeyboardProtocol.keyboardVersionQuery()) }
        applyGate()
    }

    private fun onKeyboardReset() {
        Log.i(TAG, "Keyboard firmware recovered")
        sendBleAttachBroadcast(false)
        sendBleAttachBroadcast(true)
    }

    private fun resetAppliedFeatures() {
        touchpadFeature = null
        resetBacklightAnimation()
        backlightValue = -1
        capsLightValue = null
        backlightRetry.clear()
        capsRetry.clear()
        touchpadRetry.clear()
        nfcRetry.clear()
    }

    private fun onError(errorCode: Int) {
        Log.w(TAG, "Keyboard transport error $errorCode")
        handleTransportLoss("error $errorCode")
    }

    private fun onTransportDied() {
        Log.w(TAG, "Keyboard transport died")
        handleTransportLoss("binder death")
    }

    private fun handleTransportLoss(reason: String) {
        Log.w(TAG, "Keyboard transport lost: $reason")
        connected = false
        haveStatus = false
        sleeping = false
        stopAwake()
        dropService()
        unregisterSensors()
        disableDevices()
        publish()
        scheduleReconnect()
    }

    private fun wakeDisplay(reason: String) {
        try {
            power?.wakeUp(SystemClock.uptimeMillis(), 0, reason)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not wake the display ($reason)", exception)
        }
    }

    private fun sendBleAttachBroadcast(attached: Boolean) {
        if (!bleKeyboard) return
        val bleAddress = address ?: return
        val intent = Intent(ACTION_KEYBOARD_ATTACH).apply {
            addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            putExtra(EXTRA_KEYBOARD_ADDRESS, bleAddress)
            putExtra(EXTRA_KEYBOARD_ATTACH_STATE, attached)
        }
        context.sendBroadcast(intent)
    }

    private fun publishKeyboardAddress(value: String) {
        runCatching {
            Settings.System.putStringForUser(
                resolver,
                KeyboardProtocol.BLE_ADDRESS_KEY,
                value,
                UserHandle.USER_CURRENT,
            )
        }.onFailure { Log.w(TAG, "Could not publish the keyboard address", it) }
    }

    private fun supportsNfcTap(): Boolean =
        Build.DEVICE == "liuqin" && keyboardInfo and KeyboardProtocol.KEYBOARD_LEVEL_HIGH != 0

    private fun sendNfcTapData() {
        val client = service ?: return
        if (!callbackRegistered || !connected || sleeping) return
        sendNfcTapFrames(client)
        nfcRetry.arm(KeyboardProtocol.FEATURE_NFC_TAP, 0)
    }

    private fun sendNfcTapFrames(client: IKeyboardNanoapp_aidl) {
        NfcTapPayload.frames(localAddress).forEach { frame -> send(client, frame) }
    }

    private fun publishKeyboardInfo(keyboardInfo: Int) {
        runCatching {
            Settings.System.putIntForUser(
                resolver,
                KeyboardProtocol.KEYBOARD_INFO_CHANGED,
                keyboardInfo,
                UserHandle.USER_CURRENT,
            )
        }.onFailure { Log.w(TAG, "Could not publish the keyboard info", it) }
        runCatching {
            Settings.Secure.putIntForUser(
                resolver,
                KeyboardProtocol.KEYBOARD_TYPE_LEVEL,
                if (keyboardInfo and KeyboardProtocol.KEYBOARD_LEVEL_HIGH != 0) 1 else 0,
                UserHandle.USER_CURRENT,
            )
        }.onFailure { Log.w(TAG, "Could not publish the keyboard level", it) }
    }

    private inner class EffectRetry(
        private val resend: ((IKeyboardNanoapp_aidl) -> Unit)? = null,
    ) {
        private var command = -1
        private var pendingValue: Int? = null
        private val repeat = Runnable {
            val value = pendingValue ?: return@Runnable
            val client = service
            if (client == null || !callbackRegistered || !connected || sleeping) return@Runnable
            Log.i(TAG, String.format("Repeat feature 0x%02x value 0x%02x", command, value))
            if (resend != null) {
                resend.invoke(client)
            } else {
                send(client, KeyboardProtocol.featureCommand(command, value))
            }
        }

        fun arm(command: Int, value: Int) {
            this.command = command
            pendingValue = value
            handler.removeCallbacks(repeat)
            handler.postDelayed(repeat, EFFECT_REPEAT_DELAY_MS)
        }

        fun ack(effect: KeyboardProtocol.Effect) {
            if (effect.command != command || effect.value != pendingValue) return
            pendingValue = null
            handler.removeCallbacks(repeat)
        }

        fun clear() {
            pendingValue = null
            handler.removeCallbacks(repeat)
        }
    }

    private fun refreshKeyboard() {
        val client = service ?: return
        if (!callbackRegistered || !connected) return
        send(client, KeyboardProtocol.keyboardVersionQuery())
        send(client, KeyboardProtocol.identityQuery(localAddress))
    }

    private fun localBluetoothAddress(): ByteArray {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val parsed = try {
            adapter?.address?.split(":")?.mapNotNull { it.toIntOrNull(16)?.toByte() }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Could not read the local Bluetooth address", exception)
            null
        }
        return if (parsed?.size == 6) parsed.toByteArray() else ByteArray(6)
    }

    private fun readSettings() {
        val user = UserHandle.USER_CURRENT
        backlightAuto = Settings.System.getIntForUser(
            resolver, BACKLIGHT_AUTO, 0, user,
        ) != 0
        backlightSetting = Settings.System.getIntForUser(
            resolver, BACKLIGHT_BRIGHTNESS, DEFAULT_BACKLIGHT, user,
        ).coerceIn(0, KeyboardProtocol.BACKLIGHT_MAX)
    }

    private fun readCapsLock() {
        capsLockOn = Settings.System.getIntForUser(
            resolver, CAPS_LOCK, 0, UserHandle.USER_CURRENT,
        ) != 0
    }

    private fun calculateAngle() {
        if (!keyboardGReady || !padGReady) return
        val result = AngleStateController.calculateAngle(
            keyboardG[0],
            keyboardG[1],
            keyboardG[2],
            padG[0],
            padG[1],
            padG[2],
        )
        if (result < 0) return
        angle.updateAngle(result.toFloat())
        applyGate()
    }

    private fun setPadSensorEnabled() {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val enable = screenOn && connected
        if (enable && !padSensorRegistered) {
            padSensorRegistered = sensors.registerListener(
                padSensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
            )
        } else if (!enable && padSensorRegistered) {
            sensors.unregisterListener(padSensorListener, sensor)
            padSensorRegistered = false
            padGReady = false
        }
    }

    private var padSensorRegistered = false

    private fun setLightSensorEnabled() {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val enable = screenOn && connected && !sleeping && !angle.shouldIgnoreKeyboard &&
            backlightAuto && backlightSupported
        if (enable && !lightSensorRegistered) {
            resetBacklightAnimation()
            lightSensorRegistered = sensors.registerListener(
                lightSensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
            )
        } else if (!enable && lightSensorRegistered) {
            sensors.unregisterListener(lightSensorListener, sensor)
            lightSensorRegistered = false
            ambientBacklight = null
            resetBacklightAnimation()
        }
    }

    private var lightSensorRegistered = false

    private fun unregisterSensors() {
        sensors?.unregisterListener(padSensorListener)
        sensors?.unregisterListener(lightSensorListener)
        padSensorRegistered = false
        lightSensorRegistered = false
        ambientBacklight = null
        padGReady = false
    }

    private fun applyGate() {
        if (!started || closed) return
        val active = haveStatus && connected && !angle.shouldIgnoreKeyboard

        setDevicesEnabled(PRODUCT_MOUSE, active)
        setDevicesEnabled(PRODUCT_TOUCHPAD, active)
        setDevicesEnabled(PRODUCT_KEYBOARD, active)
        setDevicesEnabled(PRODUCT_CONSUMER, active)

        setPadSensorEnabled()
        setLightSensorEnabled()

        val client = service
        if (client != null && callbackRegistered && connected && !sleeping) {
            if (touchpadFeature != active) {
                val value = if (active) 1 else 0
                if (send(
                        client,
                        KeyboardProtocol.featureCommand(
                            KeyboardProtocol.FEATURE_TOUCHPAD,
                            value,
                        ),
                    )
                ) {
                    touchpadFeature = active
                    touchpadRetry.arm(KeyboardProtocol.FEATURE_TOUCHPAD, value)
                }
            }
            applyBacklight(active)
            applyCapsLight()
        }
        publish()
    }

    private fun applyBacklight(active: Boolean) {
        if (!backlightSupported) {
            resetBacklightAnimation()
            return
        }
        when {
            !active -> {
                resetBacklightAnimation()
                sendBacklight(0)
            }

            backlightAuto -> {
                if (!lightSensorRegistered && screenOn) setLightSensorEnabled()
                ambientBacklight?.let(::animateBacklight)
            }

            else -> {
                resetBacklightAnimation()
                sendBacklight(backlightSetting)
            }
        }
    }

    private fun animateBacklight(target: Int) {
        if (!backlightSupported || !lightSensorRegistered || !backlightAuto ||
            !connected || sleeping || angle.shouldIgnoreKeyboard
        ) {
            return
        }
        val clamped = target.coerceIn(0, KeyboardProtocol.BACKLIGHT_MAX)
        if (clamped == backlightTarget) return
        val now = SystemClock.uptimeMillis()
        val previous = backlightTarget
        val previousAt = backlightRequestAt
        backlightTarget = clamped
        backlightRequestAt = now
        var delay = 0L
        if (now - previousAt < JITTER_WINDOW_MS) {
            if (clamped > previous) continueLight++ else if (clamped < previous) continueDark++
            if (continueLight > 0 && continueDark > 0 &&
                continueLight + continueDark > JITTER_LIMIT
            ) {
                delay = JITTER_DELAY_MS
            }
        } else {
            continueLight = 0
            continueDark = 0
        }
        backlightAnimator.cancel()
        backlightAnimator.setIntValues(backlightValue.coerceAtLeast(0), clamped)
        backlightAnimator.startDelay = delay
        backlightAnimator.start()
    }

    private fun resetBacklightAnimation() {
        backlightAnimator.cancel()
        backlightTarget = -1
        backlightRequestAt = 0L
        continueLight = 0
        continueDark = 0
    }

    private fun sendBacklight(value: Int) {
        if (!backlightSupported) return
        val client = service ?: return
        if (!callbackRegistered || !connected || sleeping) return
        if (backlightValue == value) return
        if (send(
                client,
                KeyboardProtocol.featureCommand(
                    KeyboardProtocol.FEATURE_BACKLIGHT,
                    value,
                ),
            )
        ) {
            backlightValue = value
            backlightRetry.arm(KeyboardProtocol.FEATURE_BACKLIGHT, value)
        }
    }

    private fun applyCapsLight() {
        val client = service ?: return
        if (!callbackRegistered || !connected || sleeping) return
        val command = KeyboardProtocol.capsLightFeature(xm2022Mcu)
        val value = KeyboardProtocol.capsLightValue(capsLockOn, xm2022Mcu)
        if (capsLightValue == value) return
        if (send(client, KeyboardProtocol.featureCommand(command, value))) {
            capsLightValue = value
            capsRetry.arm(command, value)
        }
    }

    private fun backlightFromLux(lux: Float): Int {
        val level = Math.round(Math.min(Math.max(lux, 0f), 50f) / 5f)
        val factor = BRIGHTNESS_SENSOR_RELATION[Math.min(Math.max(level, 0), 10)] ?: 0f
        return Math.round(100f * factor)
    }

    private fun setDevicesEnabled(productId: Int, enabled: Boolean) {
        val manager = input ?: return
        for (deviceId in manager.inputDeviceIds) {
            val device = manager.getInputDevice(deviceId) ?: continue
            if (device.vendorId != VENDOR_NANOSIC || device.productId != productId) continue
            if (device.isEnabled == enabled) continue
            try {
                if (enabled) {
                    manager.enableInputDevice(deviceId)
                } else {
                    manager.disableInputDevice(deviceId)
                }
            } catch (exception: RuntimeException) {
                Log.w(
                    TAG,
                    "Could not ${if (enabled) "enable" else "disable"} " +
                        "keyboard device $deviceId",
                    exception,
                )
            }
        }
    }

    private fun disableDevices() {
        setDevicesEnabled(PRODUCT_TOUCHPAD, false)
        setDevicesEnabled(PRODUCT_MOUSE, false)
        setDevicesEnabled(PRODUCT_KEYBOARD, false)
        setDevicesEnabled(PRODUCT_CONSUMER, false)
    }

    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacks(poll)
        if (started && !closed) handler.postDelayed(poll, delayMs)
    }

    private fun publish() {
        val fresh = haveStatus && connected
        val updatedStatus = KeyboardStatus(
            connected = fresh,
            batteryMillivolts = if (fresh) batteryMillivolts.takeIf { it > 0 } else null,
            keyboardVersion = keyboardVersion,
            touchpadVersion = touchpadVersion,
            firmware = firmware ?: formatVersions(),
            bluetoothAddress = address,
        )
        if (updatedStatus == status) return
        status = updatedStatus
        KeyboardStatusStore.publish(context, status)
    }

    private fun formatVersions(): String? {
        val keyboard = keyboardVersion ?: return null
        val touchpad = touchpadVersion ?: return String.format("0x%04X", keyboard)
        return String.format("0x%04X / 0x%04X", keyboard, touchpad)
    }

    companion object {
        private const val TAG = "LiuqinKeyboard"
        private const val SERVICE_NAME =
            "vendor.xiaomi.hardware.keyboardnanoapp_aidl.IKeyboardNanoapp_aidl/default"
        private const val TICK_INTERVAL_MS = 2_000L
        private const val WAKE_INTERVAL_MS = 12_000L
        private const val STAY_AWAKE_MS = 120_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val EFFECT_REPEAT_DELAY_MS = 50L
        private const val ATTACH_WAKE_REASON = "miui_keyboard_attach"
        private const val BACKLIGHT_ANIMATION_MS = 2_000L
        private const val JITTER_WINDOW_MS = 1_000L
        private const val JITTER_DELAY_MS = 1_000L
        private const val JITTER_LIMIT = 3
        private const val DEFAULT_BACKLIGHT = 0
        private const val WAKE_LOCK_NAME = "iic_upgrade"
        private const val VENDOR_NANOSIC = 0x15d9
        private const val PRODUCT_TOUCHPAD = 0xa1
        private const val PRODUCT_MOUSE = 0xa2
        private const val PRODUCT_KEYBOARD = 0xa3
        private const val PRODUCT_CONSUMER = 0xa4

        private const val BACKLIGHT_BRIGHTNESS = "keyboard_back_light_brightness"
        private const val BACKLIGHT_AUTO = "keyboard_back_light_automatic_adjustment"
        private const val CAPS_LOCK = "liuqin_keyboard_caps_lock"

        private const val ACTION_KEYBOARD_ATTACH = "com.xiaomi.bluetooth.action.KEYBOARD_ATTACH"
        private const val EXTRA_KEYBOARD_ADDRESS = "com.xiaomi.bluetooth.keyboard.extra.ADDRESS"
        private const val EXTRA_KEYBOARD_ATTACH_STATE = "com.xiaomi.bluetooth.keyboard.extra.ATTACH_STATE"
        private const val NFC_WAKE_REASON = "NFC Device Touched"

        private val BRIGHTNESS_SENSOR_RELATION = mapOf(
            0 to 0.6f,
            1 to 0.8f,
            2 to 1.0f,
            3 to 0.8f,
            4 to 0.7f,
            5 to 0.5f,
            6 to 0.4f,
            7 to 0.3f,
            8 to 0.2f,
            9 to 0.1f,
            10 to 0.0f,
        )
    }
}

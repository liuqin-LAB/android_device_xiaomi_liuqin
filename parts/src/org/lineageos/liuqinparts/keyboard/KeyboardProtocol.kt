/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

/**
 * Byte-level port of the stock Nanosic keyboard protocol used by the
 * `vendor.xiaomi.hardware.keyboardnanoapp_aidl` transport.
 *
 * Commands use the stock 68-byte `AA 42 32 00` long frame
 * (`IICCommandMaker.SEND_COMMAND_BYTE_LONG`); the vendor service strips the
 * `AA 42` prefix and forwards the remaining 66 bytes to the controller.
 * Responses arrive as `AA <length> <payload>` and are stripped before
 * decoding. Framing, report decoding and the feature state machine stay in
 * the framework-side controller, exactly like the stock keyboard manager.
 */
internal object KeyboardProtocol {
    const val RAW_FRAME_SIZE = 68

    private const val TRANSPORT_REPORT = 0x32.toByte()
    private const val SHORT_REPORT = 0x4e.toByte()
    private const val LONG_REPORT = 0x4f.toByte()
    private const val PROTOCOL_VERSION = 0x31.toByte()
    private const val VERSION_PROTOCOL = 0x30.toByte()
    private const val PAD_ADDRESS = 0x80.toByte()
    private const val MCU_ADDRESS = 0x18.toByte()
    private const val KEYBOARD_ADDRESS = 0x38.toByte()

    private val FRAME_HEADER = byteArrayOf(0xaa.toByte(), 0x42, TRANSPORT_REPORT, 0x00)

    const val FEATURE_TOUCHPAD = 0x21
    const val FEATURE_BACKLIGHT = 0x23
    const val FEATURE_KEYBOARD_POWER = 0x25
    const val FEATURE_TIPS_LIGHT_XM2022 = 0x26
    const val FEATURE_TIPS_LIGHT = 0x2e
    const val FEATURE_NFC_TAP = 0x36
    const val BACKLIGHT_MAX = 100

    const val KEYBOARD_INFO_CHANGED = "notify_keyboard_info_changed"
    const val KEYBOARD_TYPE_LEVEL = "keyboard_type_level"
    const val BLE_ADDRESS_KEY = "miui_keyboard_address"
    const val KEYBOARD_LEVEL_HIGH = 512

    private const val KEYBOARD_LEVEL_LOW = 256
    private const val KEYBOARD_TYPE_UCJ = 1
    private const val KEYBOARD_INFO_UCJ_LOW = 65

    private val VENDOR_HEADERS = byteArrayOf(0x22, 0x23, 0x24, 0x26)
    private val HALL_PREFIX = byteArrayOf(
        0x24, 0x20, 0x80.toByte(), 0x80.toByte(), 0xe1.toByte(), 0x01,
    )

    data class Status(
        val connected: Boolean,
        val pogoPinHealthy: Boolean,
        val overCurrent: Boolean,
        val keyboardStatus: Int,
        val batteryMillivolts: Int,
    )

    data class Version(
        val keyboardVersion: Int,
        val touchpadVersion: Int,
        val keyboardType: Int,
        val touchpadType: Int,
        val bluetooth: Boolean,
        val keyboardInfo: Int,
    )

    data class Hall(val state: Int) {
        val lidOpen: Boolean get() = state and 0x01 != 0
        val tabletOpen: Boolean get() = state and 0x10 != 0
    }

    /** Effect receipt for the backlight / caps-light commands. */
    data class Effect(val command: Int, val value: Int)

    data class GSensor(val x: Float, val y: Float, val z: Float)

    fun pogoStatusQuery(): ByteArray =
        command(SHORT_REPORT, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0xa1, byteArrayOf(0x01))

    fun keyboardVersionQuery(): ByteArray =
        command(SHORT_REPORT, VERSION_PROTOCOL, KEYBOARD_ADDRESS, 0x01, byteArrayOf(0x00))

    fun mcuVersionQuery(): ByteArray =
        command(SHORT_REPORT, VERSION_PROTOCOL, MCU_ADDRESS, 0x01, byteArrayOf(0x00))

    /**
     * `0xE1` long report Hall query. The stock framework reads the cover
     * switches from the input stack instead; this controller-local query is
     * the LineageOS fallback because apps cannot observe
     * `SW_LID`/`SW_TABLET_MODE`.
     */
    fun hallQuery(): ByteArray = ByteArray(RAW_FRAME_SIZE).apply {
        FRAME_HEADER.copyInto(this)
        this[4] = LONG_REPORT
        this[5] = 0x20
        this[6] = PAD_ADDRESS
        this[7] = PAD_ADDRESS
        this[8] = 0xe1.toByte()
        this[9] = 0x01
        this[10] = 0x00
    }

    fun identityQuery(localAddress: ByteArray): ByteArray {
        val payload = ByteArray(6)
        localAddress.copyInto(payload, 0, 0, minOf(payload.size, localAddress.size))
        return command(SHORT_REPORT, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0x52, payload)
    }

    /**
     * `0x24` request from the keyboard MCU. Host answers with a fresh version
     * and status query; value 100 asks for re-authentication, which LineageOS
     * does not implement.
     */
    fun parseKeyboardRequest(data: ByteArray): Int? {
        if (data.size < 7 || data[0] !in VENDOR_HEADERS ||
            data[4].toInt() and 0xff != 0x24
        ) {
            return null
        }
        if ((data[5].toInt() and 0xff) < 1) return null
        return data[6].toInt() and 0xff
    }

    /** `0x20` firmware-recovery receipt: payload length 1, value `0x36`. */
    fun parseKeyboardReset(data: ByteArray): Boolean {
        if (data.size < 7 || data[0] !in VENDOR_HEADERS ||
            data[4].toInt() and 0xff != 0x20
        ) {
            return false
        }
        return (data[5].toInt() and 0xff) == 1 && (data[6].toInt() and 0xff) == 0x36
    }

    /** Stock `MiuiKeyboardUtil.hasTouchpad`: high keyboards carry a touchpad. */
    fun hasTouchpad(keyboardType: Int): Boolean =
        keyboardType == 0x20 || keyboardType == 0x21 || keyboardType == 0x10

    fun wakeKeyboard(): ByteArray =
        featureCommand(FEATURE_KEYBOARD_POWER, 0x01)

    fun capsLightFeature(xm2022Mcu: Boolean): Int =
        if (xm2022Mcu) FEATURE_TIPS_LIGHT_XM2022 else FEATURE_TIPS_LIGHT

    fun capsLightValue(on: Boolean, xm2022Mcu: Boolean): Int = if (xm2022Mcu) {
        if (on) 0x01 else 0x00
    } else {
        if (on) 0xfd else 0xfc
    }

    fun featureCommand(feature: Int, value: Int): ByteArray =
        command(SHORT_REPORT, PROTOCOL_VERSION, KEYBOARD_ADDRESS, feature, byteArrayOf(value.toByte()))

    /**
     * `CommunicationUtil.sendNFC` frame for one `getNfcData` chunk: the long
     * report carries the total chunk count at data[10] and the 1-based chunk
     * number at data[11].
     */
    fun nfcTapCommand(totalChunks: Int, chunkNumber: Int, chunk: ByteArray): ByteArray =
        command(
            LONG_REPORT,
            PROTOCOL_VERSION,
            KEYBOARD_ADDRESS,
            FEATURE_NFC_TAP,
            byteArrayOf(totalChunks.toByte(), chunkNumber.toByte()) + chunk,
        )

    /**
     * Devices answer with the vendor report directly, but the AIDL service
     * also accepts the stock `AA <length> <payload>` wrapper. Strip it before
     * decoding.
     */
    fun unwrap(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        if (data[0] == 0xaa.toByte()) {
            if (data.size < 2) return null
            val length = data[1].toInt() and 0xff
            if (length == 0 || data.size < length + 2) return null
            return data.copyOfRange(2, 2 + length)
        }
        return data
    }

    fun parseStatus(data: ByteArray): Status? {
        if (data.size < 20 || (data[5].toInt() and 0xff) < 13 ||
            !isVendorResponse(data, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0xa2.toByte()) ||
            data[7].toInt() != 0
        ) {
            return null
        }
        val keyboardStatus = data[9].toInt() and 0xff
        val pogoPinHealthy = keyboardStatus and 0x63 == 0x23
        val overCurrent = data[18].toInt() != 0
        return Status(
            connected = !overCurrent && pogoPinHealthy,
            pogoPinHealthy = pogoPinHealthy,
            overCurrent = overCurrent,
            keyboardStatus = keyboardStatus,
            batteryMillivolts = unsigned16(data, 10),
        )
    }

    /**
     * Stock `IICProtocolDispatcher.parseKeyboardInfo` / `parseTouchPadInfo`:
     * the actual frame length decides between the short low-config form and
     * the full form that carries the keyboard type, and the info byte is
     * published with the stock low/high level flag.
     */
    fun parseVersion(data: ByteArray): Version? {
        if (data.size < 12 || (data[5].toInt() and 0xff) < 5 ||
            !isVendorResponse(data, VERSION_PROTOCOL, KEYBOARD_ADDRESS, 0x01)
        ) {
            return null
        }
        val payloadLength = data[5].toInt() and 0xff
        val keyboardVersion = unsigned16(data, 6)
        val touchpadVersion =
            if (payloadLength != 5 && data.size > 9) unsigned16(data, 8) else 0
        val rawInfo = data[10].toInt() and 0xff
        if (data.size < 18) {
            val lowInfo = if (rawInfo == 0x41 || rawInfo == 0x42) rawInfo else KEYBOARD_INFO_UCJ_LOW
            return Version(
                keyboardVersion = keyboardVersion,
                touchpadVersion = touchpadVersion,
                keyboardType = KEYBOARD_TYPE_UCJ,
                touchpadType = 0,
                bluetooth = false,
                keyboardInfo = lowInfo or KEYBOARD_LEVEL_LOW,
            )
        }
        val keyboardType = data[13].toInt() and 0xff
        val touchpadType = data[14].toInt() and 0xff
        return Version(
            keyboardVersion = keyboardVersion,
            touchpadVersion = touchpadVersion,
            keyboardType = keyboardType,
            touchpadType = touchpadType,
            bluetooth = data[17].toInt() == 1,
            keyboardInfo = rawInfo or
                (if (hasTouchpad(keyboardType)) KEYBOARD_LEVEL_HIGH else KEYBOARD_LEVEL_LOW),
        )
    }

    fun parseMcuVersion(data: ByteArray): String? {
        if (data.size < 24 || (data[5].toInt() and 0xff) < 17 ||
            !isVendorResponse(data, VERSION_PROTOCOL, MCU_ADDRESS, 0x01)
        ) {
            return null
        }
        return data.copyOfRange(7, 23).toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .trim()
            .takeIf(String::isNotEmpty)
    }

    /** `0xE1` Hall response: bit0 lid open, bit4 tablet-mode open. */
    fun parseHall(data: ByteArray): Hall? {
        if (data.size < 7) return null
        for (index in HALL_PREFIX.indices) {
            if (data[index] != HALL_PREFIX[index]) return null
        }
        val state = data[6].toInt() and 0xff
        if (state and 0x11 != state) return null
        return Hall(state)
    }

    /**
     * `IICProtocolDispatcher.responseFromKeyboard` effect cases
     * (`0x23`/`0x26`/`0x2E`): the device echoes the feature command and its
     * applied value, which acknowledges the feature state machine.
     */
    fun parseEffect(data: ByteArray): Effect? {
        if (data.size < 7) return null
        val command = data[4].toInt() and 0xff
        if (command != FEATURE_BACKLIGHT && command != FEATURE_TIPS_LIGHT &&
            command != FEATURE_TIPS_LIGHT_XM2022
        ) {
            return null
        }
        if (!isVendorResponse(data, PROTOCOL_VERSION, KEYBOARD_ADDRESS, data[4])) {
            return null
        }
        return Effect(command, data[6].toInt() and 0xff)
    }

    /**
     * `IICProtocolDispatcher.responseFromKeyboard` case `0x68`: the keyboard
     * reports that a phone touched its NFC antenna.
     */
    fun parseNfcTouched(data: ByteArray): Boolean? {
        if (data.size < 7 ||
            !isVendorResponse(data, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0x68.toByte())
        ) {
            return null
        }
        return data[6].toInt() == 1
    }

    fun parseSleeping(data: ByteArray): Boolean? {
        if (data.size < 8 || (data[5].toInt() and 0xff) != 1 ||
            !isVendorResponse(data, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0x28.toByte()) ||
            (data[6].toInt() and 0xff) > 1
        ) {
            return null
        }
        return data[6].toInt() == 0
    }

    fun parseIdentity(data: ByteArray): String? {
        if (data.size <= 25 || (data[5].toInt() and 0xff) < 19 ||
            !isVendorResponse(data, PROTOCOL_VERSION, KEYBOARD_ADDRESS, 0x52)
        ) {
            return null
        }
        return (19 until 25).joinToString(":") {
            String.format("%02X", data[it].toInt() and 0xff)
        }.takeUnless { it == "00:00:00:00:00:00" || it == "FF:FF:FF:FF:FF:FF" }
    }

    fun parseGSensor(data: ByteArray): GSensor? {
        if (data.size < 12 || (data[4].toInt() and 0xff) != 0x64) return null
        var x = ((data[7].toInt() and 0xff) shl 4) and 0xff0 or ((data[6].toInt() and 0xff) shr 4)
        var y = ((data[9].toInt() and 0xff) shl 4) and 0xff0 or ((data[8].toInt() and 0xff) shr 4)
        var z = ((data[11].toInt() and 0xff) shl 4) and 0xff0 or ((data[10].toInt() and 0xff) shr 4)
        if (x and 0x800 != 0) x -= 0x1000
        if (y and 0x800 != 0) y -= 0x1000
        if (z and 0x800 != 0) z -= 0x1000
        return GSensor(
            x = x * 9.8f / 256.0f,
            y = -y * 9.8f / 256.0f,
            z = -z * 9.8f / 256.0f,
        )
    }

    private fun command(
        report: Byte,
        protocol: Byte,
        target: Byte,
        command: Int,
        payload: ByteArray,
    ): ByteArray {
        if (payload.size > RAW_FRAME_SIZE - 11) return ByteArray(0)
        val frame = ByteArray(RAW_FRAME_SIZE)
        FRAME_HEADER.copyInto(frame)
        frame[4] = report
        frame[5] = protocol
        frame[6] = PAD_ADDRESS
        frame[7] = target
        frame[8] = command.toByte()
        frame[9] = payload.size.toByte()
        payload.copyInto(frame, 10)
        frame[10 + payload.size] = checksum(frame, 4, 10 + payload.size)
        return frame
    }

    /**
     * Stock routing check. `IICProtocolDispatcher` computes the checksum but
     * never uses the result, so a mismatch must not drop an otherwise valid
     * report.
     */
    private fun isVendorResponse(
        data: ByteArray,
        protocol: Byte,
        source: Byte,
        command: Byte,
    ): Boolean {
        if (data.size < 5 || data[0] !in VENDOR_HEADERS) return false
        return data[1] == protocol && data[2] == source && data[3] == PAD_ADDRESS &&
            data[4] == command
    }

    private fun checksum(data: ByteArray, start: Int, end: Int): Byte {
        var value = 0
        for (index in start until end) {
            value = (value + (data[index].toInt() and 0xff)) and 0xff
        }
        return value.toByte()
    }

    private fun unsigned16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}

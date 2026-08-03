# LiuqinParts

`LiuqinParts` is the device-local settings and policy host for the Xiaomi Pad
6 Pro (liuqin). It follows the upstream LineageOS parts pattern: a
platform-signed, privileged `system_ext` application that injects pages with
`com.android.settings.action.IA_SETTINGS` metadata and runs a persistent
service for the hardware state that Android does not manage by itself.

The Parts and keyboard HAL implementations use stock protocol and behavior
as references. The stock class names below identify those behavioral
references. The small adaptations from LineageOS pipa are separately
attributed in the corresponding commits.

## Settings > Gestures

Three one-switch pages are injected into `com.android.settings.category.ia.gestures`:

| Page | Setting | Effect |
|------|---------|--------|
| Double tap to check tablet | `gesture_wakeup_option` (System, 0/2, unset = off) | Writes `gesture_double_tap_enabled`; a screen-off double tap reports `KEY_WAKEUP` from the touch driver |
| Lift to check tablet | `pick_up_gesture_wakeup_mode` (System, 0/1) | Subscribes the wake-up `TYPE_PICK_UP_GESTURE` sensor exposed by Qualcomm `sensors.ssc.so` while the screen is off; a pickup event wakes with `WAKE_REASON_GESTURE` |
| Stylus tap to wake | `stylus_tap_to_wake` (System) | Writes `gesture_pen_tap_enabled`; a screen-off pen one-click reports `KEY_WAKEUP` from the touch driver |

The two pad keys match the stock `TouchWakeUpFeatureManager`: the double-tap
option is `gesture_wakeup_option` with the legacy `gesture_wakeup` fallback
(off while unset, single click is not supported on this device) and pickup
uses `pick_up_gesture_wakeup_mode`. Cover wake/sleep has no toggle: the stock
`gpio_keys` layout (lid and tablet-mode switches on one input device) and the
fixed `config_lidControlsSleep` policy are kept.

`WakeGestureSettingsController` is the persistent writer of the LCD gesture
mode nodes. The NT36532 driver needs the mode before the panel powers down;
the service applies the nodes at boot and on every settings change. The wake
gesture is an ordinary `KEY_WAKEUP` input key, so the controller no longer
manages a `WAKE_GESTURE` sensor subscription.

These LCD wake controls are implemented by Parts, the `xiaomi_touch` sysfs
bridge and NT36532. They do not use `sensors.xiaomi.v2` or `sensor-notifier`.
The standard sensors still pass through the Xiaomi AIDL multi-HAL wrapper
to the Qualcomm sub-HAL; Parts subscribes to its pickup sensor directly.

## Stylus

Settings > Stylus & keyboard groups the stylus and magnetic keyboard pages in
the homepage's Apps/Notifications/Sound/Modes/Display/Wallpaper section. The
magnetic keyboard row remains available when the accessory is detached.

`StylusController` replaces the stock Xiaomi Bluetooth service:

- Charger/power-supply uevents (`PenUEventObserver`) detect the pen leaving
  the magnetic dock and pair it over standard LE bonding. Every MAC sample
  during the stock state 4 pairing window is handled. Replacing the managed
  pen removes only its previous bond; other paired pens are preserved. The
  dock state is mirrored to the stock `stylus_hall_status` System key.
- `StylusPairingScanner` performs an address-filtered bounded scan.
- Standard HID host connectivity and GATT battery, firmware and PnP reads
  feed `StylusStatusStore`, which the settings page displays. The pen address
  is mirrored to the stock `miui_bluetooth_store_pen_address` Secure key and
  the companion DFU address (MAC + 1) to
  `miui_bluetooth_store_pen_dfu_address`. The stock
  `miui_bluetooth_store_fps` / `miui_bluetooth_store_pen_fw_file_path` keys
  stay unwritten because the port has no MIPP high-frequency protocol and no
  firmware OTA.
- `StylusDeviceListener` follows the behavior of stock `MiuiStylusDeviceListener`:
  it watches `InputManager` for Xiaomi generation-1..7 stylus devices, drives
  the NT36532 pen-connection deltas through the
  `/sys/class/touch/touch_dev/stylus_connection` node instead of the removed
  kernel HID scan, and mirrors the connected generation to the stock
  `setting_stylus_version` Secure key. Connect/disconnect deltas use the raw
  generation (`gen | 0x10` / `gen`); the kernel decodes generation 1 (shield)
  and generation 2 (counter) and clamps the generation 3..7 connects to the
  generation-2 counter, mirroring the stock mode20 `[-1, 18]` clamp.
- Pen button actions are handled by `KeyHandler`, a
  `com.android.internal.os.DeviceKeyHandler` loaded into `system_server` by
  the `LineageSdkResLiuqin` overlay. `StylusActions` implements notes,
  region screenshot, back/home/recents and media play/pause. `KeyHandler`
  also publishes the pogo Caps Lock state for the keyboard indicator.

## Magnetic keyboard

System Settings lists the Nanosic HID under Physical keyboard only while the
accessory is connected. The LiuqinParts Stylus & keyboard page always exposes
the magnetic keyboard settings and reports its disconnected state when detached.
No Settings source patch or keyboard-page overlay is required.

The kernel keeps `/dev/nanodev0` and its wake-up input online at probe, then
registers the four Nanosic HID devices only after a valid `0xA2` report confirms
the pogo connection. It unregisters them on detach or over-current, using the
same connection predicate as stock `IICProtocolDispatcher.parseKeyboardStatus`.
Kernel probe and recovery, and Parts transport connection, query the current pogo
state once; normal attach and detach changes arrive through the Nanosic IRQ.

`vendor.lineage.keyboard-service.liuqin` (device tree `keyboard/`) is a
transparent AIDL transport for `/dev/nanodev0`. `KeyboardController` implements
accessory policy using stock `MiuiKeyboardManager` / `IICKeyboard` behavior
as a reference:

- the stock initialization chain: `0xA1` pogo status and MCU version on
  transport connect, keyboard version plus the `0x52` identity query (with
  the local Bluetooth address) on the connection edge, feature refresh on a
  `0x24` request, and BLE rebroadcast on a `0x20` recovery frame. Firmware
  upgrade and device authentication are deliberately omitted;
- commands use the stock 68-byte `AA 42 32 00` long frame
  (`IICCommandMaker.SEND_COMMAND_BYTE_LONG`). The vendor service strips the
  `AA 42` prefix and forwards the 66-byte controller transfer;
- the stock G-Sensor angle state machine (`AngleStateController`) combining
  the pad accelerometer and the keyboard G-Sensor into a 0-360° lid angle,
  including the stock `!lidOpen → 0°` / `!tabletOpen → 360°` forcing. The
  stock controller reads the cover switches from the input stack; because an
  app cannot observe `SW_LID`/`SW_TABLET_MODE`, the controller polls the
  `0xE1` Hall query every two seconds instead and requires a fresh sample
  before enabling the HID devices;
- the standard `InputManager` gate for the Nanosic input devices
  (`15d9:a1`..`15d9:a4`). The separate `0xa2` mouse and `0xa1` touchpad
  interfaces follow the keyboard enable state; the touchpad also receives
  the `0x21` feature command;
- a bounded `iic_upgrade` partial wakelock keeps the 12 s `0x25` wake loop
  running for 120 s after connection and screen events while attached. It is
  released on detach, transport loss, screen-off or service shutdown. The attach edge
  wakes the display with `miui_keyboard_attach` once the Hall state reports
  both lid and tablet open, matching `FakeKeyboard.wakeUpIfNeed`;
- backlight uses the stock `keyboard_back_light_brightness` (0-100, default
  0, 2 s animation with the stock 1 s jitter delay) and
  `keyboard_back_light_automatic_adjustment` keys with the stock
  light-sensor curve. Support follows the stock `keyboardInfo & 512`
  derivation and the version report publishes `notify_keyboard_info_changed`
  (System) and `keyboard_type_level` (Secure), like
  `FakeKeyboard.setKeyboardInfo`. Caps Lock drives the stock `0x26`/`0x2E`
  indicator command;
- backlight and caps-light writes are re-sent once after 50 ms when the
  device does not echo the matching `0x23`/`0x26`/`0x2E` effect, like the
  stock `AbstractFeature` retry;
- a BLE-capable keyboard announces its pogo attach/detach to the Bluetooth
  service with the stock registered-only
  `com.xiaomi.bluetooth.action.KEYBOARD_ATTACH` broadcast and mirrors its
  address to the stock `miui_keyboard_address` System key, like
  `IICKeyboard.sendBroadCast2Ble` / `onKeyboardMacAddress`;
- on the high keyboard (`keyboardInfo & 512`) a phone touching the NFC
  antenna wakes the display (`NFC Device Touched`) and the controller pushes
  the stock OneHop `MIRROR` payload as chunked `0x36` frames
  (`NFCTapFeature` / `OnehopInfo` / `CommunicationUtil.sendNFC`). LineageOS
  has no OneHop/Mi Connect consumer, so the payload is protocol parity only;
- a transport error or binder death drops the proxy and reconnects every
  two seconds. Each connection owns its callback and death recipient, so
  queued reports from a replaced connection cannot alter the new one.

The settings page shows connection, pogo voltage and firmware and exposes
the backlight brightness seekbar, auto-adjust and the standard
`touchpad_tap_to_click` tap-to-click switch.

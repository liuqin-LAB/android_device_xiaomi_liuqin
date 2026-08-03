# LiuqinParts

LiuqinParts 为小米平板 6 Pro（liuqin）提供触控、手写笔和磁吸键盘的设备适配，
复用 LineageOS 的设置界面、输入、蓝牙、电源和传感器服务。功能取舍以
LineageOS `lineage-24.0` 的实际实现及本机硬件协议为依据。

应用使用平台签名，安装于 `system_ext`，共享系统 UID。系统用户中的常驻服务
统一管理硬件；设置页面供当前用户使用。它依赖 LineageOS 的
`DeviceKeyHandler` 扩展和设备键盘 HAL，不能作为普通应用单独安装。

## LineageOS 官方能力与本项目适配

这里的“官方能力”指 LineageOS 平台提供的接口和功能；设备协议适配由本项目提供。
MIUI/HyperOS 原厂行为另列于下一节。

| LineageOS 官方能力 | LiuqinParts 对应实现 | 状态 |
| --- | --- | --- |
| SettingsLib 设置控件、设置入口发现、搜索索引 | 使用折叠标题栏、主开关、滑块和偏好页面；通过 `IA_SETTINGS` 接入手势及“手写笔与键盘”入口 | 保留平台界面方式，新增设备入口 |
| 标准唤醒输入、电源管理、抬起传感器 | 双击和笔点按由触控驱动上报 `KEY_WAKEUP`；抬起通过唤醒型 pickup sensor 请求亮屏 | 保留硬件适配 |
| 蓝牙 LE 扫描、配对、HID Host 和 GATT | 充电座配对窗口内扫描并配对笔；使用平台 HID 连接及 GATT 电量、固件信息 | 保留硬件适配 |
| 普通笔输入和按键分发 | 笔输入继续交给输入框架及应用；Parts 不消费或重新注入笔按键 | 使用平台分发；不提供自定义动作层 |
| 实体键盘设置、输入设备管理 | 系统识别已连接的 Nanosic 输入设备；Parts 经 HAL 完成配件初始化、状态查询和使用姿态门控 | 新增设备适配 |
| 触控板轻触点击设置 | 页面写入 `Settings.System.TOUCHPAD_TAP_TO_CLICK`，由框架应用到支持手势的兼容触控板 | 保留平台设置；生效范围由输入能力决定 |
| LineageOS `DeviceKeyHandler` 扩展 | 仅观察 Nanosic Caps Lock 状态并同步硬件指示灯，原按键事件继续分发 | 新增设备适配 |
| 标准笔尾键和默认笔记应用（Notes role），以及系统截图入口 | 保留平台按键分发；Parts 未提供自定义动作选择器、区域截图或笔记启动器 | 使用平台路径；不提供自定义快捷方式 |

“保留”表示提供对应平台路径或硬件功能；“修改”表示与原厂实现方式不同；
“未实现”表示当前缺失；“新增”表示本项目在通用平台基础上提供的设备适配。

## MIUI/HyperOS 原厂功能与取舍

原厂的触控和 Nanosic 协议用于理解硬件行为。对应参考类包括
`TouchWakeUpFeatureManager`、`MiuiStylusDeviceListener`、`MiuiKeyboardManager`、
`IICKeyboard` 和 `AngleStateController`。下表按当前 Parts 的实现范围，
列出原厂功能的对应关系及差异。

| MIUI/HyperOS 原厂功能 | 当前对应实现 | 保留、缺失或变化 |
| --- | --- | --- |
| 原厂双击、抬起亮屏 | 手势设置和触控／传感器路径继续使用 | 保留 |
| 原厂笔点按屏幕进入笔记 | 仅在熄屏时通过触控驱动亮屏 | 修改：亮屏，不启动笔记 |
| 原厂充电座辅助配对及笔型号识别 | 充电 uevent 触发限时配对；输入设备 ID 映射笔代际并通知触控控制器 | 保留 |
| 原厂笔连接、电量和固件信息 | 设置页提供只读状态 | 保留 |
| 原厂笔记／截屏快捷操作 | 不提供厂商动作页面、快捷操作或替代按键注入；普通笔按键交给平台和应用 | 未实现厂商快捷操作 |
| 原厂笔按键配合触屏进入笔记的手势监听 | 不注册 `monitorGestureInput` 监听 | 未实现厂商触屏快捷手势 |
| 原厂 MIUI Settings 中的笔 MAC、DFU MAC、Hall 和代际镜像 | 保留运行所需的内部状态，地址写入私有存储 | 修改：私有存储，不提供 MIUI 镜像 |
| 原厂磁吸键盘初始化、姿态门控、背光、Caps Lock 灯及触控板控制 | 保留 HAL、协议查询和硬件命令 | 保留 |
| 原厂系统服务消费键盘 Hall 状态 | Parts 经 HAL 查询新鲜 Hall 状态后才允许输入，并在管理配件期间轮询 | 修改：由 Parts 经 HAL 查询状态 |
| 原厂键盘地址／型号 Settings 镜像及小米连接广播 | 地址保存于私有状态，不发布小米广播和 MIUI 镜像 | 修改：使用私有状态 |
| 原厂键盘 NFC 触碰报告 | 保留 `0x68` 触碰报告解析及熄屏时本机亮屏 | 保留 |
| 原厂 OneHop／Mi Connect NFC 标签写入 | 不生成含本机 MAC 的 MI-NFCTAG／`MIRROR` 负载，不执行 `0x36` 标签写入 | 未实现专有服务及替代标签编程；配件 NFC 触碰报告仍有接收路径 |
| 原厂笔 MIPP 高频协议、笔固件 OTA | 尚未实现专有协议和更新器 | 未实现 |
| 原厂键盘固件 OTA、设备认证交换 | 尚未实现更新器和认证交换；保留已有身份查询 | 未实现 |
| 配件地址存储 | 系统用户私有存储保存地址；启动时清理所有现存用户中的弃用公开 Settings 键 | 修改存储方式，提供隐私迁移 |
| 随时可进入的磁吸键盘页面 | 拔下键盘后仍可查看断开状态和设置偏好 | 新增设备入口 |

## 保留的真实硬件功能

### 手势与保护套

设置 → 手势中提供三个独立开关：

| 功能 | 当前用户的 `Settings.System` 键 | 硬件实现 |
| --- | --- | --- |
| 双击亮屏 | `gesture_wakeup_option`：0 关闭、2 开启 | 写入 `gesture_double_tap_enabled`，驱动上报 `KEY_WAKEUP` |
| 抬起亮屏 | `pick_up_gesture_wakeup_mode`：0／1 | 熄屏时订阅唤醒型 `TYPE_PICK_UP_GESTURE` 传感器 |
| 笔点按亮屏 | `stylus_tap_to_wake`：0／1 | 写入 `gesture_pen_tap_enabled`，熄屏笔点按由驱动上报 `KEY_WAKEUP` |

`WakeGestureSettingsController` 在启动、用户切换、设置变化及亮屏后同步模式。
NT36532 需要在面板休眠前设置模式。双击兼容键 `gesture_wakeup` 的回退，
未设置时默认关闭；未提供手指单击亮屏。

触控模式通过 `/sys/class/touch/touch_dev/` 下的 `xiaomi_touch` 桥接节点写入。
保护套的合盖休眠／开盖唤醒保留内核 lid／tablet-mode 开关和
`config_lidControlsSleep` 策略，Parts 不新增保护套开关。

### 手写笔

- `PenUEventObserver` 保留充电座／电源事件。充电状态为 4 时使用事件中的地址
  进行有时限、按地址过滤的 LE 扫描和平台配对。替换所管理的笔时，只移除
  前一支受管理笔的配对，保留其他蓝牙设备。
- `StylusController` 保留 HID 连接状态、标准 GATT 电量和固件信息读取。
- `StylusDeviceListener` 观察 `InputManager`，将 Xiaomi／Primax 输入设备 ID
  映射为笔代际 1–7。连接写入 `generation | 0x10`，断开写入 `generation`，
  启停写入 `-1` 重置 `/sys/class/touch/touch_dev/stylus_connection`。
  这是触控控制器所需的适配，已保留。
- 普通笔输入及笔按键仍走输入框架／应用路径。笔点按只请求亮屏，Parts
  不打开笔记、不监视笔触屏快捷手势，也不注入替代按键。

输入设备 ID 可识别某代笔，不等同于已在 liuqin 上验证该代笔的全部硬件能力。

### 磁吸键盘

设置 → 手写笔与键盘 → 磁吸键盘提供连接状态、pogo 电压、固件信息、
背光亮度、自动背光及触控板轻触点击；断开时固件显示不可用。
系统的实体键盘页面由平台管理。

- 内核保留 `/dev/nanodev0` 和 wake 输入；收到有效 pogo 状态后注册四个
  Nanosic HID 设备，拔下或过流时注销。厂商服务
  `vendor.lineage.keyboard-service.liuqin` 经稳定 AIDL 转发命令及报告。
- 保留 pogo 状态、MCU／键盘版本、身份查询、feature 请求和恢复报告处理。
  Kotlin 命令保持 68 字节长帧；HAL 去掉帧头前缀后交给驱动。
- 平板加速度计和键盘 G-sensor 计算 0–360 度角度；新鲜 Hall 状态与角度共同
  控制 keyboard、consumer、mouse 和 touchpad 输入。Parts 每两秒经 HAL 查询 Hall，
  补足原厂系统服务独有的开关事件接口。
- 保留键盘 `0x25` 唤醒循环、有时限的 partial wake lock 和连接边沿亮屏。
  拔下、链路丢失、熄屏或服务停止时释放锁；连接唤醒须确认 lid 与 tablet 打开。
- 背光支持由版本报告判断；亮度范围为 0–100，默认 0。自动背光保留环境光曲线
  与动画。保留 `0x23` 背光、`0x21` 触控板和 `0x26`／`0x2E` Caps Lock 灯命令。
- 保留背光／Caps Lock 灯确认及有界重试、HAL 死亡处理、失效回调丢弃和重新连接。
- 保留 NFC `0x68` 物理触碰报告的本机亮屏响应。不执行专有 `0x36` 标签编程流程；
  是否在未写入 MI-NFCTAG 负载时仍上报触碰，须在对应键盘固件上确认。
- `KeyHandler` 由 Lineage SDK overlay 加载到 `system_server`，仅同步 Nanosic
  Caps Lock 状态供指示灯控制使用，去重并拒绝过期回调，保留同毫秒不同事件；
  返回原事件供正常输入分发。

## 隐私、多用户与分支范围

系统用户服务统一管理硬件，手势和键盘偏好跟随当前用户。
`stylus_status` 和 `keyboard_status` 使用设备保护存储中的 `MODE_PRIVATE`
偏好保存配件地址。供其他用户页面读取的 Global 状态快照仅包含连接、电量和
固件等信息，不含蓝牙地址和配件名称。

启动迁移遍历所有现存用户，清理弃用的 MIUI 笔／DFU／键盘地址及动作
Settings 键。私有配对地址和仍需使用的手势、背光偏好保留；每项清理单独捕获
异常，避免一项失败中断其他用户的迁移。

Parts 使用 `liuqin_parts_app` SELinux 域。应用经触控节点和键盘 HAL 管理硬件，
`/dev/nanodev0` 由厂商 HAL 访问。两分支 `lineage-23.2` 和 `lineage-24.0` 使用相同 Parts；
Android 17 sepolicy 在 `lineage-24.0` 独立维护，详情见 [策略说明](../sepolicy/README.md)。

## LineageOS 24.0 源码检验

检验基线为 LineageOS 官方仓库的 `lineage-24.0` 分支，固定提交如下：

| 官方仓库 | 固定提交 | 检验内容 |
| --- | --- | --- |
| [android_frameworks_base](https://github.com/LineageOS/android_frameworks_base/tree/e082f54b3058b801ad6b051c0ac4d68f45144658) | `e082f54b3058b801ad6b051c0ac4d68f45144658` | SettingsLib 依赖／控件／主题、输入设备、手势和电源 API |
| [android_build_soong](https://github.com/LineageOS/android_build_soong/tree/5ea236479501d50a4a0eafb568f4f35d1bdda3cb) | `5ea236479501d50a4a0eafb568f4f35d1bdda3cb` | `android_app`、Kotlin 和 `privapp_allowlist` 构建属性 |
| [android_packages_modules_Bluetooth](https://github.com/LineageOS/android_packages_modules_Bluetooth/tree/f2d29ab2d80ea179285cd818b756b44234d2bbb4) | `f2d29ab2d80ea179285cd818b756b44234d2bbb4` | HID／GATT／扫描／配对 API 及权限 |
| [android_lineage-sdk](https://github.com/LineageOS/android_lineage-sdk/tree/a5135bf1b689e73e508e61643410b9d63209b567) | `a5135bf1b689e73e508e61643410b9d63209b567` | 设备按键处理器扩展和 overlay 契约 |
| [android_packages_apps_Settings](https://github.com/LineageOS/android_packages_apps_Settings/tree/beaa8aa6323be765ad9369ec91ad3604cc65b58a) | `beaa8aa6323be765ad9369ec91ad3604cc65b58a` | 设置入口、实体键盘和触控板设置 |

源码检验确认：SettingsLib 的控件依赖及 `Theme.SubSettingsBase.Expressive`
主题仍存在；Soong 支持当前 Kotlin 和 allowlist 配置；所用电源、传感器、
输入及蓝牙方法签名仍存在。触控板设置由 `InputSettingsObserver` 传至原生输入层，
仅适用于支持手势的兼容触控板，不能保证以普通鼠标方式上报的设备也生效。

LineageOS 的笔记设置受 `ROLE_NOTES` 可用性及
`KEYCODE_STYLUS_BUTTON_TAIL` 支持条件限制，标准尾键松开可触发 `OPEN_NOTES`。
Parts 不将其他键码改为尾键；因此某支笔是否显示默认笔记设置、是否能打开笔记，
取决于实际输入能力与平台／应用配置，不能由型号识别推定。
当前使用的 `connectGatt(Context, …)` 在该分支标为 deprecated 但仍存在；
此接口同时用于 23.2；该调用的源码兼容性不等同于 APK 编译验证。

硬件核对包括本地 SM8450 内核的 `nanosic_driver`、Xiaomi touch／NT36532 驱动，
以及设备树的 `keyboard/` HAL。

验证边界：源码接口核对、Kotlin 格式／语法、XML 及独立协议检查不等同于完整
LineageOS 24.0 Soong／ROM 构建或真机验证。仍须在匹配源码树编译 LiuqinParts，
并在 enforcing 真机上验证配对、笔输入与亮屏、键盘插拔／姿态／背光／Caps Lock／
触控板／NFC 触碰和 HAL 重连；当前没有这些真机结果。

#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Liuqin is an APQ/Wi-Fi-only tablet.
TARGET_IS_TABLET := true

# Inherit from xiaomi sm8450-common
$(call inherit-product, device/xiaomi/sm8450-common/common.mk)

# Inherit from the proprietary version
$(call inherit-product, vendor/xiaomi/liuqin/liuqin-vendor.mk)

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxxhdpi

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/mixer_paths_waipio_mtp.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_cape/mixer_paths_waipio_mtp.xml

# Boot animation
TARGET_SCREEN_HEIGHT := 2880
TARGET_SCREEN_WIDTH := 1800

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/init.liuqin.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/init.liuqin.rc

# Permissions
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml

# Overlays
PRODUCT_PACKAGES += \
    ApertureResLiuqin \
    FrameworksResLiuqin \
    LineageSdkResLiuqin \
    SettingsProviderResLiuqin \
    SettingsResLiuqin \
    SystemUIResLiuqin \
    WifiResLiuqin

# Product characteristics
PRODUCT_CHARACTERISTICS := tablet

# Recovery
$(call soong_config_set_bool,recovery,target_recovery_uses_qti_drm,true)

# Keyboard and device parts
PRODUCT_PACKAGES += \
    LiuqinParts \
    vendor.lineage.keyboard-service.liuqin

PRODUCT_PACKAGES_DEBUG += vendor.lineage.keyboard-client.liuqin

PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(LOCAL_PATH)/configs/idc/,$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/idc/)

# Touchpad configuration
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(LOCAL_PATH)/configs/keyboards/,$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/input/keyboards/)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

Copyright (C) 2026 The LineageOS Project

Device configuration for Xiaomi Pad 6 Pro
==========================================

The Xiaomi Pad 6 Pro (codenamed _"liuqin"_) is a Wi-Fi-only tablet from Xiaomi.

## Device specifications

Basic | Spec Sheet
-----:|:----------
Platform | Snapdragon 8+ Gen 1 (SM8475)
RAM & Storage | 8/128 GB, 8/256 GB, 12/256 GB (LPDDR5, UFS 3.1)
Shipped Android version | 13
Battery | Non-removable, 8600 mAh
Display | 11-inch LCD, 1800 × 2880, up to 144 Hz
Rear cameras | 50 MP wide, 2 MP depth
Front camera | 20 MP
Fingerprint | FPC1552 side-mounted sensor

This tree deliberately does not declare cellular, GNSS, NFC, or vibrator hardware.

## Building

This tree targets LineageOS 23.2 and requires the matching Xiaomi SM8450
common tree, kernel, device trees and modules with liuqin support. Four-speaker
calibration also requires [PAL change 495821](https://review.lineageos.org/c/LineageOS/android_vendor_qcom_opensource_arpal-lx/+/495821).
These prerequisites must be integrated before a checkout using only the
official dependencies can build this device.

Use the shared `tools/extract-utils` infrastructure to extract proprietary
files with `./extract-files.py`, or pass a stock dump directory. The default
device blobs and firmware come from OS3.0.7.0.VMYCNXM. Recovery reuses the
extracted touch firmware from `vendor/xiaomi/liuqin`.

Build and validate the `userdebug` variant, including Lineage Recovery,
encryption, OTA and accessories, before submitting for official support.

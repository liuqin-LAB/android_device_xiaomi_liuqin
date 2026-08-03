/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.keyboard-service.liuqin"

#include "KeyboardNanoapp.h"
#include "Protocol.h"

#include <android-base/logging.h>

#include <cerrno>

namespace xiaomi::keyboard {

KeyboardNanoapp::KeyboardNanoapp()
    : mDevice([this](const uint8_t* data, size_t size) {
          if (size >= 4 && data[0] == 0x27 && data[2] == 0xff && data[3] == 0xff) {
              return;
          }
          const auto frame = wrapDevicePayload(data, size);
          if (!frame.empty()) sendData(frame);
      }, [this](int error) { sendError(error); }) {
    mDevice.start();
}

KeyboardNanoapp::~KeyboardNanoapp() {
    mDevice.stop();
}

ndk::ScopedAStatus KeyboardNanoapp::sendCmd_aidl(const std::vector<uint8_t>& buf,
                                                 int32_t* _aidl_return) {
    if (_aidl_return == nullptr) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }

    std::vector<uint8_t> raw;
    *_aidl_return = decodeClientCommand(buf, &raw)
                           ? mDevice.writeCommand(raw.data(), raw.size())
                           : -EINVAL;
    if (*_aidl_return < 0) {
        sendError(*_aidl_return);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus KeyboardNanoapp::setCallback_aidl(const std::shared_ptr<Callback>& callback) {
    std::lock_guard<std::mutex> dispatchLock(mDispatchMutex);
    std::lock_guard<std::mutex> lock(mCallbackMutex);
    mCallback = callback;
    return ndk::ScopedAStatus::ok();
}

void KeyboardNanoapp::clearDeadCallback(const std::shared_ptr<Callback>& callback) {
    std::lock_guard<std::mutex> lock(mCallbackMutex);
    if (mCallback == callback) {
        mCallback.reset();
    }
}

void KeyboardNanoapp::sendData(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> dispatchLock(mDispatchMutex);
    std::shared_ptr<Callback> callback;
    {
        std::lock_guard<std::mutex> lock(mCallbackMutex);
        callback = mCallback;
    }
    if (!callback) {
        return;
    }

    const ndk::ScopedAStatus status = callback->dataReceive_aidl(data);
    if (!status.isOk()) {
        LOG(WARNING) << "Removing dead keyboard callback: " << status.getDescription();
        clearDeadCallback(callback);
    }
}

void KeyboardNanoapp::sendError(int error) {
    std::lock_guard<std::mutex> dispatchLock(mDispatchMutex);
    std::shared_ptr<Callback> callback;
    {
        std::lock_guard<std::mutex> lock(mCallbackMutex);
        callback = mCallback;
    }
    if (!callback) {
        return;
    }

    const ndk::ScopedAStatus status = callback->errorReceive_aidl(error);
    if (!status.isOk()) {
        LOG(WARNING) << "Removing dead keyboard callback: " << status.getDescription();
        clearDeadCallback(callback);
    }
}

}  // namespace xiaomi::keyboard

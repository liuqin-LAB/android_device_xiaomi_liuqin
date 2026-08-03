/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal object NfcTapPayload {
    private val PROTOCOL_ID = byteArrayOf(39, 23)

    private const val DEVICE_TYPE = 8
    private const val TYPE_CUSTOM_ACTION = 101
    private const val TYPE_DEVICEINFO_BT_MAC = 1
    private const val TYPE_EXT_ABILITY = 121
    private const val ACTION = "TAG_DISCOVERED"
    private const val ACTION_SUFFIX_MIRROR = "MIRROR"
    private const val EXT_ABILITY = 1
    private const val CHUNK_SIZE = 52

    private val PREFIX = byteArrayOf(
        8, 1, 16, 13, 34, 1, 3, 42, 9, 77, 73, 45, 78, 70, 67, 84, 65, 71, 56, 15, 74,
    )
    private val SUFFIX = byteArrayOf(106, 2, -6, 127)

    fun frames(localBluetoothAddress: ByteArray): List<ByteArray> {
        val payload = protocolPayload(formatAddress(localBluetoothAddress))
        val chunks = chunk(nfcData(nfcProtocol(payload)), CHUNK_SIZE)
        return chunks.mapIndexed { index, chunk ->
            KeyboardProtocol.nfcTapCommand(chunks.size, index + 1, chunk)
        }
    }

    private fun tlvPayload(bluetoothMac: String?): ByteArray {
        val action = ACTION_SUFFIX_MIRROR.toByteArray(StandardCharsets.UTF_8)
        val mac = bluetoothMac?.toByteArray(StandardCharsets.UTF_8)
        val ability = byteArrayOf(EXT_ABILITY.toByte())
        val size = 2 + action.size + (mac?.let { 2 + it.size } ?: 0) + 2 + ability.size
        return ByteBuffer.allocate(size).apply {
            put(TYPE_CUSTOM_ACTION.toByte())
            put(action.size.toByte())
            put(action)
            if (mac != null) {
                put(TYPE_DEVICEINFO_BT_MAC.toByte())
                put(mac.size.toByte())
                put(mac)
            }
            put(TYPE_EXT_ABILITY.toByte())
            put(ability.size.toByte())
            put(ability)
        }.array()
    }

    private fun protocolPayload(bluetoothMac: String?): ByteArray {
        val action = ACTION.toByteArray(StandardCharsets.UTF_8)
        val payload = tlvPayload(bluetoothMac)
        return ByteBuffer.allocate(2 + 4 + 1 + 1 + action.size + payload.size).apply {
            put(PROTOCOL_ID)
            putInt(DEVICE_TYPE)
            put(0)
            put(action.size.toByte())
            put(action)
            put(payload)
        }.array()
    }

    private fun nfcProtocol(appData: ByteArray): ByteArray {
        val total = PREFIX.size + 2 + 1 + appData.size + SUFFIX.size
        return ByteBuffer.allocate(total).apply {
            put(10)
            put((total - 2).toByte())
            put(PREFIX)
            put(appData.size.toByte())
            put(appData)
            put(SUFFIX)
        }.array()
    }

    private fun nfcData(nfc: ByteArray): ByteArray {
        val all = ByteArray(nfc.size + 8)
        all[0] = 0x4e
        all[1] = 70
        all[2] = 67
        all[3] = ((nfc.size + 1) and 0xff).toByte()
        all[4] = (((nfc.size + 1) shr 8) and 0xff).toByte()
        all[7] = 84
        nfc.copyInto(all, 8)
        var sum = 84
        for (byte in nfc) {
            sum += byte.toInt() and 0xff
        }
        all[5] = (sum and 0xff).toByte()
        all[6] = ((sum shr 8) and 0xff).toByte()
        return all
    }

    private fun chunk(data: ByteArray, size: Int): List<ByteArray> {
        if (data.size <= size) return listOf(data)
        val count = (data.size + size - 1) / size
        return (0 until count).map { index ->
            val start = index * size
            val end = if (index == count - 1) data.size else start + size
            data.copyOfRange(start, end)
        }
    }

    private fun formatAddress(address: ByteArray): String? {
        if (address.size != 6 || address.all { it == 0.toByte() }) return null
        return address.joinToString(":") { String.format("%02X", it.toInt() and 0xff) }
    }
}

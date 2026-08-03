/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the stock `AngleStateController` and `MiuiKeyboardUtil`
 * angle calculation. The keyboard G-Sensor and the pad accelerometer are
 * combined into a 0-360 degree lid angle that gates the virtual input devices.
 *
 * The stock controller also forces 0 degrees while the lid is closed and 360
 * degrees in tablet mode, and its IIC gate additionally requires both cover
 * switches to be open. LineageOS cannot observe the framework
 * `SW_LID`/`SW_TABLET_MODE` switches from an app, so the keyboard controller
 * feeds the Hall state decoded from the `0xE1` query instead. Identity stays
 * always passed because the Xiaomi authentication protocol is deliberately
 * not implemented.
 */
class AngleStateController {
    enum class State(val lower: Float, val upper: Float, val ignoreKeyboard: Boolean) {
        CLOSE(0f, 5f, true),
        NO_WORK_1(5f, 45f, true),
        WORK_1(45f, 90f, false),
        WORK_2(90f, 185f, false),
        NO_WORK_2(185f, 355f, true),
        BACK(355f, 360f, true);

        fun contains(angle: Float): Boolean =
            if (this == BACK) lower <= angle && angle <= upper
            else lower <= angle && angle < upper

        fun next(angle: Float): State = when (this) {
            CLOSE -> if (angle >= upper && angle <= 360f - upper) {
                NO_WORK_1.next(angle)
            } else this

            NO_WORK_1 -> when {
                angle >= upper -> WORK_1.next(angle)
                angle < lower -> CLOSE.next(angle)
                else -> this
            }

            WORK_1 -> when {
                angle >= upper -> WORK_2.next(angle)
                angle < lower -> NO_WORK_1.next(angle)
                else -> this
            }

            WORK_2 -> when {
                angle >= upper -> NO_WORK_2.next(angle)
                angle < lower -> WORK_1.next(angle)
                else -> this
            }

            NO_WORK_2 -> when {
                angle >= upper -> BACK.next(angle)
                angle < lower -> WORK_2.next(angle)
                else -> this
            }

            BACK -> if (angle < lower && angle > 360f - lower) {
                NO_WORK_2.next(angle)
            } else this
        }
    }

    @Volatile
    var lidOpen = true

    @Volatile
    var tabletOpen = true

    private var state = State.CLOSE
    private var angleIgnored = false

    val shouldIgnoreKeyboard: Boolean
        get() = !(lidOpen && tabletOpen) || angleIgnored

    fun updateAngle(angle: Float) {
        if (angle.isNaN()) return
        var value = angle
        if (!lidOpen) value = 0f
        if (!tabletOpen) value = 360f
        if (!state.contains(value)) state = state.next(value)
        angleIgnored = state.ignoreKeyboard
    }

    fun reset() {
        state = State.CLOSE
        angleIgnored = false
    }

    companion object {
        private const val G = 9.8f

        fun motionJudge(x: Float, y: Float, z: Float): Boolean =
            abs(sqrt(x * x + y * y + z * z) - G) > 0.294f

        /**
         * Port of `MiuiKeyboardUtil.calculatePKAngleV2`: find the rotation of
         * the normalized keyboard vector that best aligns with the pad vector.
         * Returns -1 when the Y axis deviation is too large to trust.
         */
        fun calculateAngle(
            keyboardX: Float,
            keyboardY: Float,
            keyboardZ: Float,
            padX: Float,
            padY: Float,
            padZ: Float,
        ): Int {
            val keyboardNorm = invSqrt(keyboardX * keyboardX + keyboardY * keyboardY +
                keyboardZ * keyboardZ)
            val padNorm = invSqrt(padX * padX + padY * padY + padZ * padZ)
            val kx = keyboardX * keyboardNorm
            val ky = keyboardY * keyboardNorm
            val kz = keyboardZ * keyboardNorm
            val px = padX * padNorm
            val py = padY * padNorm
            val pz = padZ * padNorm
            var minDeviation = 100f
            var minAngle = 0
            for (angle in 0..360) {
                val cosine: Float
                val sine: Float
                when {
                    angle <= 90 -> {
                        cosine = cosDeg(angle)
                        sine = sinDeg(angle)
                    }

                    angle <= 180 -> {
                        cosine = -cosDeg(180 - angle)
                        sine = sinDeg(180 - angle)
                    }

                    angle <= 270 -> {
                        cosine = -cosDeg(angle - 180)
                        sine = -sinDeg(angle - 180)
                    }

                    else -> {
                        cosine = cosDeg(360 - angle)
                        sine = -sinDeg(360 - angle)
                    }
                }
                val deviationX = cosine * kx - sine * kz + px
                val deviationZ = cosine * kz + sine * kx + pz
                val deviation = abs(deviationX) + abs(deviationZ)
                if (deviation < minDeviation) {
                    minDeviation = deviation
                    minAngle = angle
                }
            }
            val error = max(abs(py), abs(ky))
            return if (error > 0.98f) -1 else minAngle
        }

        private fun cosDeg(degrees: Int): Float =
            cos(Math.toRadians(degrees.toDouble())).toFloat()

        private fun sinDeg(degrees: Int): Float =
            sin(Math.toRadians(degrees.toDouble())).toFloat()

        private fun invSqrt(value: Float): Float = 1f / sqrt(value)
    }
}

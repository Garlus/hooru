package com.purepixel.camera.model

import android.graphics.Bitmap
import android.content.Context
import android.os.PowerManager
import android.util.Log

internal object NativePresetProcessor {
    private const val TAG = "HooruPresetNative"

    private val available = runCatching {
        System.loadLibrary("hooru_native")
        true
    }.getOrElse {
        Log.w(TAG, "Native preset processor unavailable; using Kotlin fallback", it)
        false
    }

    fun configureForDevice(context: Context) {
        if (!available) return
        val power = context.getSystemService(PowerManager::class.java)
        val constrained = power?.isPowerSaveMode == true ||
            (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >=
            PowerManager.THERMAL_STATUS_MODERATE
        runCatching { setWorkerLimitNative(if (constrained) 2 else 4) }
    }

    fun process(
        source: Bitmap,
        destination: Bitmap,
        lut: ByteArray,
        lutSize: Int,
        preset: LightroomPreset,
        intensity: Float = 1f,
        extraGrain: Float = 0f,
        halation: Float = 0f
    ): Boolean {
        if (!available) return false
        return runCatching {
            processNative(
                source = source,
                destination = destination,
                lut = lut,
                lutSize = lutSize,
                clarity = preset.clarity,
                sharpness = preset.sharpness,
                sharpenRadius = preset.sharpenRadius,
                sharpenDetail = preset.sharpenDetail,
                sharpenMasking = preset.sharpenMasking,
                luminanceNoiseReduction = preset.luminanceNoiseReduction,
                colorNoiseReduction = preset.colorNoiseReduction,
                grain = preset.grain,
                grainSize = preset.grainSize,
                grainRoughness = preset.grainRoughness,
                intensity = intensity,
                extraGrain = extraGrain,
                halation = halation
            )
        }.getOrElse {
            Log.w(TAG, "Native preset processing failed; using Kotlin fallback", it)
            false
        }
    }

    fun finishLook(
        source: Bitmap,
        filtered: Bitmap,
        destination: Bitmap,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean {
        if (!available) return false
        return runCatching {
            finishLookNative(
                source = source,
                filtered = filtered,
                destination = destination,
                intensity = intensity,
                grain = grain,
                halation = halation
            )
        }.getOrElse {
            Log.w(TAG, "Native look finishing failed; using Kotlin fallback", it)
            false
        }
    }

    fun processColorMatrix(
        source: Bitmap,
        destination: Bitmap,
        matrix: FloatArray,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean {
        if (!available || matrix.size < 20) return false
        return runCatching {
            processColorMatrixNative(source, destination, matrix, intensity, grain, halation)
        }.getOrElse {
            Log.w(TAG, "Native color-matrix processing failed; using Canvas fallback", it)
            false
        }
    }

    private external fun processNative(
        source: Bitmap,
        destination: Bitmap,
        lut: ByteArray,
        lutSize: Int,
        clarity: Float,
        sharpness: Float,
        sharpenRadius: Float,
        sharpenDetail: Float,
        sharpenMasking: Float,
        luminanceNoiseReduction: Float,
        colorNoiseReduction: Float,
        grain: Float,
        grainSize: Float,
        grainRoughness: Float,
        intensity: Float,
        extraGrain: Float,
        halation: Float
    ): Boolean

    private external fun finishLookNative(
        source: Bitmap,
        filtered: Bitmap,
        destination: Bitmap,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean

    private external fun setWorkerLimitNative(limit: Int)

    private external fun processColorMatrixNative(
        source: Bitmap,
        destination: Bitmap,
        matrix: FloatArray,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean
}

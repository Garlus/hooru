package com.purepixel.camera.model

import android.graphics.Bitmap
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

    fun process(
        source: Bitmap,
        destination: Bitmap,
        lut: ByteArray,
        lutSize: Int,
        preset: LightroomPreset
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
                grainRoughness = preset.grainRoughness
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
        grainRoughness: Float
    ): Boolean

    private external fun finishLookNative(
        source: Bitmap,
        filtered: Bitmap,
        destination: Bitmap,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean
}

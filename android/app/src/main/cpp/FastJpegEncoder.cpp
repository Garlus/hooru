#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

struct RgbaPixel {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
};

inline float clamp01(float value) {
    return std::max(0.0f, std::min(1.0f, value));
}

inline float smoothstep(float edge0, float edge1, float value) {
    const float width = std::max(0.0001f, edge1 - edge0);
    const float t = clamp01((value - edge0) / width);
    return t * t * (3.0f - 2.0f * t);
}

inline float grainHash(int x, int y) {
    uint32_t n = static_cast<uint32_t>(x) * 374761393u +
                 static_cast<uint32_t>(y) * 668265263u;
    n = (n ^ (n >> 13u)) * 1274126177u;
    n ^= n >> 16u;
    return static_cast<float>(n & 0xffffu) / 32767.5f - 1.0f;
}

inline float radialGrain(int cellX, int cellY, float dx, float dy) {
    const float falloff = std::max(0.0f, 0.5f - dx * dx - dy * dy);
    const float squared = falloff * falloff;
    return squared * squared * grainHash(cellX, cellY);
}

inline float roundFilmGrain(float px, float py) {
    constexpr float f2 = 0.3660254038f;
    constexpr float g2 = 0.2113248654f;
    const float skew = (px + py) * f2;
    const int cellX = static_cast<int>(std::floor(px + skew));
    const int cellY = static_cast<int>(std::floor(py + skew));
    const float unskew = static_cast<float>(cellX + cellY) * g2;
    const float x0 = px - static_cast<float>(cellX) + unskew;
    const float y0 = py - static_cast<float>(cellY) + unskew;
    const int cornerX = x0 > y0 ? 1 : 0;
    const int cornerY = x0 > y0 ? 0 : 1;
    const float x1 = x0 - static_cast<float>(cornerX) + g2;
    const float y1 = y0 - static_cast<float>(cornerY) + g2;
    const float x2 = x0 - 1.0f + 2.0f * g2;
    const float y2 = y0 - 1.0f + 2.0f * g2;
    return 8.0f * (
        radialGrain(cellX, cellY, x0, y0) +
        radialGrain(cellX + cornerX, cellY + cornerY, x1, y1) +
        radialGrain(cellX + 1, cellY + 1, x2, y2)
    );
}

inline float lutValue(const uint8_t* lut, int size, int x, int y, int z, int channel) {
    return static_cast<float>(lut[((z * size * size + y * size + x) * 3) + channel]) / 255.0f;
}

inline void sampleLut(
    const uint8_t* lut,
    int size,
    float r,
    float g,
    float b,
    float output[3]
) {
    const float rx = clamp01(r) * static_cast<float>(size - 1);
    const float gy = clamp01(g) * static_cast<float>(size - 1);
    const float bz = clamp01(b) * static_cast<float>(size - 1);
    const int x0 = static_cast<int>(std::floor(rx));
    const int y0 = static_cast<int>(std::floor(gy));
    const int z0 = static_cast<int>(std::floor(bz));
    const int x1 = std::min(size - 1, x0 + 1);
    const int y1 = std::min(size - 1, y0 + 1);
    const int z1 = std::min(size - 1, z0 + 1);
    const float dx = rx - static_cast<float>(x0);
    const float dy = gy - static_cast<float>(y0);
    const float dz = bz - static_cast<float>(z0);

    for (int channel = 0; channel < 3; ++channel) {
        const float c00 = lutValue(lut, size, x0, y0, z0, channel) * (1.0f - dx) +
                          lutValue(lut, size, x1, y0, z0, channel) * dx;
        const float c10 = lutValue(lut, size, x0, y1, z0, channel) * (1.0f - dx) +
                          lutValue(lut, size, x1, y1, z0, channel) * dx;
        const float c01 = lutValue(lut, size, x0, y0, z1, channel) * (1.0f - dx) +
                          lutValue(lut, size, x1, y0, z1, channel) * dx;
        const float c11 = lutValue(lut, size, x0, y1, z1, channel) * (1.0f - dx) +
                          lutValue(lut, size, x1, y1, z1, channel) * dx;
        output[channel] = (c00 * (1.0f - dy) + c10 * dy) * (1.0f - dz) +
                          (c01 * (1.0f - dy) + c11 * dy) * dz;
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_purepixel_camera_model_NativePresetProcessor_processNative(
    JNIEnv* env,
    jobject,
    jobject sourceBitmap,
    jobject destinationBitmap,
    jbyteArray lutArray,
    jint lutSize,
    jfloat clarity,
    jfloat sharpness,
    jfloat sharpenRadius,
    jfloat sharpenDetail,
    jfloat sharpenMasking,
    jfloat luminanceNoiseReduction,
    jfloat colorNoiseReduction,
    jfloat grain,
    jfloat grainSize,
    jfloat grainRoughness
) {
    AndroidBitmapInfo sourceInfo{};
    AndroidBitmapInfo destinationInfo{};
    if (AndroidBitmap_getInfo(env, sourceBitmap, &sourceInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, destinationBitmap, &destinationInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        sourceInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        destinationInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        sourceInfo.width != destinationInfo.width ||
        sourceInfo.height != destinationInfo.height ||
        lutSize < 2 || env->GetArrayLength(lutArray) < lutSize * lutSize * lutSize * 3) {
        return JNI_FALSE;
    }

    void* sourcePixels = nullptr;
    void* destinationPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, sourceBitmap, &sourcePixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, destinationBitmap, &destinationPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }
    jbyte* lutBytes = env->GetByteArrayElements(lutArray, nullptr);
    if (lutBytes == nullptr) {
        AndroidBitmap_unlockPixels(env, destinationBitmap);
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }

    const auto* lut = reinterpret_cast<const uint8_t*>(lutBytes);
    const int width = static_cast<int>(sourceInfo.width);
    const int height = static_cast<int>(sourceInfo.height);
    const int radius = std::max(1, std::min(3, static_cast<int>(std::round(sharpenRadius))));
    const bool needsDetail = clarity != 0.0f || sharpness != 0.0f ||
                             luminanceNoiseReduction != 0.0f || colorNoiseReduction != 0.0f;
    const float clarityScale = clarity / 100.0f * 0.55f;
    const float sharpnessScale = sharpness / 100.0f *
                                 (0.45f + sharpenDetail / 100.0f * 0.55f);
    const float maskingStart = sharpenMasking / 100.0f * 0.25f;
    const float lumaNr = luminanceNoiseReduction / 100.0f * 0.7f;
    const float chromaNr = colorNoiseReduction / 100.0f * 0.75f;
    const float grainAmount = std::max(0.0f, std::min(100.0f, grain)) / 100.0f * 0.18f;
    const float grainScale = 1.0f + std::max(0.0f, std::min(100.0f, grainSize)) / 18.0f;
    const float roughness = std::max(0.0f, std::min(100.0f, grainRoughness)) / 100.0f;
    const float shortEdge = static_cast<float>(std::max(1, std::min(width, height)));

    auto sourceAt = [&](int x, int y) -> const RgbaPixel& {
        const auto* row = reinterpret_cast<const RgbaPixel*>(
            static_cast<const uint8_t*>(sourcePixels) + y * sourceInfo.stride
        );
        return row[x];
    };

    auto processRows = [&](int startY, int endY) {
        for (int y = startY; y < endY; ++y) {
            auto* outputRow = reinterpret_cast<RgbaPixel*>(
                static_cast<uint8_t*>(destinationPixels) + y * destinationInfo.stride
            );
            for (int x = 0; x < width; ++x) {
                const RgbaPixel& center = sourceAt(x, y);
                float red = static_cast<float>(center.r);
                float green = static_cast<float>(center.g);
                float blue = static_cast<float>(center.b);

                if (needsDetail) {
                    const RgbaPixel& left = sourceAt(std::max(0, x - radius), y);
                    const RgbaPixel& right = sourceAt(std::min(width - 1, x + radius), y);
                    const RgbaPixel& up = sourceAt(x, std::max(0, y - radius));
                    const RgbaPixel& down = sourceAt(x, std::min(height - 1, y + radius));
                    const float blurredRed = (left.r + right.r + up.r + down.r) * 0.25f;
                    const float blurredGreen = (left.g + right.g + up.g + down.g) * 0.25f;
                    const float blurredBlue = (left.b + right.b + up.b + down.b) * 0.25f;
                    const float centerL = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
                    const float blurL = 0.2126f * blurredRed + 0.7152f * blurredGreen + 0.0722f * blurredBlue;
                    const float edge = std::abs(centerL - blurL) / 255.0f;
                    const float mask = smoothstep(maskingStart, maskingStart + 0.08f, edge);
                    const float sharp = sharpnessScale * mask;
                    const float normalizedL = centerL / 255.0f;
                    const float local = clarityScale * smoothstep(0.08f, 0.82f, normalizedL) *
                                        (1.0f - smoothstep(0.82f, 1.0f, normalizedL));
                    const float targetL = centerL * (1.0f - lumaNr) + blurL * lumaNr;

                    auto detailChannel = [&](float value, float blurred) {
                        const float chroma = (value - centerL) * (1.0f - chromaNr) +
                                             (blurred - blurL) * chromaNr;
                        const float neutral = targetL + chroma;
                        return std::max(0.0f, std::min(255.0f,
                            neutral + (value - blurred) * (sharp + local)));
                    };
                    red = std::round(detailChannel(red, blurredRed));
                    green = std::round(detailChannel(green, blurredGreen));
                    blue = std::round(detailChannel(blue, blurredBlue));
                }

                float sampled[3];
                sampleLut(lut, lutSize, red / 255.0f, green / 255.0f, blue / 255.0f, sampled);
                if (grainAmount > 0.0001f) {
                    const float gx = static_cast<float>(x) * 1000.0f / shortEdge / grainScale;
                    const float gy = static_cast<float>(y) * 1000.0f / shortEdge / grainScale;
                    const float rounded = roundFilmGrain(gx, gy);
                    const float roughened = rounded * (1.55f - 1.1f * std::abs(rounded));
                    const float noiseBase = rounded * (1.0f - roughness) + roughened * roughness;
                    const float luminance = 0.2126f * sampled[0] + 0.7152f * sampled[1] + 0.0722f * sampled[2];
                    const float grainMask = 0.72f + 0.28f * (1.0f - std::abs(luminance * 2.0f - 1.0f));
                    const float noise = noiseBase * grainAmount * grainMask;
                    sampled[0] = clamp01(sampled[0] + noise);
                    sampled[1] = clamp01(sampled[1] + noise);
                    sampled[2] = clamp01(sampled[2] + noise);
                }

                outputRow[x] = RgbaPixel{
                    static_cast<uint8_t>(std::round(sampled[0] * 255.0f)),
                    static_cast<uint8_t>(std::round(sampled[1] * 255.0f)),
                    static_cast<uint8_t>(std::round(sampled[2] * 255.0f)),
                    center.a
                };
            }
        }
    };

    const unsigned hardwareThreads = std::max(1u, std::thread::hardware_concurrency());
    const int threadCount = width * height >= 1000000
        ? std::min(4, static_cast<int>(hardwareThreads))
        : 1;
    std::vector<std::thread> workers;
    workers.reserve(std::max(0, threadCount - 1));
    const int rowsPerThread = (height + threadCount - 1) / threadCount;
    for (int index = 1; index < threadCount; ++index) {
        const int start = index * rowsPerThread;
        const int end = std::min(height, start + rowsPerThread);
        if (start < end) workers.emplace_back(processRows, start, end);
    }
    processRows(0, std::min(height, rowsPerThread));
    for (auto& worker : workers) worker.join();

    env->ReleaseByteArrayElements(lutArray, lutBytes, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, destinationBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return JNI_TRUE;
}

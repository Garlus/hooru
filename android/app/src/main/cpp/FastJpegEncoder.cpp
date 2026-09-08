#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

std::atomic<int> workerLimit{4};

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

inline uint32_t grainHashBits(int x, int y, uint32_t seed = 0x484f4f52u) {
    uint32_t n = static_cast<uint32_t>(x) * 374761393u +
                 static_cast<uint32_t>(y) * 668265263u + seed;
    n = (n ^ (n >> 13u)) * 1274126177u;
    n ^= n >> 16u;
    return n;
}

inline float gaussianGrain(uint32_t bits) {
    // Four uniform byte samples form a compact Irwin-Hall approximation of a
    // zero-mean Gaussian. It avoids harsh uniform-noise extremes without the
    // expensive logarithm/cosine operations of Box-Muller.
    const float sum = static_cast<float>(
        (bits & 0xffu) + ((bits >> 8u) & 0xffu) +
        ((bits >> 16u) & 0xffu) + ((bits >> 24u) & 0xffu)
    );
    return sum / 510.0f - 1.0f;
}

inline float grainHash(int x, int y, uint32_t seed = 0x484f4f52u) {
    return gaussianGrain(grainHashBits(x, y, seed));
}

inline float radialGrain(int cellX, int cellY, float dx, float dy, uint32_t seed) {
    const float falloff = std::max(0.0f, 0.5f - dx * dx - dy * dy);
    const float squared = falloff * falloff;
    return squared * squared * grainHash(cellX, cellY, seed);
}

inline float roundFilmGrain(float px, float py, uint32_t seed = 0x484f4f52u) {
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
        radialGrain(cellX, cellY, x0, y0, seed) +
        radialGrain(cellX + cornerX, cellY + cornerY, x1, y1, seed) +
        radialGrain(cellX + 1, cellY + 1, x2, y2, seed)
    );
}

struct FilmGrainSample {
    float luminance;
    float chroma;
};

struct FilmGrainParameters {
    float strength;
    float inverseClusterPixels;
    float fineMix;
    float channelMaximum;
    float inverseChannelMaximum;
};

inline FilmGrainParameters makeFilmGrainParameters(
    float shortEdge,
    float strength,
    float size,
    float roughness,
    float channelMaximum
) {
    const float clusterPixels = std::max(
        0.85f,
        // About one visible cluster pixel when a 12 MP image is fit to a
        // 1000-pixel phone display. The previous 1800 reference made most
        // particles sub-pixel after display scaling and JPEG compression.
        shortEdge / 1200.0f * (0.75f + 2.25f * clamp01(size))
    );
    return FilmGrainParameters{
        strength,
        1.0f / clusterPixels,
        0.16f + 0.48f * clamp01(roughness),
        channelMaximum,
        1.0f / channelMaximum
    };
}

inline FilmGrainSample sampleFilmGrain(
    int x,
    int y,
    float inverseClusterPixels,
    float fineMix
) {
    const float clustered = roundFilmGrain(
        static_cast<float>(x) * inverseClusterPixels,
        static_cast<float>(y) * inverseClusterPixels,
        0x5f356495u
    );

    // Reuse one integer hash for microscopic crystals and a restrained,
    // decorrelated colour-layer component, avoiding two more hashes per pixel.
    const uint32_t fineBits = grainHashBits(x, y, 0x9e3779b9u);
    const float fine = gaussianGrain(fineBits);
    const uint32_t rotated = (fineBits << 11u) | (fineBits >> 21u);
    const float chroma = gaussianGrain(rotated ^ 0xa511e9b3u);
    return FilmGrainSample{
        (clustered * (1.0f - fineMix) + fine * fineMix) * 1.18f,
        chroma
    };
}

inline void applyFilmGrain(
    float& red,
    float& green,
    float& blue,
    int x,
    int y,
    const FilmGrainParameters& parameters
) {
    if (parameters.strength <= 0.0001f) return;
    const FilmGrainSample grain = sampleFilmGrain(
        x, y, parameters.inverseClusterPixels, parameters.fineMix
    );
    const float luminance = clamp01(
        (0.2126f * red + 0.7152f * green + 0.0722f * blue) * parameters.inverseChannelMaximum
    );

    // Use the same tonal envelope as the Blue-Noise preview. The native sample
    // remains clustered and Gaussian-like, but its perceived strength now tracks
    // the preview from shadows through highlights instead of only at mid-grey.
    const float midtonePresence = 1.0f - std::abs(luminance * 2.0f - 1.0f);
    const float densityResponse = 0.72f + 0.28f * midtonePresence;
    const float common = grain.luminance * parameters.strength * densityResponse * parameters.channelMaximum;

    // Colour stocks have separate emulsion layers, but perceived grain remains
    // predominantly luminance texture. Four percent prevents sterile monochrome
    // noise without creating digital-looking RGB speckles.
    const float colour =
        grain.chroma * parameters.strength * densityResponse * parameters.channelMaximum * 0.04f;
    red += common + colour;
    green += common - colour * 0.25f;
    blue += common + colour * 0.55f;
}

inline const uint8_t* lutPixel(const uint8_t* lut, int size, int x, int y, int z) {
    return lut + ((z * size * size + y * size + x) * 3);
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

    // Tetrahedral interpolation samples four RGB vertices instead of the eight
    // vertices required by trilinear interpolation. It is the standard high-
    // quality interpolation used by color pipelines and substantially reduces
    // memory traffic for every full-resolution pixel.
    const uint8_t* p0 = lutPixel(lut, size, x0, y0, z0);
    const uint8_t* p1;
    const uint8_t* p2;
    const uint8_t* p3 = lutPixel(lut, size, x1, y1, z1);
    float w1;
    float w2;
    float w3;

    if (dx >= dy) {
        if (dy >= dz) {
            p1 = lutPixel(lut, size, x1, y0, z0);
            p2 = lutPixel(lut, size, x1, y1, z0);
            w1 = dx; w2 = dy; w3 = dz;
        } else if (dx >= dz) {
            p1 = lutPixel(lut, size, x1, y0, z0);
            p2 = lutPixel(lut, size, x1, y0, z1);
            w1 = dx; w2 = dz; w3 = dy;
        } else {
            p1 = lutPixel(lut, size, x0, y0, z1);
            p2 = lutPixel(lut, size, x1, y0, z1);
            w1 = dz; w2 = dx; w3 = dy;
        }
    } else {
        if (dx >= dz) {
            p1 = lutPixel(lut, size, x0, y1, z0);
            p2 = lutPixel(lut, size, x1, y1, z0);
            w1 = dy; w2 = dx; w3 = dz;
        } else if (dy >= dz) {
            p1 = lutPixel(lut, size, x0, y1, z0);
            p2 = lutPixel(lut, size, x0, y1, z1);
            w1 = dy; w2 = dz; w3 = dx;
        } else {
            p1 = lutPixel(lut, size, x0, y0, z1);
            p2 = lutPixel(lut, size, x0, y1, z1);
            w1 = dz; w2 = dy; w3 = dx;
        }
    }

    constexpr float byteToFloat = 1.0f / 255.0f;
    for (int channel = 0; channel < 3; ++channel) {
        const float c0 = static_cast<float>(p0[channel]);
        const float c1 = static_cast<float>(p1[channel]);
        const float c2 = static_cast<float>(p2[channel]);
        const float c3 = static_cast<float>(p3[channel]);
        output[channel] = (
            c0 + w1 * (c1 - c0) + w2 * (c2 - c1) + w3 * (c3 - c2)
        ) * byteToFloat;
    }
}

template <typename Work>
void processInParallel(int width, int height, Work&& work) {
    const int pixels = width * height;
    const unsigned hardwareThreads = std::max(1u, std::thread::hardware_concurrency());
    // Four workers finish 12 MP captures quickly without waking every big core and
    // driving the phone into thermal throttling. The fused pipeline also invokes
    // this parallel pass only once for Lightroom captures.
    const int threadLimit = std::max(1, std::min(4, workerLimit.load(std::memory_order_relaxed)));
    const int threadCount = pixels >= 1000000
        ? std::min(threadLimit, static_cast<int>(hardwareThreads))
        : 1;
    std::vector<std::thread> workers;
    workers.reserve(std::max(0, threadCount - 1));
    const int rowsPerThread = (height + threadCount - 1) / threadCount;
    for (int index = 1; index < threadCount; ++index) {
        const int start = index * rowsPerThread;
        const int end = std::min(height, start + rowsPerThread);
        if (start < end) workers.emplace_back(work, start, end);
    }
    work(0, std::min(height, rowsPerThread));
    for (auto& worker : workers) worker.join();
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_purepixel_camera_model_NativePresetProcessor_setWorkerLimitNative(
    JNIEnv*,
    jobject,
    jint limit
) {
    workerLimit.store(std::max(1, std::min(4, static_cast<int>(limit))), std::memory_order_relaxed);
}

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
    jfloat grainRoughness,
    jfloat intensity,
    jfloat extraGrain,
    jfloat halation
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
    const float lookMix = clamp01(intensity);
    const float presetGrainStrength =
        std::max(0.0f, std::min(100.0f, grain)) / 100.0f * 0.70f;
    const float extraGrainStrength = clamp01(extraGrain) * 0.70f;
    // The preview adds both controls to the same Blue-Noise field. Add their
    // amplitudes here as well so combinations remain matched, while still
    // evaluating only one higher-quality native grain sample per pixel.
    const float combinedGrainWeight = presetGrainStrength + extraGrainStrength;
    const float filmGrainStrength = std::min(1.40f, combinedGrainWeight);
    const float filmGrainSize = combinedGrainWeight > 0.000001f
        ? (presetGrainStrength * clamp01(grainSize / 100.0f) + extraGrainStrength * 0.28f) /
            combinedGrainWeight
        : 0.28f;
    const float filmGrainRoughness = combinedGrainWeight > 0.000001f
        ? (presetGrainStrength * clamp01(grainRoughness / 100.0f) + extraGrainStrength * 0.62f) /
            combinedGrainWeight
        : 0.62f;
    const float halationStrength = clamp01(halation) * 0.24f;
    const float shortEdge = static_cast<float>(std::max(1, std::min(width, height)));
    const FilmGrainParameters filmGrain = makeFilmGrainParameters(
        shortEdge, filmGrainStrength, filmGrainSize, filmGrainRoughness, 1.0f
    );

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

                // Fuse the former finishLook pass into LUT processing. This avoids
                // allocating and streaming through another full-resolution bitmap.
                sampled[0] = center.r / 255.0f + (sampled[0] - center.r / 255.0f) * lookMix;
                sampled[1] = center.g / 255.0f + (sampled[1] - center.g / 255.0f) * lookMix;
                sampled[2] = center.b / 255.0f + (sampled[2] - center.b / 255.0f) * lookMix;
                if (halationStrength > 0.0001f) {
                    const float luma = 0.2126f * sampled[0] + 0.7152f * sampled[1] + 0.0722f * sampled[2];
                    const float highlight = clamp01((luma - 0.72f) / 0.28f) * halationStrength;
                    sampled[0] += highlight;
                    sampled[1] += highlight * (72.0f / 255.0f);
                    sampled[2] -= highlight * (32.0f / 255.0f);
                }
                applyFilmGrain(
                    sampled[0], sampled[1], sampled[2], x, y, filmGrain
                );

                outputRow[x] = RgbaPixel{
                    static_cast<uint8_t>(std::round(clamp01(sampled[0]) * 255.0f)),
                    static_cast<uint8_t>(std::round(clamp01(sampled[1]) * 255.0f)),
                    static_cast<uint8_t>(std::round(clamp01(sampled[2]) * 255.0f)),
                    center.a
                };
            }
        }
    };

    processInParallel(width, height, processRows);

    env->ReleaseByteArrayElements(lutArray, lutBytes, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, destinationBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_purepixel_camera_model_NativePresetProcessor_processColorMatrixNative(
    JNIEnv* env,
    jobject,
    jobject sourceBitmap,
    jobject destinationBitmap,
    jfloatArray matrixArray,
    jfloat intensity,
    jfloat grain,
    jfloat halation
) {
    AndroidBitmapInfo sourceInfo{};
    AndroidBitmapInfo destinationInfo{};
    if (AndroidBitmap_getInfo(env, sourceBitmap, &sourceInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, destinationBitmap, &destinationInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        sourceInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        destinationInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        sourceInfo.width != destinationInfo.width || sourceInfo.height != destinationInfo.height ||
        env->GetArrayLength(matrixArray) < 20) {
        return JNI_FALSE;
    }
    void* sourcePixels = nullptr;
    void* destinationPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, sourceBitmap, &sourcePixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, destinationBitmap, &destinationPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }
    jfloat matrix[20];
    env->GetFloatArrayRegion(matrixArray, 0, 20, matrix);
    if (env->ExceptionCheck()) {
        AndroidBitmap_unlockPixels(env, destinationBitmap);
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }

    const int width = static_cast<int>(sourceInfo.width);
    const int height = static_cast<int>(sourceInfo.height);
    const float mix = clamp01(intensity);
    const float grainStrength = clamp01(grain) * 0.70f;
    const float halationStrength = clamp01(halation) * 0.24f;
    const float shortEdge = static_cast<float>(std::max(1, std::min(width, height)));
    const FilmGrainParameters filmGrain = makeFilmGrainParameters(
        shortEdge, grainStrength, 0.28f, 0.62f, 255.0f
    );
    auto processRows = [&](int startY, int endY) {
        for (int y = startY; y < endY; ++y) {
            const auto* sourceRow = reinterpret_cast<const RgbaPixel*>(
                static_cast<const uint8_t*>(sourcePixels) + y * sourceInfo.stride
            );
            auto* destinationRow = reinterpret_cast<RgbaPixel*>(
                static_cast<uint8_t*>(destinationPixels) + y * destinationInfo.stride
            );
            for (int x = 0; x < width; ++x) {
                const RgbaPixel& source = sourceRow[x];
                const float filteredRed = matrix[0]*source.r + matrix[1]*source.g + matrix[2]*source.b + matrix[4];
                const float filteredGreen = matrix[5]*source.r + matrix[6]*source.g + matrix[7]*source.b + matrix[9];
                const float filteredBlue = matrix[10]*source.r + matrix[11]*source.g + matrix[12]*source.b + matrix[14];
                float red = source.r + (filteredRed - source.r) * mix;
                float green = source.g + (filteredGreen - source.g) * mix;
                float blue = source.b + (filteredBlue - source.b) * mix;
                if (halationStrength > 0.0001f) {
                    const float luma = (0.2126f*red + 0.7152f*green + 0.0722f*blue) / 255.0f;
                    const float highlight = clamp01((luma - 0.72f) / 0.28f) * halationStrength;
                    red += 255.0f * highlight;
                    green += 72.0f * highlight;
                    blue -= 32.0f * highlight;
                }
                applyFilmGrain(
                    red, green, blue, x, y, filmGrain
                );
                destinationRow[x] = RgbaPixel{
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, red)))),
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, green)))),
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, blue)))),
                    source.a
                };
            }
        }
    };
    processInParallel(width, height, processRows);
    AndroidBitmap_unlockPixels(env, destinationBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_purepixel_camera_model_NativePresetProcessor_finishLookNative(
    JNIEnv* env,
    jobject,
    jobject sourceBitmap,
    jobject filteredBitmap,
    jobject destinationBitmap,
    jfloat intensity,
    jfloat grain,
    jfloat halation
) {
    AndroidBitmapInfo sourceInfo{};
    AndroidBitmapInfo filteredInfo{};
    AndroidBitmapInfo destinationInfo{};
    if (AndroidBitmap_getInfo(env, sourceBitmap, &sourceInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, filteredBitmap, &filteredInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, destinationBitmap, &destinationInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        sourceInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        filteredInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        destinationInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        sourceInfo.width != filteredInfo.width || sourceInfo.height != filteredInfo.height ||
        sourceInfo.width != destinationInfo.width || sourceInfo.height != destinationInfo.height) {
        return JNI_FALSE;
    }

    void* sourcePixels = nullptr;
    void* filteredPixels = nullptr;
    void* destinationPixels = nullptr;
    const bool sharedInput = env->IsSameObject(sourceBitmap, filteredBitmap);
    if (AndroidBitmap_lockPixels(env, sourceBitmap, &sourcePixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (sharedInput) {
        filteredPixels = sourcePixels;
    } else if (AndroidBitmap_lockPixels(env, filteredBitmap, &filteredPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, destinationBitmap, &destinationPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        if (!sharedInput) AndroidBitmap_unlockPixels(env, filteredBitmap);
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return JNI_FALSE;
    }

    const int width = static_cast<int>(sourceInfo.width);
    const int height = static_cast<int>(sourceInfo.height);
    const float mix = clamp01(intensity);
    const float grainStrength = clamp01(grain) * 0.70f;
    const float halationStrength = clamp01(halation) * 0.24f;
    const float shortEdge = static_cast<float>(std::max(1, std::min(width, height)));
    const FilmGrainParameters filmGrain = makeFilmGrainParameters(
        shortEdge, grainStrength, 0.28f, 0.62f, 255.0f
    );

    auto finishRows = [&](int startY, int endY) {
        for (int y = startY; y < endY; ++y) {
            const auto* sourceRow = reinterpret_cast<const RgbaPixel*>(
                static_cast<const uint8_t*>(sourcePixels) + y * sourceInfo.stride
            );
            const auto* filteredRow = reinterpret_cast<const RgbaPixel*>(
                static_cast<const uint8_t*>(filteredPixels) + y * filteredInfo.stride
            );
            auto* destinationRow = reinterpret_cast<RgbaPixel*>(
                static_cast<uint8_t*>(destinationPixels) + y * destinationInfo.stride
            );
            for (int x = 0; x < width; ++x) {
                const RgbaPixel& source = sourceRow[x];
                const RgbaPixel& filtered = filteredRow[x];
                float red = source.r + (filtered.r - source.r) * mix;
                float green = source.g + (filtered.g - source.g) * mix;
                float blue = source.b + (filtered.b - source.b) * mix;

                if (halationStrength > 0.0001f) {
                    const float luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255.0f;
                    const float highlight = clamp01((luma - 0.72f) / 0.28f) * halationStrength;
                    red += 255.0f * highlight;
                    green += 72.0f * highlight;
                    blue -= 32.0f * highlight;
                }
                applyFilmGrain(
                    red, green, blue, x, y, filmGrain
                );

                destinationRow[x] = RgbaPixel{
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, red)))),
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, green)))),
                    static_cast<uint8_t>(std::round(std::max(0.0f, std::min(255.0f, blue)))),
                    source.a
                };
            }
        }
    };

    processInParallel(width, height, finishRows);

    AndroidBitmap_unlockPixels(env, destinationBitmap);
    if (!sharedInput) AndroidBitmap_unlockPixels(env, filteredBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return JNI_TRUE;
}

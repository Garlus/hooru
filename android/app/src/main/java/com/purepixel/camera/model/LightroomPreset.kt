package com.purepixel.camera.model

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

data class LightroomPreset(
    val name: String = "Imported preset",
    val exposure: Float = 0f, val contrast: Float = 0f,
    val highlights: Float = 0f, val shadows: Float = 0f,
    val whites: Float = 0f, val blacks: Float = 0f,
    val temperature: Float = 0f, val tint: Float = 0f,
    val vibrance: Float = 0f, val saturation: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f, val grain: Float = 0f,
    val grainSize: Float = 25f, val grainRoughness: Float = 50f,
    val sharpness: Float = 0f, val sharpenRadius: Float = 1f,
    val sharpenDetail: Float = 25f, val sharpenMasking: Float = 0f,
    val luminanceNoiseReduction: Float = 0f, val colorNoiseReduction: Float = 0f,
    val colorGradingShadowHue: Float = 0f, val colorGradingShadowSat: Float = 0f,
    val colorGradingMidtoneHue: Float = 0f, val colorGradingMidtoneSat: Float = 0f,
    val colorGradingHighlightHue: Float = 0f, val colorGradingHighlightSat: Float = 0f,
    val colorGradingBlending: Float = 50f, val colorGradingBalance: Float = 0f,
    val shadowTint: Float = 0f,
    val calibrationRedHue: Float = 0f, val calibrationRedSaturation: Float = 0f,
    val calibrationGreenHue: Float = 0f, val calibrationGreenSaturation: Float = 0f,
    val calibrationBlueHue: Float = 0f, val calibrationBlueSaturation: Float = 0f,
    val hue: Map<String, Float> = emptyMap(),
    val colorSaturation: Map<String, Float> = emptyMap(),
    val luminance: Map<String, Float> = emptyMap(),
    val toneCurve: List<Pair<Float, Float>> = emptyList(),
    val redCurve: List<Pair<Float, Float>> = emptyList(),
    val greenCurve: List<Pair<Float, Float>> = emptyList(),
    val blueCurve: List<Pair<Float, Float>> = emptyList()
) {
    private val lutCache = ConcurrentHashMap<Int, ByteBuffer>()

    companion object {
        const val DEFAULT_LUT_SIZE = 32
        private val colors = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")

        fun fromXmp(text: String, fallbackName: String): LightroomPreset {
            fun number(vararg names: String, default: Float = 0f): Float {
                names.forEach { name ->
                    Regex("crs:$name\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
                    Regex("<crs:$name>([^<]+)</crs:$name>", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
                }
                return default
            }
            fun string(name: String): String? = Regex("crs:$name\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
                ?: Regex("<crs:$name>.*?<rdf:li[^>]*>([^<]+)</rdf:li>.*?</crs:$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(text)?.groupValues?.get(1)?.trim()
            fun channel(prefix: String) = colors.associateWith { number("$prefix$it") }
            fun curve(name: String): List<Pair<Float, Float>> {
                val body = Regex("<crs:$name>.*?<rdf:Seq>(.*?)</rdf:Seq>.*?</crs:$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(text)?.groupValues?.get(1) ?: return emptyList()
                return Regex("<rdf:li>\\s*([+-]?[0-9.]+)\\s*,\\s*([+-]?[0-9.]+)\\s*</rdf:li>", RegexOption.IGNORE_CASE)
                    .findAll(body).mapNotNull { match ->
                        val x = match.groupValues[1].toFloatOrNull()
                        val y = match.groupValues[2].toFloatOrNull()
                        if (x != null && y != null) x / 255f to y / 255f else null
                    }.toList()
            }
            return LightroomPreset(
                name = string("Name") ?: fallbackName,
                exposure = number("Exposure2012", "Exposure"), contrast = number("Contrast2012", "Contrast"),
                highlights = number("Highlights2012"), shadows = number("Shadows2012"),
                whites = number("Whites2012"), blacks = number("Blacks2012"),
                temperature = number("Temperature"), tint = number("Tint"),
                vibrance = number("Vibrance"), saturation = number("Saturation"),
                clarity = number("Clarity2012", "Clarity"),
                dehaze = number("Dehaze"), grain = number("GrainAmount"),
                grainSize = number("GrainSize", default = 25f), grainRoughness = number("GrainFrequency", default = 50f),
                sharpness = number("Sharpness"), sharpenRadius = number("SharpenRadius", default = 1f),
                sharpenDetail = number("SharpenDetail", default = 25f), sharpenMasking = number("SharpenEdgeMasking"),
                luminanceNoiseReduction = number("LuminanceSmoothing"), colorNoiseReduction = number("ColorNoiseReduction"),
                colorGradingShadowHue = number("ColorGradeShadowHue", "SplitToningShadowHue"),
                colorGradingShadowSat = number("ColorGradeShadowSat", "SplitToningShadowSaturation"),
                colorGradingMidtoneHue = number("ColorGradeMidtoneHue"), colorGradingMidtoneSat = number("ColorGradeMidtoneSat"),
                colorGradingHighlightHue = number("ColorGradeHighlightHue", "SplitToningHighlightHue"),
                colorGradingHighlightSat = number("ColorGradeHighlightSat", "SplitToningHighlightSaturation"),
                colorGradingBlending = number("ColorGradeBlending", default = 50f),
                colorGradingBalance = number("ColorGradeBalance", "SplitToningBalance"),
                shadowTint = number("ShadowTint"),
                calibrationRedHue = number("RedHue"), calibrationRedSaturation = number("RedSaturation"),
                calibrationGreenHue = number("GreenHue"), calibrationGreenSaturation = number("GreenSaturation"),
                calibrationBlueHue = number("BlueHue"), calibrationBlueSaturation = number("BlueSaturation"),
                hue = channel("HueAdjustment"), colorSaturation = channel("SaturationAdjustment"), luminance = channel("LuminanceAdjustment"),
                toneCurve = curve("ToneCurvePV2012"), redCurve = curve("ToneCurvePV2012Red"),
                greenCurve = curve("ToneCurvePV2012Green"), blueCurve = curve("ToneCurvePV2012Blue")
            )
        }
    }

    fun buildLut(size: Int = DEFAULT_LUT_SIZE): ByteBuffer {
        val cached = lutCache.computeIfAbsent(size) {
            ByteBuffer.allocateDirect(size * size * size * 3).apply {
                for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                    val c = transform(r / (size - 1f), g / (size - 1f), b / (size - 1f))
                    put((c[0] * 255f).roundToInt().coerceIn(0, 255).toByte())
                    put((c[1] * 255f).roundToInt().coerceIn(0, 255).toByte())
                    put((c[2] * 255f).roundToInt().coerceIn(0, 255).toByte())
                }
                position(0)
            }.asReadOnlyBuffer()
        }
        return cached.duplicate().apply { position(0) }
    }

    fun applyToBitmap(source: Bitmap, lutSize: Int = DEFAULT_LUT_SIZE): Bitmap {
        val width = source.width
        val height = source.height
        val lutBuffer = buildLut(lutSize)
        val lut = ByteArray(lutBuffer.remaining())
        lutBuffer.get(lut)
        val nativeOutput = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (NativePresetProcessor.process(source, nativeOutput, lut, lutSize, this)) {
            return nativeOutput
        }
        nativeOutput.recycle()

        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val spatial = applySpatialDetail(input, width, height)
        val sampled = FloatArray(3)
        for (i in spatial.indices) {
            val p = spatial[i]
            sampleLut(
                lut,
                Color.red(p) / 255f,
                Color.green(p) / 255f,
                Color.blue(p) / 255f,
                lutSize,
                sampled
            )
            if (grain > 0f) {
                val x = i % width; val y = i / width
                val luminance = .2126f*sampled[0] + .7152f*sampled[1] + .0722f*sampled[2]
                val midtonePresence = 1f - abs(luminance * 2f - 1f)
                val noise = grainNoise(x, y, width, height) * (.72f + .28f * midtonePresence)
                for (j in 0..2) sampled[j] = (sampled[j] + noise).coerceIn(0f, 1f)
            }
            spatial[i] = Color.argb(
                Color.alpha(p),
                (sampled[0]*255).roundToInt(),
                (sampled[1]*255).roundToInt(),
                (sampled[2]*255).roundToInt()
            )
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(spatial, 0, width, 0, 0, width, height)
        return output
    }

    private fun sampleLut(
        lut: ByteArray,
        r: Float,
        g: Float,
        b: Float,
        size: Int = DEFAULT_LUT_SIZE,
        output: FloatArray
    ) {
        val rx = r * (size-1); val gy = g * (size-1); val bz = b * (size-1)
        val x0 = floor(rx).toInt(); val y0 = floor(gy).toInt(); val z0 = floor(bz).toInt()
        val x1 = min(size-1, x0+1); val y1 = min(size-1, y0+1); val z1 = min(size-1, z0+1)
        val dx = rx-x0; val dy = gy-y0; val dz = bz-z0
        fun value(x: Int, y: Int, z: Int, channel: Int) = (lut[((z*size*size+y*size+x)*3)+channel].toInt() and 255) / 255f
        for (channel in 0..2) {
            val c00 = value(x0,y0,z0,channel)*(1-dx)+value(x1,y0,z0,channel)*dx
            val c10 = value(x0,y1,z0,channel)*(1-dx)+value(x1,y1,z0,channel)*dx
            val c01 = value(x0,y0,z1,channel)*(1-dx)+value(x1,y0,z1,channel)*dx
            val c11 = value(x0,y1,z1,channel)*(1-dx)+value(x1,y1,z1,channel)*dx
            output[channel] = (c00*(1-dy)+c10*dy)*(1-dz)+(c01*(1-dy)+c11*dy)*dz
        }
    }

    private fun applySpatialDetail(input: IntArray, width: Int, height: Int): IntArray {
        if (clarity == 0f && sharpness == 0f && luminanceNoiseReduction == 0f && colorNoiseReduction == 0f) return input.copyOf()
        val out = IntArray(input.size)
        val radius = sharpenRadius.roundToInt().coerceIn(1, 3)
        for (y in 0 until height) for (x in 0 until width) {
            val center = input[y * width + x]
            val left = input[y * width + (x - radius).coerceIn(0, width - 1)]
            val right = input[y * width + (x + radius).coerceIn(0, width - 1)]
            val up = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val down = input[(y + radius).coerceIn(0, height - 1) * width + x]
            val sr = Color.red(left) + Color.red(right) + Color.red(up) + Color.red(down)
            val sg = Color.green(left) + Color.green(right) + Color.green(up) + Color.green(down)
            val sb = Color.blue(left) + Color.blue(right) + Color.blue(up) + Color.blue(down)
            val cr = Color.red(center).toFloat(); val cg = Color.green(center).toFloat(); val cb = Color.blue(center).toFloat()
            val br = sr * .25f; val bg = sg * .25f; val bb = sb * .25f
            val centerL = .2126f*cr + .7152f*cg + .0722f*cb
            val blurL = .2126f*br + .7152f*bg + .0722f*bb
            val edge = abs(centerL - blurL) / 255f
            val mask = smoothstep(sharpenMasking / 100f * .25f, sharpenMasking / 100f * .25f + .08f, edge)
            val sharp = sharpness / 100f * (.45f + sharpenDetail / 100f * .55f) * mask
            val local = clarity / 100f * .55f * smoothstep(.08f, .82f, centerL / 255f) * (1f - smoothstep(.82f, 1f, centerL / 255f))
            val lumaNr = luminanceNoiseReduction / 100f * .7f
            val chromaNr = colorNoiseReduction / 100f * .75f
            val targetL = centerL * (1f - lumaNr) + blurL * lumaNr
            fun channel(value: Float, blurred: Float): Float {
                val chroma = (value - centerL) * (1f - chromaNr) + (blurred - blurL) * chromaNr
                val neutral = targetL + chroma
                return (neutral + (value - blurred) * (sharp + local)).coerceIn(0f, 255f)
            }
            out[y*width+x] = Color.argb(Color.alpha(center), channel(cr,br).roundToInt(), channel(cg,bg).roundToInt(), channel(cb,bb).roundToInt())
        }
        return out
    }

    private fun grainHash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xffff) / 32767.5f - 1f
    }

    private fun radialGrain(cellX: Int, cellY: Int, dx: Float, dy: Float): Float {
        val falloff = (.5f - dx*dx - dy*dy).coerceAtLeast(0f)
        val roundKernel = falloff * falloff * falloff * falloff
        return roundKernel * grainHash(cellX, cellY)
    }

    private fun roundFilmGrain(px: Float, py: Float): Float {
        val f2 = .3660254038f
        val g2 = .2113248654f
        val skew = (px + py) * f2
        val cellX = floor(px + skew).toInt()
        val cellY = floor(py + skew).toInt()
        val unskew = (cellX + cellY) * g2
        val x0 = px - cellX + unskew
        val y0 = py - cellY + unskew
        val cornerX = if (x0 > y0) 1 else 0
        val cornerY = if (x0 > y0) 0 else 1
        val x1 = x0 - cornerX + g2
        val y1 = y0 - cornerY + g2
        val x2 = x0 - 1f + 2f*g2
        val y2 = y0 - 1f + 2f*g2
        return 8f * (
            radialGrain(cellX, cellY, x0, y0) +
            radialGrain(cellX + cornerX, cellY + cornerY, x1, y1) +
            radialGrain(cellX + 1, cellY + 1, x2, y2)
        )
    }

    private fun grainNoise(x: Int, y: Int, width: Int, height: Int): Float {
        val scale = (1f + grainSize.coerceIn(0f, 100f) / 18f)
        val shortEdge = min(width, height).coerceAtLeast(1).toFloat()
        val gx = x * 1000f / shortEdge / scale
        val gy = y * 1000f / shortEdge / scale
        val rounded = roundFilmGrain(gx, gy)
        val roughness = grainRoughness.coerceIn(0f, 100f) / 100f
        val roughened = rounded * (1.55f - 1.1f * abs(rounded))
        val noise = rounded * (1f - roughness) + roughened * roughness
        return noise * grain.coerceIn(0f, 100f) / 100f * .18f
    }

    private fun transform(red: Float, green: Float, blue: Float): FloatArray {
        var r = red * 2f.pow(exposure); var g = green * 2f.pow(exposure); var b = blue * 2f.pow(exposure)
        val wb = whiteBalanceMultipliers(temperature, tint)
        r *= wb[0]; g *= wb[1]; b *= wb[2]
        val calibrated = applyCameraCalibration(r, g, b)
        r = calibrated[0]; g = calibrated[1]; b = calibrated[2]
        val cf = 1f + contrast / 100f
        r = (r - .5f) * cf + .5f; g = (g - .5f) * cf + .5f; b = (b - .5f) * cf + .5f
        var l = .2126f * r + .7152f * g + .0722f * b
        fun delta(value: Float, weight: Float, scale: Float) = value / 100f * weight * scale
        val tone = delta(highlights, smoothstep(.35f, 1f, l), .24f) +
            delta(shadows, (1f - smoothstep(.05f, .68f, l)) * smoothstep(0f, .18f, l), .24f) +
            delta(whites, l.coerceIn(0f, 1f).pow(3), .18f) + delta(blacks, (1f - l).coerceIn(0f, 1f).pow(3), .18f)
        r += tone; g += tone; b += tone
        l = (.2126f * r + .7152f * g + .0722f * b).coerceIn(0f, 1f)
        val haze = dehaze / 100f
        r = (r - .5f) * (1f + haze * .35f) + .5f - haze * .03f
        g = (g - .5f) * (1f + haze * .35f) + .5f - haze * .03f
        b = (b - .5f) * (1f + haze * .35f) + .5f - haze * .03f
        val satNow = max(r, max(g, b)) - min(r, min(g, b))
        val satFactor = (1f + saturation / 100f) * (1f + vibrance / 100f * (1f - satNow.coerceIn(0f, 1f)))
        r = l + (r - l) * satFactor; g = l + (g - l) * satFactor; b = l + (b - l) * satFactor
        // Lightroom's point curves shape the tonal image before HSL and color
        // grading. Applying them last distorted mixer hue bands and split toning.
        r = evaluateCurve(redCurve, evaluateCurve(toneCurve, r))
        g = evaluateCurve(greenCurve, evaluateCurve(toneCurve, g))
        b = evaluateCurve(blueCurve, evaluateCurve(toneCurve, b))
        val mixed = applyColorMixer(r, g, b)
        r = mixed[0]; g = mixed[1]; b = mixed[2]
        l = (.2126f * r + .7152f * g + .0722f * b).coerceIn(0f, 1f)
        return applyGrade(floatArrayOf(r, g, b), l)
    }

    private fun applyColorMixer(r: Float, g: Float, b: Float): FloatArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb((r*255).roundToInt().coerceIn(0,255), (g*255).roundToInt().coerceIn(0,255), (b*255).roundToInt().coerceIn(0,255)), hsv)
        val centers = mapOf("Red" to 0f, "Orange" to 30f, "Yellow" to 60f, "Green" to 120f, "Aqua" to 180f, "Blue" to 240f, "Purple" to 280f, "Magenta" to 320f)
        var hueDelta = 0f; var saturationDelta = 0f; var luminanceDelta = 0f; var totalWeight = 0f
        centers.forEach { (name, center) ->
            var distance = abs(hsv[0] - center); if (distance > 180f) distance = 360f - distance
            val weight = if (distance < 60f) (cos(distance / 60f * PI).toFloat() + 1f) * .5f else 0f
            hueDelta += (hue[name] ?: 0f) * weight
            saturationDelta += (colorSaturation[name] ?: 0f) * weight
            luminanceDelta += (luminance[name] ?: 0f) * weight
            totalWeight += weight
        }
        if (totalWeight > 1f) { hueDelta /= totalWeight; saturationDelta /= totalWeight; luminanceDelta /= totalWeight }
        hsv[0] = (hsv[0] + hueDelta * .30f + 360f) % 360f
        hsv[1] = (hsv[1] * (1f + saturationDelta / 100f)).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * (1f + luminanceDelta / 100f * .65f)).coerceIn(0f, 1f)
        val color = Color.HSVToColor(hsv)
        return floatArrayOf(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
    }

    private fun whiteBalanceMultipliers(kelvinOrRelative: Float, tintValue: Float): FloatArray {
        if (kelvinOrRelative == 0f && tintValue == 0f) return floatArrayOf(1f, 1f, 1f)
        val kelvin = if (abs(kelvinOrRelative) > 1000f) kelvinOrRelative.coerceIn(2000f, 50000f)
            else (6500f + kelvinOrRelative * 45f).coerceIn(2000f, 50000f)
        fun kelvinRgb(k: Float): FloatArray {
            val t = k / 100f
            val red = if (t <= 66f) 255f else 329.69873f * (t - 60f).pow(-.13320476f)
            val green = if (t <= 66f) 99.4708f * ln(t) - 161.11957f else 288.12216f * (t - 60f).pow(-.07551485f)
            val blue = if (t >= 66f) 255f else if (t <= 19f) 0f else 138.51773f * ln(t - 10f) - 305.0448f
            return floatArrayOf(red.coerceIn(0f,255f), green.coerceIn(0f,255f), blue.coerceIn(0f,255f))
        }
        val target = kelvinRgb(kelvin); val neutral = kelvinRgb(6500f)
        // A Lightroom temperature value describes the illuminant to neutralize,
        // not the color of a warming overlay. Inverting the illuminant fixes the
        // previous warm/cool reversal. Normalizing by green approximates the
        // chromatic adaptation while preserving scene luminance.
        val base = floatArrayOf(neutral[0]/target[0].coerceAtLeast(.001f), neutral[1]/target[1].coerceAtLeast(.001f), neutral[2]/target[2].coerceAtLeast(.001f))
        val normalized = base[1].coerceAtLeast(.001f)
        val tintScale = tintValue.coerceIn(-150f,150f) / 150f
        val adaptationStrength = 1f
        return floatArrayOf(
            (base[0]/normalized).pow(adaptationStrength) * (1f + tintScale*.12f),
            1f - tintScale*.18f,
            (base[2]/normalized).pow(adaptationStrength) * (1f + tintScale*.10f)
        )
    }

    private fun evaluateCurve(points: List<Pair<Float, Float>>, value: Float): Float {
        if (points.isEmpty()) return value.coerceIn(0f,1f)
        if (value <= points.first().first) return points.first().second
        if (value >= points.last().first) return points.last().second
        val upper = points.indexOfFirst { it.first >= value }.coerceAtLeast(1)
        val a = points[upper-1]; val b = points[upper]
        val t = ((value - a.first) / (b.first - a.first).coerceAtLeast(.0001f)).coerceIn(0f,1f)
        fun secant(index: Int): Float {
            val p0 = points[index]
            val p1 = points[index + 1]
            return (p1.second - p0.second) / (p1.first - p0.first).coerceAtLeast(.0001f)
        }
        fun slope(index: Int): Float {
            if (index <= 0) return secant(0)
            if (index >= points.lastIndex) return secant(points.lastIndex - 1)
            val before = secant(index - 1)
            val after = secant(index)
            if (before * after <= 0f) return 0f
            val h0 = points[index].first - points[index - 1].first
            val h1 = points[index + 1].first - points[index].first
            val w0 = 2f * h1 + h0
            val w1 = h1 + 2f * h0
            return (w0 + w1) / (w0 / before + w1 / after)
        }
        val segment = (b.first - a.first).coerceAtLeast(.0001f)
        val t2 = t * t; val t3 = t2 * t
        val result = (2f*t3 - 3f*t2 + 1f) * a.second +
            (t3 - 2f*t2 + t) * segment * slope(upper - 1) +
            (-2f*t3 + 3f*t2) * b.second +
            (t3 - t2) * segment * slope(upper)
        return result.coerceIn(0f,1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x-edge0)/(edge1-edge0).coerceAtLeast(.0001f)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }

    private fun applyGrade(c: FloatArray, l: Float): FloatArray {
        fun tint(hue: Float, sat: Float, weight: Float) {
            if (sat <= 0f || weight <= 0f) return
            val h = hue / 60f; val x = 1f - abs(h % 2f - 1f)
            val rgb = when (floor(h).toInt().mod(6)) { 0 -> floatArrayOf(1f,x,0f); 1 -> floatArrayOf(x,1f,0f); 2 -> floatArrayOf(0f,1f,x); 3 -> floatArrayOf(0f,x,1f); 4 -> floatArrayOf(x,0f,1f); else -> floatArrayOf(1f,0f,x) }
            val a = sat / 100f * weight * .45f
            for (i in 0..2) c[i] = c[i] * (1f - a) + rgb[i] * a
        }
        val balance = colorGradingBalance / 200f
        val gradePower = 2.4f - colorGradingBlending.coerceIn(0f, 100f) / 100f * 1.4f
        tint(colorGradingShadowHue, colorGradingShadowSat, ((.58f + balance - l) / .58f).coerceIn(0f,1f).pow(gradePower))
        tint(colorGradingMidtoneHue, colorGradingMidtoneSat, (1f - abs(l - .5f) * 2f).coerceIn(0f,1f))
        tint(colorGradingHighlightHue, colorGradingHighlightSat, ((l - .42f + balance) / .58f).coerceIn(0f,1f).pow(gradePower))
        for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f)
        return c
    }

    private fun applyCameraCalibration(r: Float, g: Float, b: Float): FloatArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(
            Color.rgb((r*255).roundToInt().coerceIn(0,255), (g*255).roundToInt().coerceIn(0,255), (b*255).roundToInt().coerceIn(0,255)),
            hsv
        )
        data class Primary(val center: Float, val hue: Float, val saturation: Float)
        val primaries = listOf(
            Primary(0f, calibrationRedHue, calibrationRedSaturation),
            Primary(120f, calibrationGreenHue, calibrationGreenSaturation),
            Primary(240f, calibrationBlueHue, calibrationBlueSaturation)
        )
        var weightSum = 0f; var hueShift = 0f; var saturationShift = 0f
        primaries.forEach { primary ->
            var distance = abs(hsv[0] - primary.center)
            if (distance > 180f) distance = 360f - distance
            val weight = ((cos((distance / 120f).coerceIn(0f, 1f) * PI).toFloat() + 1f) * .5f)
            weightSum += weight
            hueShift += primary.hue * weight
            saturationShift += primary.saturation * weight
        }
        if (weightSum > 0f) {
            hueShift /= weightSum
            saturationShift /= weightSum
        }
        hsv[0] = (hsv[0] + hueShift * .22f + 360f) % 360f
        hsv[1] = (hsv[1] * (1f + saturationShift / 100f)).coerceIn(0f, 1f)
        val calibrated = Color.HSVToColor(hsv)
        val out = floatArrayOf(Color.red(calibrated)/255f, Color.green(calibrated)/255f, Color.blue(calibrated)/255f)
        val shadowWeight = (1f - (.2126f*out[0] + .7152f*out[1] + .0722f*out[2]).coerceIn(0f, 1f)).pow(2)
        val magenta = shadowTint.coerceIn(-100f, 100f) / 100f * shadowWeight * .20f
        out[0] *= 1f + magenta; out[1] *= 1f - magenta; out[2] *= 1f + magenta
        return out
    }
}

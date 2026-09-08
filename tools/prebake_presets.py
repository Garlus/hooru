#!/usr/bin/env python3
"""Prebake packaged Hooru XMPs into 32³ LUTs and instant WebP cards."""

from __future__ import annotations

import colorsys
import math
import re
import zlib
from pathlib import Path

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main"
LUT_DIR = ANDROID / "assets/preset_luts"
PREVIEW_DIR = ANDROID / "assets/preset_previews"
LUT_SIZE = 32
COLOR_NAMES = ("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
COLOR_CENTERS = dict(zip(COLOR_NAMES, (0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 280.0, 320.0)))


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def smoothstep(a: float, b: float, value: float) -> float:
    t = clamp((value - a) / max(0.0001, b - a))
    return t * t * (3.0 - 2.0 * t)


def parse_xmp(path: Path) -> dict:
    text = path.read_text(errors="replace")

    def number(*names: str, default: float = 0.0) -> float:
        for name in names:
            match = re.search(rf"crs:{name}\s*=\s*[\"']([^\"']+)", text, re.I)
            if not match:
                match = re.search(rf"<crs:{name}>([^<]+)</crs:{name}>", text, re.I)
            if match:
                try:
                    return float(match.group(1))
                except ValueError:
                    pass
        return default

    def channel(prefix: str) -> dict[str, float]:
        return {name: number(prefix + name) for name in COLOR_NAMES}

    def curve(name: str) -> list[tuple[float, float]]:
        body = re.search(rf"<crs:{name}>.*?<rdf:Seq>(.*?)</rdf:Seq>.*?</crs:{name}>", text, re.I | re.S)
        if not body:
            return []
        return [(float(x) / 255.0, float(y) / 255.0) for x, y in re.findall(
            r"<rdf:li>\s*([+-]?[0-9.]+)\s*,\s*([+-]?[0-9.]+)\s*</rdf:li>", body.group(1), re.I
        )]

    return {
        "exposure": number("Exposure2012", "Exposure"), "contrast": number("Contrast2012", "Contrast"),
        "highlights": number("Highlights2012"), "shadows": number("Shadows2012"),
        "whites": number("Whites2012"), "blacks": number("Blacks2012"),
        "temperature": number("Temperature"), "tint": number("Tint"),
        "vibrance": number("Vibrance"), "saturation": number("Saturation"),
        "dehaze": number("Dehaze"), "grain": number("GrainAmount"),
        "shadow_tint": number("ShadowTint"),
        "red_hue": number("RedHue"), "red_sat": number("RedSaturation"),
        "green_hue": number("GreenHue"), "green_sat": number("GreenSaturation"),
        "blue_hue": number("BlueHue"), "blue_sat": number("BlueSaturation"),
        "shadow_hue": number("ColorGradeShadowHue", "SplitToningShadowHue"),
        "shadow_sat": number("ColorGradeShadowSat", "SplitToningShadowSaturation"),
        "midtone_hue": number("ColorGradeMidtoneHue"), "midtone_sat": number("ColorGradeMidtoneSat"),
        "highlight_hue": number("ColorGradeHighlightHue", "SplitToningHighlightHue"),
        "highlight_sat": number("ColorGradeHighlightSat", "SplitToningHighlightSaturation"),
        "blending": number("ColorGradeBlending", default=50.0),
        "balance": number("ColorGradeBalance", "SplitToningBalance"),
        "hue": channel("HueAdjustment"), "color_sat": channel("SaturationAdjustment"),
        "luminance": channel("LuminanceAdjustment"),
        "tone_curve": curve("ToneCurvePV2012"), "red_curve": curve("ToneCurvePV2012Red"),
        "green_curve": curve("ToneCurvePV2012Green"), "blue_curve": curve("ToneCurvePV2012Blue"),
    }


def evaluate_curve(points: list[tuple[float, float]], value: float) -> float:
    if not points:
        return clamp(value)
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    upper = next(index for index, point in enumerate(points) if point[0] >= value)
    a, b = points[upper - 1], points[upper]
    t = clamp((value - a[0]) / max(0.0001, b[0] - a[0]))

    def secant(index: int) -> float:
        p0, p1 = points[index], points[index + 1]
        return (p1[1] - p0[1]) / max(0.0001, p1[0] - p0[0])

    def slope(index: int) -> float:
        if index <= 0: return secant(0)
        if index >= len(points) - 1: return secant(len(points) - 2)
        before, after = secant(index - 1), secant(index)
        if before * after <= 0: return 0.0
        h0 = points[index][0] - points[index - 1][0]
        h1 = points[index + 1][0] - points[index][0]
        w0, w1 = 2 * h1 + h0, h1 + 2 * h0
        return (w0 + w1) / (w0 / before + w1 / after)

    t2, t3 = t * t, t * t * t
    segment = max(0.0001, b[0] - a[0])
    return clamp((2*t3 - 3*t2 + 1)*a[1] + (t3 - 2*t2 + t)*segment*slope(upper-1)
                 + (-2*t3 + 3*t2)*b[1] + (t3 - t2)*segment*slope(upper))


def white_balance(temperature: float, tint: float) -> tuple[float, float, float]:
    if temperature == 0 and tint == 0:
        return 1.0, 1.0, 1.0
    kelvin = clamp(temperature if abs(temperature) > 1000 else 6500 + temperature * 45, 2000, 50000)

    def kelvin_rgb(k: float) -> tuple[float, float, float]:
        t = k / 100
        red = 255 if t <= 66 else 329.69873 * (t - 60) ** -0.13320476
        green = 99.4708 * math.log(t) - 161.11957 if t <= 66 else 288.12216 * (t - 60) ** -0.07551485
        blue = 255 if t >= 66 else 0 if t <= 19 else 138.51773 * math.log(t - 10) - 305.0448
        return clamp(red, 0, 255), clamp(green, 0, 255), clamp(blue, 0, 255)

    target, neutral = kelvin_rgb(kelvin), kelvin_rgb(6500)
    base = tuple(neutral[i] / max(0.001, target[i]) for i in range(3))
    normalized, tint_scale = max(0.001, base[1]), clamp(tint, -150, 150) / 150
    return (base[0] / normalized * (1 + tint_scale*.12), 1 - tint_scale*.18,
            base[2] / normalized * (1 + tint_scale*.10))


def hsv_adjust(rgb: tuple[float, float, float], p: dict, calibration: bool) -> tuple[float, float, float]:
    bounded = tuple(clamp(round(c * 255) / 255) for c in rgb)
    h, s, v = colorsys.rgb_to_hsv(*bounded)
    hue = h * 360
    if calibration:
        primaries = ((0, p["red_hue"], p["red_sat"]), (120, p["green_hue"], p["green_sat"]), (240, p["blue_hue"], p["blue_sat"]))
        weights = [(math.cos(clamp(abs((hue-center+180) % 360-180) / 120) * math.pi) + 1) * .5 for center, _, _ in primaries]
        total = max(0.0001, sum(weights))
        hue += sum(item[1] * weight for item, weight in zip(primaries, weights)) / total * .22
        s *= 1 + sum(item[2] * weight for item, weight in zip(primaries, weights)) / total / 100
    else:
        weights = []
        hd = sd = ld = total = 0.0
        for name, center in COLOR_CENTERS.items():
            distance = abs((hue - center + 180) % 360 - 180)
            weight = (math.cos(distance / 60 * math.pi) + 1) * .5 if distance < 60 else 0
            hd += p["hue"][name] * weight; sd += p["color_sat"][name] * weight
            ld += p["luminance"][name] * weight; total += weight
        if total > 1: hd /= total; sd /= total; ld /= total
        hue += hd * .30; s *= 1 + sd / 100; v *= 1 + ld / 100 * .65
    out = colorsys.hsv_to_rgb((hue % 360) / 360, clamp(s), clamp(v))
    if calibration:
        shadow = (1 - clamp(.2126*out[0] + .7152*out[1] + .0722*out[2])) ** 2
        magenta = clamp(p["shadow_tint"], -100, 100) / 100 * shadow * .20
        out = (out[0]*(1+magenta), out[1]*(1-magenta), out[2]*(1+magenta))
    return out


def transform(rgb: tuple[float, float, float], p: dict) -> tuple[float, float, float]:
    gain = 2 ** p["exposure"]
    wb = white_balance(p["temperature"], p["tint"])
    r, g, b = hsv_adjust(tuple(rgb[i] * gain * wb[i] for i in range(3)), p, True)
    contrast = 1 + p["contrast"] / 100
    r, g, b = ((c - .5) * contrast + .5 for c in (r, g, b))
    luma = .2126*r + .7152*g + .0722*b
    tone = (p["highlights"]/100*smoothstep(.35, 1, luma)*.24
            + p["shadows"]/100*(1-smoothstep(.05, .68, luma))*smoothstep(0, .18, luma)*.24
            + p["whites"]/100*clamp(luma)**3*.18 + p["blacks"]/100*clamp(1-luma)**3*.18)
    r, g, b = r+tone, g+tone, b+tone
    luma = clamp(.2126*r + .7152*g + .0722*b)
    haze = p["dehaze"] / 100
    r, g, b = (((c-.5)*(1+haze*.35)+.5-haze*.03) for c in (r, g, b))
    spread = max(r, g, b) - min(r, g, b)
    saturation = (1+p["saturation"]/100) * (1+p["vibrance"]/100*(1-clamp(spread)))
    r, g, b = (luma+(c-luma)*saturation for c in (r, g, b))
    r = evaluate_curve(p["red_curve"], evaluate_curve(p["tone_curve"], r))
    g = evaluate_curve(p["green_curve"], evaluate_curve(p["tone_curve"], g))
    b = evaluate_curve(p["blue_curve"], evaluate_curve(p["tone_curve"], b))
    r, g, b = hsv_adjust((r, g, b), p, False)
    luma = clamp(.2126*r + .7152*g + .0722*b)
    result = [r, g, b]
    balance, power = p["balance"]/200, 2.4-p["blending"]/100*1.4

    def tint(hue: float, sat: float, weight: float) -> None:
        if sat <= 0 or weight <= 0: return
        target = colorsys.hsv_to_rgb((hue % 360)/360, 1, 1)
        alpha = sat/100 * weight * .45
        for i in range(3): result[i] = result[i]*(1-alpha) + target[i]*alpha

    tint(p["shadow_hue"], p["shadow_sat"], clamp((.58+balance-luma)/.58) ** power)
    tint(p["midtone_hue"], p["midtone_sat"], clamp(1-abs(luma-.5)*2))
    tint(p["highlight_hue"], p["highlight_sat"], clamp((luma-.42+balance)/.58) ** power)
    return tuple(clamp(c) for c in result)


def build_lut(preset: dict) -> np.ndarray:
    lut = np.empty((LUT_SIZE, LUT_SIZE, LUT_SIZE, 3), dtype=np.uint8)
    for b in range(LUT_SIZE):
        for g in range(LUT_SIZE):
            for r in range(LUT_SIZE):
                lut[b, g, r] = np.rint(np.array(transform((r/31, g/31, b/31), preset))*255).astype(np.uint8)
    return lut


def apply_lut(image: np.ndarray, lut: np.ndarray) -> np.ndarray:
    coordinates = np.clip(image, 0, 1) * (LUT_SIZE - 1)
    low = np.floor(coordinates).astype(np.int32); high = np.minimum(LUT_SIZE - 1, low + 1); fraction = coordinates - low
    r0, g0, b0 = low[..., 0], low[..., 1], low[..., 2]
    r1, g1, b1 = high[..., 0], high[..., 1], high[..., 2]
    c00 = lut[b0,g0,r0]*(1-fraction[...,0,None]) + lut[b0,g0,r1]*fraction[...,0,None]
    c10 = lut[b0,g1,r0]*(1-fraction[...,0,None]) + lut[b0,g1,r1]*fraction[...,0,None]
    c01 = lut[b1,g0,r0]*(1-fraction[...,0,None]) + lut[b1,g0,r1]*fraction[...,0,None]
    c11 = lut[b1,g1,r0]*(1-fraction[...,0,None]) + lut[b1,g1,r1]*fraction[...,0,None]
    c0 = c00*(1-fraction[...,1,None]) + c10*fraction[...,1,None]
    c1 = c01*(1-fraction[...,1,None]) + c11*fraction[...,1,None]
    return (c0*(1-fraction[...,2,None]) + c1*fraction[...,2,None]) / 255


MATRICES = {
    "silver_push": [.36,.72,.12,-22, .36,.72,.12,-22, .36,.72,.12,-22],
    "noir_halide": [.44,.87,.14,-48, .44,.87,.14,-48, .44,.87,.14,-48],
}
LOOKS = {
    "no_filter": (1,0,0),
    "silver_push": (1,.58,0), "noir_halide": (1,.76,0),
}


def matrix_lut(matrix: list[float] | None) -> np.ndarray:
    lut = np.empty((32,32,32,3), dtype=np.uint8)
    for b in range(32):
        for g in range(32):
            for r in range(32):
                rgb = np.array([r,g,b], dtype=np.float32)/31
                if matrix:
                    rgb = np.array([sum(matrix[row*4+i]*rgb[i] for i in range(3))+matrix[row*4+3]/255 for row in range(3)])
                lut[b,g,r] = np.rint(np.clip(rgb,0,1)*255).astype(np.uint8)
    return lut


def save_assets(preset_id: str, lut: np.ndarray, source: np.ndarray, intensity=1.0, grain=0.0, halation=0.0) -> None:
    (LUT_DIR / f"{preset_id}.rgb").write_bytes(lut.tobytes())
    filtered = apply_lut(source, lut)
    output = source + (filtered-source)*intensity
    if halation:
        luma = output @ np.array([.2126,.7152,.0722])
        glow = np.clip((luma-.72)/.28,0,1)*halation*.24
        output += glow[...,None]*np.array([1,72/255,-32/255])
    if grain:
        rng = np.random.default_rng(zlib.crc32(preset_id.encode("utf-8")))
        output += rng.uniform(-1,1,output.shape[:2])[...,None]*grain*(22/255)
    Image.fromarray(np.rint(np.clip(output,0,1)*255).astype(np.uint8)).save(
        PREVIEW_DIR / f"{preset_id}.webp", "WEBP", quality=90, method=6
    )


def main() -> None:
    LUT_DIR.mkdir(parents=True, exist_ok=True); PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    source = np.asarray(Image.open(ANDROID / "res/drawable-nodpi/filter_sample.jpg").convert("RGB"), dtype=np.float32)/255
    for preset_id, controls in LOOKS.items():
        save_assets(preset_id, matrix_lut(MATRICES.get(preset_id)), source, *controls)
    directories = {"sodium": "sodium", "urban": "urban", "afterglow": "afterglow", "wanderlight": "wanderlight"}
    for folder, prefix in directories.items():
        for xmp in sorted((ANDROID / "assets/presets" / folder).glob("*.xmp")):
            stem = xmp.stem.removeprefix("GX-02_") if folder == "sodium" else xmp.stem
            preset_id = f"{prefix}_{stem}"
            preset = parse_xmp(xmp)
            lut = build_lut(preset)
            save_assets(preset_id, lut, source, grain=clamp(preset["grain"],0,100)/100*.35)
            print(preset_id)


if __name__ == "__main__":
    main()

/**
 * hooru LUT & XMP Parser / HALD-8 Generator Engine
 */

// Generate 512x512 Canvas populated with neutral HALD-8 image data
export function createNeutralHald8Canvas() {
  const size = 512; // HALD-8 is 512x512 pixels (64^3 RGB grid)
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  const imgData = ctx.createImageData(size, size);
  const data = imgData.data;

  // HALD 8 grid (64x64x64 = 262,144 points)
  // Layout: 8x8 blocks of 64x64 pixels
  for (let b = 0; b < 64; b++) {
    const blockX = (b % 8) * 64;
    const blockY = Math.floor(b / 8) * 64;

    for (let g = 0; g < 64; g++) {
      for (let r = 0; r < 64; r++) {
        const x = blockX + r;
        const y = blockY + g;
        const index = (y * size + x) * 4;

        data[index + 0] = Math.round((r / 63) * 255); // Red
        data[index + 1] = Math.round((g / 63) * 255); // Green
        data[index + 2] = Math.round((b / 63) * 255); // Blue
        data[index + 3] = 255;                       // Alpha
      }
    }
  }

  ctx.putImageData(imgData, 0, 0);
  return canvas;
}

// Convert RGB (0..1) to HSL
function rgbToHsl(r, g, b) {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let h, s, l = (max + min) / 2;

  if (max === min) {
    h = s = 0; // achromatic
  } else {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = (g - b) / d + (g < b ? 6 : 0); break;
      case g: h = (b - r) / d + 2; break;
      case b: h = (r - g) / d + 4; break;
    }
    h /= 6;
  }
  return [h, s, l];
}

// Convert HSL (0..1) to RGB (0..1)
function hslToRgb(h, s, l) {
  let r, g, b;

  if (s === 0) {
    r = g = b = l; // achromatic
  } else {
    const hue2rgb = (p, q, t) => {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1/6) return p + (q - p) * 6 * t;
      if (t < 1/2) return q;
      if (t < 2/3) return p + (q - p) * (2/3 - t) * 6;
      return p;
    };

    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    r = hue2rgb(p, q, h + 1/3);
    g = hue2rgb(p, q, h);
    b = hue2rgb(p, q, h - 1/3);
  }
  return [r, g, b];
}

function kelvinRgb(kelvin) {
  const t = Math.max(2000, Math.min(50000, kelvin)) / 100;
  const red = t <= 66 ? 255 : 329.69873 * Math.pow(t - 60, -0.13320476);
  const green = t <= 66 ? 99.4708 * Math.log(t) - 161.11957 : 288.12216 * Math.pow(t - 60, -0.07551485);
  const blue = t >= 66 ? 255 : t <= 19 ? 0 : 138.51773 * Math.log(t - 10) - 305.0448;
  return [red, green, blue].map(v => Math.max(0, Math.min(255, v)));
}

function evaluateCurve(points, value) {
  if (!points?.length) return Math.max(0, Math.min(1, value));
  if (value <= points[0][0]) return points[0][1];
  if (value >= points.at(-1)[0]) return points.at(-1)[1];
  const upper = points.findIndex(point => point[0] >= value);
  const a = points[upper - 1], b = points[upper];
  let t = (value - a[0]) / Math.max(0.0001, b[0] - a[0]);
  t = t * t * (3 - 2 * t);
  return Math.max(0, Math.min(1, a[1] + (b[1] - a[1]) * t));
}

// Apply XMP Camera Raw adjustments to an RGB pixel
export function transformPixelWithXmp(r, g, b, params) {
  let rf = r / 255.0;
  let gf = g / 255.0;
  let bf = b / 255.0;

  // White balance. Lightroom may store relative preset values or an absolute Kelvin value.
  if (params.temperature || params.tint) {
    const kelvin = Math.abs(params.temperature || 0) > 1000 ? params.temperature : 6500 + (params.temperature || 0) * 45;
    const target = kelvinRgb(kelvin), neutral = kelvinRgb(6500);
    const base = target.map((v, i) => v / neutral[i]);
    const norm = Math.max(0.001, base[1]);
    const tint = Math.max(-1, Math.min(1, (params.tint || 0) / 150));
    rf *= base[0] / norm * (1 + tint * 0.08);
    gf *= 1 - tint * 0.12;
    bf *= base[2] / norm * (1 + tint * 0.06);
  }

  // 1. Exposure (EV: -5 to +5)
  if (params.exposure !== 0) {
    const expFactor = Math.pow(2, params.exposure);
    rf *= expFactor;
    gf *= expFactor;
    bf *= expFactor;
  }

  // 2. Contrast (-100 to +100)
  if (params.contrast !== 0) {
    const cFactor = (100 + params.contrast) / 100;
    rf = (rf - 0.5) * cFactor + 0.5;
    gf = (gf - 0.5) * cFactor + 0.5;
    bf = (bf - 0.5) * cFactor + 0.5;
  }

  // 3. Highlights & Shadows, Whites & Blacks
  if (params.highlights !== 0 || params.shadows !== 0 || params.whites !== 0 || params.blacks !== 0) {
    const luminance = 0.2126 * rf + 0.7152 * gf + 0.0722 * bf;
    
    // Highlights affect bright areas (luminance > 0.5)
    if (params.highlights !== 0) {
      const hWeight = Math.max(0, (luminance - 0.4) * 1.6);
      const hDelta = (params.highlights / 100) * 0.25 * hWeight;
      rf += hDelta; gf += hDelta; bf += hDelta;
    }
    
    // Shadows affect dark areas (luminance < 0.5)
    if (params.shadows !== 0) {
      const sWeight = Math.max(0, (0.6 - luminance) * 1.6);
      const sDelta = (params.shadows / 100) * 0.25 * sWeight;
      rf += sDelta; gf += sDelta; bf += sDelta;
    }

    // Whites affect extreme highlights
    if (params.whites !== 0) {
      const wWeight = Math.pow(luminance, 2);
      const wDelta = (params.whites / 100) * 0.2 * wWeight;
      rf += wDelta; gf += wDelta; bf += wDelta;
    }

    // Blacks affect extreme darks
    if (params.blacks !== 0) {
      const bWeight = Math.pow(1.0 - luminance, 2);
      const bDelta = (params.blacks / 100) * 0.2 * bWeight;
      rf += bDelta; gf += bDelta; bf += bDelta;
    }
  }

  // Clamp RGB before HSL
  rf = Math.min(1.0, Math.max(0.0, rf));
  gf = Math.min(1.0, Math.max(0.0, gf));
  bf = Math.min(1.0, Math.max(0.0, bf));

  // 4. HSL Adjustments
  let [h, s, l] = rgbToHsl(rf, gf, bf);

  // Vibrance protects already saturated colors more than global saturation.
  if (params.vibrance) {
    s *= 1 + (params.vibrance / 100) * (1 - s);
  }

  // Saturation (-100 to +100)
  if (params.saturation !== 0) {
    s *= (100 + params.saturation) / 100;
  }

  // HSL Target Channel Colors (Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta)
  const hueChannels = [
    { name: 'Red', hueCenter: 0 / 360, range: 30 / 360 },
    { name: 'Orange', hueCenter: 30 / 360, range: 30 / 360 },
    { name: 'Yellow', hueCenter: 60 / 360, range: 30 / 360 },
    { name: 'Green', hueCenter: 120 / 360, range: 45 / 360 },
    { name: 'Aqua', hueCenter: 180 / 360, range: 30 / 360 },
    { name: 'Blue', hueCenter: 240 / 360, range: 40 / 360 },
    { name: 'Purple', hueCenter: 280 / 360, range: 30 / 360 },
    { name: 'Magenta', hueCenter: 320 / 360, range: 30 / 360 }
  ];

  hueChannels.forEach(ch => {
    const hueAdj = params.hslHue[ch.name] || 0;
    const satAdj = params.hslSat[ch.name] || 0;
    const lumAdj = params.hslLum[ch.name] || 0;

    if (hueAdj !== 0 || satAdj !== 0 || lumAdj !== 0) {
      let diff = Math.abs(h - ch.hueCenter);
      if (diff > 0.5) diff = 1.0 - diff;

      if (diff < ch.range) {
        const weight = Math.cos((diff / ch.range) * (Math.PI / 2));
        h += (hueAdj / 360) * 0.2 * weight;
        s += (satAdj / 100) * 0.5 * weight * s;
        l += (lumAdj / 100) * 0.3 * weight * l;
      }
    }
  });

  // Clamp HSL
  h = (h + 1.0) % 1.0;
  s = Math.min(1.0, Math.max(0.0, s));
  l = Math.min(1.0, Math.max(0.0, l));

  [rf, gf, bf] = hslToRgb(h, s, l);

  // Split Toning / Color Grading (Shadows & Highlights Tint)
  if (params.splitShadowHue !== undefined && params.splitShadowSat > 0) {
    const shadowWeight = Math.pow(1.0 - l, 2);
    const [sr, sg, sb] = hslToRgb(params.splitShadowHue / 360, params.splitShadowSat / 100, 0.5);
    rf = rf * (1 - shadowWeight * 0.5) + sr * shadowWeight * 0.5;
    gf = gf * (1 - shadowWeight * 0.5) + sg * shadowWeight * 0.5;
    bf = bf * (1 - shadowWeight * 0.5) + sb * shadowWeight * 0.5;
  }

  if (params.splitHighlightHue !== undefined && params.splitHighlightSat > 0) {
    const highlightWeight = Math.pow(l, 2);
    const [hr, hg, hb] = hslToRgb(params.splitHighlightHue / 360, params.splitHighlightSat / 100, 0.5);
    rf = rf * (1 - highlightWeight * 0.5) + hr * highlightWeight * 0.5;
    gf = gf * (1 - highlightWeight * 0.5) + hg * highlightWeight * 0.5;
    bf = bf * (1 - highlightWeight * 0.5) + hb * highlightWeight * 0.5;
  }

  // Current Lightroom color grading midtones (legacy split toning is mapped above).
  if (params.colorGradeMidtoneSat > 0) {
    const midWeight = Math.max(0, 1 - Math.abs(l - 0.5) * 2);
    const [mr, mg, mb] = hslToRgb(params.colorGradeMidtoneHue / 360, params.colorGradeMidtoneSat / 100, 0.5);
    const amount = midWeight * 0.45;
    rf = rf * (1 - amount) + mr * amount;
    gf = gf * (1 - amount) + mg * amount;
    bf = bf * (1 - amount) + mb * amount;
  }

  // Dehaze is baked into the LUT; clarity remains a spatial Android effect.
  const localContrast = ((params.dehaze || 0) * 0.35 + (params.clarity || 0) * 0.18) / 100;
  rf = (rf - 0.5) * (1 + localContrast) + 0.5;
  gf = (gf - 0.5) * (1 + localContrast) + 0.5;
  bf = (bf - 0.5) * (1 + localContrast) + 0.5;

  rf = evaluateCurve(params.redCurve, evaluateCurve(params.toneCurve, rf));
  gf = evaluateCurve(params.greenCurve, evaluateCurve(params.toneCurve, gf));
  bf = evaluateCurve(params.blueCurve, evaluateCurve(params.toneCurve, bf));

  return [
    Math.round(Math.min(255, Math.max(0, rf * 255))),
    Math.round(Math.min(255, Math.max(0, gf * 255))),
    Math.round(Math.min(255, Math.max(0, bf * 255)))
  ];
}

// Parse XMP Text content
export function parseXmpContent(xmpString) {
  const getAttr = (name, def = 0) => {
    const reg = new RegExp(`crs:${name}="([^"]+)"`, 'i');
    const match = xmpString.match(reg);
    if (match) return parseFloat(match[1]);

    const tagReg = new RegExp(`<crs:${name}>([^<]+)</crs:${name}>`, 'i');
    const tagMatch = xmpString.match(tagReg);
    if (tagMatch) return parseFloat(tagMatch[1]);

    return def;
  };

  const params = {
    exposure: getAttr('Exposure2012', 0),
    contrast: getAttr('Contrast2012', 0),
    highlights: getAttr('Highlights2012', 0),
    shadows: getAttr('Shadows2012', 0),
    whites: getAttr('Whites2012', 0),
    blacks: getAttr('Blacks2012', 0),
    saturation: getAttr('Saturation', 0),
    temperature: getAttr('IncrementalTemperature', getAttr('Temperature', 0)),
    tint: getAttr('IncrementalTint', getAttr('Tint', 0)),
    vibrance: getAttr('Vibrance', 0),
    clarity: getAttr('Clarity2012', getAttr('Clarity', 0)),
    dehaze: getAttr('Dehaze', 0),
    grain: getAttr('GrainAmount', 0),
    grainSize: getAttr('GrainSize', 25),
    grainRoughness: getAttr('GrainFrequency', 50),
    sharpness: getAttr('Sharpness', 0),
    sharpenRadius: getAttr('SharpenRadius', 1),
    sharpenDetail: getAttr('SharpenDetail', 25),
    sharpenMasking: getAttr('SharpenEdgeMasking', 0),
    luminanceNoiseReduction: getAttr('LuminanceSmoothing', 0),
    colorNoiseReduction: getAttr('ColorNoiseReduction', 0),
    splitShadowHue: getAttr('SplitToningShadowHue', 0),
    splitShadowSat: getAttr('SplitToningShadowSaturation', 0),
    splitHighlightHue: getAttr('SplitToningHighlightHue', 0),
    splitHighlightSat: getAttr('SplitToningHighlightSaturation', 0),
    colorGradeMidtoneHue: getAttr('ColorGradeMidtoneHue', 0),
    colorGradeMidtoneSat: getAttr('ColorGradeMidtoneSat', 0),
    colorGradeBlending: getAttr('ColorGradeBlending', 50),
    colorGradeBalance: getAttr('ColorGradeGlobalLum', getAttr('SplitToningBalance', 0)),
    hslHue: {},
    hslSat: {},
    hslLum: {}
  };

  const getCurve = (name) => {
    const body = xmpString.match(new RegExp(`<crs:${name}>[\\s\\S]*?<rdf:Seq>([\\s\\S]*?)<\\/rdf:Seq>[\\s\\S]*?<\\/crs:${name}>`, 'i'))?.[1];
    if (!body) return [];
    return [...body.matchAll(/<rdf:li>\s*([+-]?[\d.]+)\s*,\s*([+-]?[\d.]+)\s*<\/rdf:li>/gi)]
      .map(match => [Number(match[1]) / 255, Number(match[2]) / 255]);
  };
  params.toneCurve = getCurve('ToneCurvePV2012');
  params.redCurve = getCurve('ToneCurvePV2012Red');
  params.greenCurve = getCurve('ToneCurvePV2012Green');
  params.blueCurve = getCurve('ToneCurvePV2012Blue');

  params.splitShadowHue = getAttr('ColorGradeShadowHue', params.splitShadowHue);
  params.splitShadowSat = getAttr('ColorGradeShadowSat', params.splitShadowSat);
  params.splitHighlightHue = getAttr('ColorGradeHighlightHue', params.splitHighlightHue);
  params.splitHighlightSat = getAttr('ColorGradeHighlightSat', params.splitHighlightSat);

  ['Red', 'Orange', 'Yellow', 'Green', 'Aqua', 'Blue', 'Purple', 'Magenta'].forEach(color => {
    params.hslHue[color] = getAttr(`HueAdjustment${color}`, 0);
    params.hslSat[color] = getAttr(`SaturationAdjustment${color}`, 0);
    params.hslLum[color] = getAttr(`LuminanceAdjustment${color}`, 0);
  });

  return params;
}

// Parse 3D .cube LUT file
export function parseCubeContent(cubeString) {
  const lines = cubeString.split('\n');
  let size = 33;
  const data = [];

  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith('#')) continue;

    if (line.startsWith('LUT_3D_SIZE')) {
      const parts = line.split(/\s+/);
      size = parseInt(parts[1], 10);
      continue;
    }

    if (line.startsWith('TITLE') || line.startsWith('DOMAIN_MIN') || line.startsWith('DOMAIN_MAX')) {
      continue;
    }

    const parts = line.split(/\s+/).map(Number);
    if (parts.length >= 3 && !isNaN(parts[0])) {
      data.push(parts.slice(0, 3));
    }
  }

  return { size, data };
}

// Convert 3D Cube Data to 512x512 HALD-8 Canvas
export function cubeToHald8Canvas(cubeData) {
  const { size, data } = cubeData;
  const canvas = createNeutralHald8Canvas();
  const ctx = canvas.getContext('2d');
  const imgData = ctx.getImageData(0, 0, 512, 512);
  const pixels = imgData.data;

  // Trilinear interpolation helper over the input cube grid
  const getCubeColor = (r, g, b) => {
    const rx = r * (size - 1);
    const gy = g * (size - 1);
    const bz = b * (size - 1);

    const x0 = Math.floor(rx); const x1 = Math.min(size - 1, x0 + 1);
    const y0 = Math.floor(gy); const y1 = Math.min(size - 1, y0 + 1);
    const z0 = Math.floor(bz); const z1 = Math.min(size - 1, z0 + 1);

    const dx = rx - x0;
    const dy = gy - y0;
    const dz = bz - z0;

    const idx = (x, y, z) => (z * size * size + y * size + x);

    const c000 = data[idx(x0, y0, z0)] || [r, g, b];
    const c100 = data[idx(x1, y0, z0)] || [r, g, b];
    const c010 = data[idx(x0, y1, z0)] || [r, g, b];
    const c110 = data[idx(x1, y1, z0)] || [r, g, b];
    const c001 = data[idx(x0, y0, z1)] || [r, g, b];
    const c101 = data[idx(x1, y0, z1)] || [r, g, b];
    const c011 = data[idx(x0, y1, z1)] || [r, g, b];
    const c111 = data[idx(x1, y1, z1)] || [r, g, b];

    const interpChannel = (ch) => {
      const c00 = c000[ch] * (1 - dx) + c100[ch] * dx;
      const c10 = c010[ch] * (1 - dx) + c110[ch] * dx;
      const c01 = c001[ch] * (1 - dx) + c101[ch] * dx;
      const c11 = c011[ch] * (1 - dx) + c111[ch] * dx;
      const c0 = c00 * (1 - dy) + c10 * dy;
      const c1 = c01 * (1 - dy) + c11 * dy;
      return c0 * (1 - dz) + c1 * dz;
    };

    return [
      Math.round(Math.min(255, Math.max(0, interpChannel(0) * 255))),
      Math.round(Math.min(255, Math.max(0, interpChannel(1) * 255))),
      Math.round(Math.min(255, Math.max(0, interpChannel(2) * 255)))
    ];
  };

  for (let b = 0; b < 64; b++) {
    const blockX = (b % 8) * 64;
    const blockY = Math.floor(b / 8) * 64;

    for (let g = 0; g < 64; g++) {
      for (let r = 0; r < 64; r++) {
        const x = blockX + r;
        const y = blockY + g;
        const index = (y * 512 + x) * 4;

        const normR = r / 63;
        const normG = g / 63;
        const normB = b / 63;

        const [outR, outG, outB] = getCubeColor(normR, normG, normB);
        pixels[index + 0] = outR;
        pixels[index + 1] = outG;
        pixels[index + 2] = outB;
      }
    }
  }

  ctx.putImageData(imgData, 0, 0);
  return canvas;
}

// Transform neutral HALD-8 Canvas with XMP parameters
export function xmpToHald8Canvas(xmpParams) {
  const canvas = createNeutralHald8Canvas();
  const ctx = canvas.getContext('2d');
  const imgData = ctx.getImageData(0, 0, 512, 512);
  const pixels = imgData.data;

  for (let i = 0; i < pixels.length; i += 4) {
    const r = pixels[i + 0];
    const g = pixels[i + 1];
    const b = pixels[i + 2];

    const [outR, outG, outB] = transformPixelWithXmp(r, g, b, xmpParams);
    pixels[i + 0] = outR;
    pixels[i + 1] = outG;
    pixels[i + 2] = outB;
  }

  ctx.putImageData(imgData, 0, 0);
  return canvas;
}

// Generate raw binary .lut (64x64x64 RGBA byte array for OpenGL 3D texture)
export function canvasToBinaryLut(haldCanvas) {
  const ctx = haldCanvas.getContext('2d');
  const imgData = ctx.getImageData(0, 0, 512, 512);
  const pixels = imgData.data;

  // 64 * 64 * 64 * 3 bytes (R, G, B for each 3D point)
  const buffer = new Uint8Array(64 * 64 * 64 * 3);
  let writeIdx = 0;

  for (let b = 0; b < 64; b++) {
    const blockX = (b % 8) * 64;
    const blockY = Math.floor(b / 8) * 64;

    for (let g = 0; g < 64; g++) {
      for (let r = 0; r < 64; r++) {
        const x = blockX + r;
        const y = blockY + g;
        const readIdx = (y * 512 + x) * 4;

        buffer[writeIdx++] = pixels[readIdx + 0];
        buffer[writeIdx++] = pixels[readIdx + 1];
        buffer[writeIdx++] = pixels[readIdx + 2];
      }
    }
  }

  return buffer;
}

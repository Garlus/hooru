import React, { useState, useEffect, useRef } from 'react';
import { Upload, Download, Sliders, Image, Sparkles, CheckCircle, Info } from 'lucide-react';
import {
  createNeutralHald8Canvas,
  parseXmpContent,
  parseCubeContent,
  xmpToHald8Canvas,
  cubeToHald8Canvas,
  canvasToBinaryLut,
  transformPixelWithXmp
} from './lutParser.js';

const SAMPLE_PRESETS = [
  {
    name: 'Leica Monochrome',
    type: 'xmp',
    params: {
      exposure: 0.15, contrast: 25, highlights: -15, shadows: 10, whites: 10, blacks: -15, saturation: -100,
      hslHue: {}, hslSat: {}, hslLum: {}
    }
  },
  {
    name: 'Teal & Orange',
    type: 'xmp',
    params: {
      exposure: 0.05, contrast: 15, highlights: -20, shadows: 15, whites: 5, blacks: -10, saturation: 10,
      splitShadowHue: 200, splitShadowSat: 35, splitHighlightHue: 35, splitHighlightSat: 40,
      hslHue: { Blue: -15, Orange: 10 }, hslSat: { Blue: 20, Orange: 25 }, hslLum: {}
    }
  },
  {
    name: 'Kodak Portra 400',
    type: 'xmp',
    params: {
      exposure: 0.2, contrast: -10, highlights: -25, shadows: 20, whites: -5, blacks: 15, saturation: 8,
      splitShadowHue: 40, splitShadowSat: 12, splitHighlightHue: 60, splitHighlightSat: 10,
      hslHue: { Yellow: 5, Red: 5 }, hslSat: { Green: -20, Blue: -15 }, hslLum: { Orange: 10 }
    }
  },
  {
    name: 'Fuji Classic Chrome',
    type: 'xmp',
    params: {
      exposure: 0.0, contrast: 20, highlights: -30, shadows: 5, whites: -10, blacks: -5, saturation: -25,
      splitShadowHue: 210, splitShadowSat: 15, splitHighlightHue: 45, splitHighlightSat: 15,
      hslHue: { Red: -5, Green: -15 }, hslSat: { Blue: -30, Red: 10 }, hslLum: {}
    }
  }
];

export default function App() {
  const [selectedPresetName, setSelectedPresetName] = useState('Leica Monochrome');
  const [activeParams, setActiveParams] = useState(SAMPLE_PRESETS[0].params);
  const [activeCube, setActiveCube] = useState(null);
  const [activeMode, setActiveMode] = useState('sample'); // 'sample' | 'custom_xmp' | 'custom_cube'

  const haldCanvasRef = useRef(null);
  const sampleCanvasRef = useRef(null);

  // Render neutral sample test pattern
  const drawSampleImage = (targetCanvas, applyLutFn) => {
    const ctx = targetCanvas.getContext('2d');
    const width = targetCanvas.width;
    const height = targetCanvas.height;

    // Create a vibrant test scene (sunset landscape gradient + color spheres)
    const imgData = ctx.createImageData(width, height);
    const data = imgData.data;

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const ny = y / height;
        const nx = x / width;

        // Base gradient
        let r = Math.floor(255 * (1 - ny * 0.5));
        let g = Math.floor(180 * (1 - ny));
        let b = Math.floor(120 * ny);

        // Circular sphere test pattern
        const cx = 0.5, cy = 0.5;
        const dist = Math.sqrt((nx - cx) ** 2 + (ny - cy) ** 2);
        if (dist < 0.25) {
          const factor = Math.cos((dist / 0.25) * (Math.PI / 2));
          r = Math.floor(r * (1 - factor) + 240 * factor);
          g = Math.floor(g * (1 - factor) + 80 * factor);
          b = Math.floor(b * (1 - factor) + 120 * factor);
        }

        // Apply LUT transformation function if available
        if (applyLutFn) {
          [r, g, b] = applyLutFn(r, g, b);
        }

        const idx = (y * width + x) * 4;
        data[idx + 0] = r;
        data[idx + 1] = g;
        data[idx + 2] = b;
        data[idx + 3] = 255;
      }
    }
    ctx.putImageData(imgData, 0, 0);
  };

  // Re-generate HALD-8 canvas and Sample preview when params change
  useEffect(() => {
    if (!haldCanvasRef.current || !sampleCanvasRef.current) return;

    let haldCanvas;
    let applyFn;

    if (activeMode === 'custom_cube' && activeCube) {
      haldCanvas = cubeToHald8Canvas(activeCube);
      applyFn = (r, g, b) => {
        // Simplified preview approximation for cube
        return [r, g, b];
      };
    } else {
      haldCanvas = xmpToHald8Canvas(activeParams);
      applyFn = (r, g, b) => transformPixelWithXmp(r, g, b, activeParams);
    }

    // Draw HALD-8 onto visible canvas
    const destCtx = haldCanvasRef.current.getContext('2d');
    destCtx.drawImage(haldCanvas, 0, 0, 160, 160);

    // Draw test sample preview image
    drawSampleImage(sampleCanvasRef.current, applyFn);
  }, [activeParams, activeCube, activeMode]);

  // File Drop / Selection handler
  const handleFileUpload = (file) => {
    if (!file) return;

    const fileName = file.name.toLowerCase();
    const reader = new FileReader();

    if (fileName.endsWith('.xmp')) {
      reader.onload = (e) => {
        const text = e.target.result;
        const parsed = parseXmpContent(text);
        setActiveParams(parsed);
        setActiveCube(null);
        setActiveMode('custom_xmp');
        setSelectedPresetName(file.name.replace('.xmp', ''));
      };
      reader.readAsText(file);
    } else if (fileName.endsWith('.cube')) {
      reader.onload = (e) => {
        const text = e.target.result;
        const parsedCube = parseCubeContent(text);
        setActiveCube(parsedCube);
        setActiveMode('custom_cube');
        setSelectedPresetName(file.name.replace('.cube', ''));
      };
      reader.readAsText(file);
    } else {
      alert('Please select a valid Lightroom .xmp or 3D .cube file!');
    }
  };

  // Export HALD-8 PNG
  const downloadPng = () => {
    let canvas;
    if (activeMode === 'custom_cube' && activeCube) {
      canvas = cubeToHald8Canvas(activeCube);
    } else {
      canvas = xmpToHald8Canvas(activeParams);
    }

    const link = document.createElement('a');
    link.download = `${selectedPresetName.replace(/\s+/g, '_')}_HALD8.png`;
    link.href = canvas.toDataURL('image/png');
    link.click();
  };

  // Export App Binary .lut
  const downloadBinaryLut = () => {
    let canvas;
    if (activeMode === 'custom_cube' && activeCube) {
      canvas = cubeToHald8Canvas(activeCube);
    } else {
      canvas = xmpToHald8Canvas(activeParams);
    }

    const binaryData = canvasToBinaryLut(canvas);
    const blob = new Blob([binaryData], { type: 'application/octet-stream' });
    const link = document.createElement('a');
    link.download = `${selectedPresetName.replace(/\s+/g, '_')}.lut`;
    link.href = URL.createObjectURL(blob);
    link.click();
  };

  const parameterRows = [
    ['Belichtung', activeParams.exposure], ['Kontrast', activeParams.contrast],
    ['Lichter', activeParams.highlights], ['Tiefen', activeParams.shadows],
    ['Weiß', activeParams.whites], ['Schwarz', activeParams.blacks],
    ['Temperatur', activeParams.temperature], ['Tönung', activeParams.tint],
    ['Dynamik', activeParams.vibrance], ['Sättigung', activeParams.saturation],
    ['Klarheit', activeParams.clarity], ['Dunst entfernen', activeParams.dehaze],
    ['Körnung', activeParams.grain], ['Körnungsgröße', activeParams.grainSize],
    ['Schärfung', activeParams.sharpness], ['Luminanz-Rauschen', activeParams.luminanceNoiseReduction],
    ['Farbrauschen', activeParams.colorNoiseReduction]
  ];

  return (
    <div className="container">
      <header className="header">
        <div className="brand-badge">
          <Sparkles size={14} /> HOORU / XMP ENGINE 02
        </div>
        <h1 className="title">LIGHTROOM → HOORU</h1>
        <p className="subtitle">
          XMP-Presets werden in das gemeinsame Farbmodell für Kamera-Preview und Fotoentwicklung übersetzt.
        </p>
      </header>

      <div className="grid-layout">
        {/* Left Column: Preset Loader & Dropzone */}
        <div className="card">
          <h2 className="card-title">
            <Sliders size={20} color="#ff3b30" /> Input Preset / LUT
          </h2>

          <div
            className="dropzone"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              if (e.dataTransfer.files && e.dataTransfer.files[0]) {
                handleFileUpload(e.dataTransfer.files[0]);
              }
            }}
            onClick={() => {
              const input = document.createElement('input');
              input.type = 'file';
              input.accept = '.xmp,.cube';
              input.onchange = (e) => handleFileUpload(e.target.files[0]);
              input.click();
            }}
          >
            <Upload className="dropzone-icon" />
            <p style={{ margin: '0 0 0.5rem 0', fontWeight: 600 }}>Drop .xmp or .cube file here</p>
            <p style={{ margin: 0, fontSize: '0.85rem', color: '#94a3b8' }}>
              Supports Adobe Lightroom presets and 3D Color LUTs
            </p>
          </div>

          <div style={{ marginTop: '1.75rem' }}>
            <p style={{ fontSize: '0.85rem', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Or choose sample preset
            </p>

            <div className="preset-presets-grid">
              {SAMPLE_PRESETS.map((p) => (
                <div
                  key={p.name}
                  className={`preset-chip ${selectedPresetName === p.name ? 'active' : ''}`}
                  onClick={() => {
                    setSelectedPresetName(p.name);
                    setActiveParams(p.params);
                    setActiveCube(null);
                    setActiveMode('sample');
                  }}
                >
                  <span>{p.name}</span>
                  {selectedPresetName === p.name && <CheckCircle size={16} color="#60a5fa" />}
                </div>
              ))}
            </div>
          </div>
          {activeMode !== 'custom_cube' && <div className="parameter-panel">
            <div className="parameter-heading"><span>ENTWICKLUNG</span><span>WERT</span></div>
            {parameterRows.map(([label, value]) => <div className="parameter-row" key={label}>
              <span>{label}</span><span>{Number(value || 0).toFixed(1)}</span>
            </div>)}
            <div className="parameter-row"><span>Farbmischer</span><span>8 KANÄLE</span></div>
            <div className="parameter-row"><span>Colorgrading</span><span>S / M / H</span></div>
            <div className="parameter-row"><span>Gradationskurven</span><span>{activeParams.toneCurve?.length || 0} + RGB</span></div>
          </div>}
        </div>

        {/* Right Column: Instant HALD-8 Preview & Export */}
        <div className="card">
          <h2 className="card-title">
            <Image size={20} color="#3b82f6" /> Live Preview & Export
          </h2>

          <div className="canvas-preview-container">
            {/* Live Sample Render Canvas */}
            <div className="preview-box">
              <canvas ref={sampleCanvasRef} width={400} height={300} className="preview-canvas" />
            </div>

            {/* HALD-8 512x512 Image Map */}
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', width: '100%', background: '#0f111a', padding: '0.75rem', borderRadius: '10px' }}>
              <canvas ref={haldCanvasRef} width={160} height={160} className="hald-display-canvas" />
              <div>
                <p style={{ margin: '0 0 0.25rem 0', fontWeight: 600, fontSize: '0.9rem' }}>
                  Generated HALD-8 (512x512)
                </p>
                <p style={{ margin: 0, fontSize: '0.8rem', color: '#94a3b8', fontFamily: 'Space Mono' }}>
                  Active Preset: <span style={{ color: '#ff3b30' }}>{selectedPresetName}</span>
                </p>
              </div>
            </div>

            {/* Download Buttons */}
            <div className="btn-group">
              <button className="btn btn-primary" onClick={downloadPng}>
                <Download size={18} /> Export HALD-8 PNG
              </button>
              <button className="btn btn-secondary" onClick={downloadBinaryLut}>
                <Download size={18} /> Export .lut Binary
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Footer Info Banner */}
      <div className="info-banner">
        <Info className="info-icon" />
        <div className="info-text">
          <p>Workflow auf dem Google Pixel:</p>
          <ul>
            <li>XMP direkt in der App importieren oder hier als HALD-8 bzw. App-Preset exportieren.</li>
            <li>Preview und JPEG verwenden dieselbe 64³-Farbtabelle; Körnung und lokale Klarheit laufen separat.</li>
            <li>RAW/DNG bleibt absichtlich unverändert und kann das originale XMP später in Lightroom erhalten.</li>
          </ul>
        </div>
      </div>
    </div>
  );
}

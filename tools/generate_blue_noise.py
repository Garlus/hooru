#!/usr/bin/env python3
"""Generate Hooru's deterministic, seamlessly tiling blue-noise grain texture."""

from pathlib import Path

import numpy as np
from PIL import Image


SIZE = 128
SEED = 0x484F4F52  # "HOOR"


def main() -> None:
    rng = np.random.default_rng(SEED)
    white = rng.standard_normal((SIZE, SIZE))

    # FFT filtering is periodic by construction, so the resulting texture tiles
    # without a seam. Weighting by radial frequency removes low-frequency energy
    # (blotches) while retaining fine, film-like particles.
    fy = np.fft.fftfreq(SIZE)[:, None]
    fx = np.fft.fftfreq(SIZE)[None, :]
    radius = np.sqrt(fx * fx + fy * fy)
    spectrum = np.fft.fft2(white) * np.power(radius, 0.85)
    spectrum[0, 0] = 0
    filtered = np.fft.ifft2(spectrum).real

    # Rank mapping produces a uniform 8-bit distribution. The shader can therefore
    # turn one texture lookup directly into unbiased signed grain.
    order = np.argsort(filtered, axis=None)
    ranks = np.empty_like(order)
    ranks[order] = np.arange(order.size)
    pixels = np.rint(ranks.reshape(filtered.shape) * 255 / (order.size - 1)).astype(np.uint8)

    output = Path(__file__).resolve().parents[1] / "android/app/src/main/res/drawable-nodpi/blue_noise_128.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(pixels, mode="L").save(output, optimize=True)
    print(output)


if __name__ == "__main__":
    main()

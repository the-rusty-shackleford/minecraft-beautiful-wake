"""The art, as code: the foam and ring textures.

Run from the repository root:

    uv run --no-project python devtools/art/build.py

Everything it writes lands under src/main/resources/assets/beautifulwake/
and is committed; this script is the source of truth for those files, and
the photo booth (`./gradlew runPhotoBooth`) is how the result is looked at.
Original work throughout.
"""

from __future__ import annotations

import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/beautifulwake"


def write_png(path: Path, width: int, height: int, pixels) -> None:
    """pixels: rows of (r, g, b, a) tuples."""
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


class Noise:
    """A deterministic grain."""

    def __init__(self, seed: int) -> None:
        self.state = seed & 0xFFFFFFFF

    def next(self) -> float:
        self.state = (1664525 * self.state + 1013904223) & 0xFFFFFFFF
        return self.state / 0xFFFFFFFF


def value_noise(size: int, cells: int, seed: int):
    """Smooth periodic value noise on a size x size grid from a cells x cells lattice; tiles both ways."""
    noise = Noise(seed)
    lattice = [[noise.next() for _ in range(cells)] for _ in range(cells)]

    def smooth(t: float) -> float:
        return t * t * (3.0 - 2.0 * t)

    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        fy = y / size * cells
        y0 = int(fy) % cells
        y1 = (y0 + 1) % cells
        ty = smooth(fy - int(fy))
        for x in range(size):
            fx = x / size * cells
            x0 = int(fx) % cells
            x1 = (x0 + 1) % cells
            tx = smooth(fx - int(fx))
            a = lattice[y0][x0] * (1 - tx) + lattice[y0][x1] * tx
            b = lattice[y1][x0] * (1 - tx) + lattice[y1][x1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def foam(size: int = 64):
    """Foam: white, denser down the middle of the strip (u = 0.5) and ragged
    at its edges, streaked along v -- the texture repeats along the trail --
    from two octaves of noise stretched lengthwise. Alpha is the foam's
    coverage; the renderer multiplies it by the wake's own fade."""
    coarse = value_noise(size, 6, 0x0B0A7)
    fine = value_noise(size, 16, 0x5EA)
    # Stretch along v: sample the noise at a fraction of y so streaks run down the strip.
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            u = (x + 0.5) / size
            n = 0.65 * coarse[(y * 2) % size][x] + 0.35 * fine[(y * 3) % size][x]
            across = math.sin(math.pi * u) ** 1.2                     # dense in the middle, ragged at the edges
            cover = max(0.0, min(1.0, (n * 1.6 - 0.35) * across))
            alpha = int(255 * cover ** 0.8)
            tint = 235 + int(20 * n)
            row.append((min(255, tint), min(255, tint + 4), 255, alpha))
        px.append(row)
    return px


def ring(size: int = 64):
    """A splash ring: an annulus with a soft inner and outer edge, brighter
    on its crest, for an entry splash that expands and fades."""
    px = []
    c = (size - 1) / 2.0
    for y in range(size):
        row = []
        for x in range(size):
            r = math.hypot(x - c, y - c) / c                          # 0 at the centre, 1 at the edge
            band = math.exp(-((r - 0.78) / 0.09) ** 2)                # the crest
            inner = 0.35 * math.exp(-((r - 0.55) / 0.12) ** 2)        # a fainter inner ripple
            cover = max(0.0, min(1.0, band + inner)) * (1.0 if r < 0.97 else 0.0)
            row.append((240, 246, 255, int(255 * cover)))
        px.append(row)
    return px


def main(argv) -> int:
    write_png(ASSETS / "textures/foam.png", 64, 64, foam())
    write_png(ASSETS / "textures/ring.png", 64, 64, ring())
    print("textures: foam.png, ring.png (64x64)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

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


def worley(width: int, height: int, cells_x: int, cells_y: int, seed: int):
    """Periodic cellular (Worley) noise on a width x height grid: the distance
    from each pixel to the nearest of cells_x x cells_y jittered points,
    tiling both ways, in [0, 1]."""
    noise = Noise(seed)
    points = [[(noise.next(), noise.next()) for _ in range(cells_x)] for _ in range(cells_y)]
    out = [[0.0] * width for _ in range(height)]
    for y in range(height):
        fy = y / height * cells_y
        for x in range(width):
            fx = x / width * cells_x
            cx, cy = int(fx), int(fy)
            best = 9.0
            for oy in (-1, 0, 1):
                for ox in (-1, 0, 1):
                    gx, gy = (cx + ox) % cells_x, (cy + oy) % cells_y
                    px, py = points[gy][gx]
                    dx = (cx + ox + px) - fx
                    dy = (cy + oy + py) - fy
                    best = min(best, dx * dx + dy * dy)
            out[y][x] = min(1.0, math.sqrt(best))
    return out


def foam(width: int = 64, height: int = 192):
    """Foam the way a cel-shaded sea draws it: bold white cells with crisp
    edges over nothing, dense down the middle of the strip (u = 0.5) and
    breaking into separate blobs toward its edges, stretched along v so the
    cells streak down the trail. Two cell sizes overlaid so no two blobs
    are the same. Alpha is the foam's coverage; the renderer multiplies it
    by the wake's own fade, and the texture repeats along the trail."""
    big = worley(width, height, 4, 7, 0xF0A)
    small = worley(width, height, 9, 17, 0xB1B)
    px = []
    for y in range(height):
        row = []
        for x in range(width):
            u = (x + 0.5) / width
            n = 0.6 * (1.0 - big[y][x]) + 0.4 * (1.0 - small[y][x])   # 1 at a cell's centre, 0 at its edge
            across = math.sin(math.pi * u) ** 0.9
            # A crisp threshold that loosens toward the edges: solid foam in
            # the middle, separate blobs at the sides, nothing beyond.
            level = 0.62 - 0.30 * across
            edge = 0.06
            cover = max(0.0, min(1.0, (n - level) / edge))
            alpha = int(255 * cover)
            row.append((255, 255, 255, alpha))
        px.append(row)
    return px


def ring(size: int = 64):
    """A splash ring: a bold annulus with a crisp crest and a fainter inner
    ripple, for an entry splash that expands and fades."""
    px = []
    c = (size - 1) / 2.0
    for y in range(size):
        row = []
        for x in range(size):
            r = math.hypot(x - c, y - c) / c                          # 0 at the centre, 1 at the edge
            band = math.exp(-((r - 0.78) / 0.07) ** 2)                # the crest
            inner = 0.45 * math.exp(-((r - 0.52) / 0.09) ** 2)        # a fainter inner ripple
            cover = max(0.0, min(1.0, (band + inner) * 1.6)) * (1.0 if r < 0.97 else 0.0)
            row.append((255, 255, 255, int(255 * cover)))
        px.append(row)
    return px


def main(argv) -> int:
    write_png(ASSETS / "textures/foam.png", 64, 192, foam())
    write_png(ASSETS / "textures/ring.png", 64, 64, ring())
    print("textures: foam.png (64x192), ring.png (64x64)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

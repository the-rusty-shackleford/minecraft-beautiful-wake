"""The art, as code: the wake's textures.

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


# The wake's colours: the pale sheet of disturbed water, and the white of
# its lines and foam. The sheet is drawn at less than half alpha, so it
# lightens the water under it rather than covering it; a shader's water
# shows through.
SHEET = (188, 224, 252, 108)
LINE = (255, 255, 255, 236)
CLEAR = (0, 0, 0, 0)

# The skin and line textures map across the V by the distance from its
# edge in blocks: from EDGE_INSIDE (well inside, where the sheet is all
# one thing) to EDGE_OUTSIDE (past the edge, where there is nothing). The
# renderer's WakeMesh.EDGE_INSIDE and WakeRenderer.EDGE_OUTSIDE are these.
EDGE_INSIDE = -3.0
EDGE_OUTSIDE = 0.5
# The chevron lines' texture repeats once per chevron wavelength along v:
# WakeField.CHEVRON_WAVELENGTH, in blocks.
CHEVRON_WAVELENGTH = 3.0


def soft_edge(x: float, width: float) -> float:
    """1 inside a band |x| < width/2, falling to 0 over a pixel and a half either side, in blocks per pixel units the caller picks."""
    return max(0.0, min(1.0, (width / 2.0 - abs(x)) / width * 6.0 + 0.5))


def blocks_across(x_pixel: int, width: int) -> float:
    """The edge coordinate, in blocks, of a pixel column of the skin or line texture."""
    return EDGE_INSIDE + (x_pixel + 0.5) / width * (EDGE_OUTSIDE - EDGE_INSIDE)


def skin(width: int = 256, height: int = 8):
    """The sheet of disturbed water inside the V and the white line on its
    edge: pale blue inside, fullest down the middle and thinner toward the
    arms so the lines carry the V, a crisp white line a tenth of a block
    wide on the edge itself, nothing outside it. The same down every row;
    the renderer runs v along the chevrons for the line texture, and this
    one ignores it."""
    px = []
    row = []
    for x in range(width):
        e = blocks_across(x, width)
        line = soft_edge(e, 0.11)
        if e < 0.0:
            inside = 0.55 + 0.45 * max(0.0, min(1.0, -e / 2.5))     # fuller toward the middle
        else:
            inside = max(0.0, 1.0 - e / 0.06)                        # a crisp cut just past the line
        r = int(SHEET[0] + (LINE[0] - SHEET[0]) * line)
        g = int(SHEET[1] + (LINE[1] - SHEET[1]) * line)
        b = int(SHEET[2] + (LINE[2] - SHEET[2]) * line)
        a = int(SHEET[3] * inside + (LINE[3] - SHEET[3] * inside) * line)
        row.append((r, g, b, max(0, min(255, a))))
    for _ in range(height):
        px.append(list(row))
    return px


def lines(width: int = 256, height: int = 256):
    """The white lines along the chevron ridges: a line at phase zero, a
    fainter one at phase one half, each stopping short of the V's edge so
    it never crosses the edge line; nothing anywhere else."""
    px = []
    for y in range(height):
        phase = (y + 0.5) / height
        along = min(phase, 1.0 - phase) * CHEVRON_WAVELENGTH            # blocks from the nearest whole phase
        strong = soft_edge(along, 0.10)
        faint = 0.3 * soft_edge(phase - 0.5, 0.06 / CHEVRON_WAVELENGTH)
        cover = max(strong, faint)
        row = []
        for x in range(width):
            e = blocks_across(x, width)
            stop = max(0.0, min(1.0, (-e - 0.18) / 0.12))                   # fade out before the edge line
            a = int(LINE[3] * cover * stop)
            row.append((255, 255, 255, a) if a > 0 else CLEAR)
        px.append(row)
    return px


def foam(size: int = 128, count: int = 100, seed: int = 0xF0A):
    """The churn, the way a cel-shaded sea draws it: a cloud of round
    white bubbles, overlapping, with soft rims and a faint blue ring just
    inside each one so they read as bubbles and not as paint; holes
    between them. Tiles both ways over FOAM_TILE blocks."""
    noise = Noise(seed)
    discs = []
    for _ in range(count):
        discs.append((noise.next() * size, noise.next() * size, 4.0 + 9.0 * noise.next() ** 1.5))
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            cover = 0.0
            rim = 0.0
            for cx, cy, r in discs:
                dx = (x + 0.5 - cx + size / 2) % size - size / 2
                dy = (y + 0.5 - cy + size / 2) % size - size / 2
                d = math.hypot(dx, dy)
                c = max(0.0, min(1.0, (r - d) / 1.5))                   # 1 inside, soft over a pixel and a half
                cover = max(cover, c)
                ring = max(0.0, 1.0 - abs(d - (r - 2.2)) / 1.4) * c
                rim = max(rim, ring)
            a = int(255 * cover)
            r_ = int(255 - 45 * rim)
            g_ = int(255 - 20 * rim)
            row.append((r_, g_, 255, a) if a > 0 else CLEAR)
        px.append(row)
    return px


def bubble(size: int = 32):
    """One bubble off the bow: a white disc with a soft rim and a faint blue
    ring inside it, drawn as a billboard."""
    px = []
    c = (size - 1) / 2.0
    radius = size * 0.44
    for y in range(size):
        row = []
        for x in range(size):
            d = math.hypot(x - c, y - c)
            cover = max(0.0, min(1.0, (radius - d) / 1.5))
            ring = max(0.0, 1.0 - abs(d - (radius - 3.0)) / 2.0) * cover
            a = int(255 * cover)
            row.append((int(255 - 50 * ring), int(255 - 22 * ring), 255, a) if a > 0 else CLEAR)
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
    write_png(ASSETS / "textures/skin.png", 256, 8, skin())
    write_png(ASSETS / "textures/lines.png", 256, 256, lines())
    write_png(ASSETS / "textures/foam.png", 128, 128, foam())
    write_png(ASSETS / "textures/bubble.png", 32, 32, bubble())
    write_png(ASSETS / "textures/ring.png", 64, 64, ring())
    print("textures: skin.png (256x8), lines.png (256x256), foam.png (128x128), bubble.png (32x32), ring.png (64x64)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

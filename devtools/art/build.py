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
# its lines and foam. The sheet is drawn at under half alpha, so it
# lightens the water under it rather than covering it; a shader's water
# shows through.
SHEET = (188, 224, 252, 96)
LINE = (255, 255, 255, 236)
RIM = (205, 232, 255, 255)
CLEAR = (0, 0, 0, 0)

# Everything white is drawn at the game's own density, sixteen pixels to
# the block, with hard edges: foam in this world is pixels, not airbrush,
# and the entity render type samples textures without blurring, so a
# pixel here is a square on the water.
PX = 16

# The skin and line textures map across the V by the distance from its
# edge in blocks, from EDGE_INSIDE (well inside, where the sheet is all
# one thing) to EDGE_OUTSIDE (past the edge, where there is nothing), and
# along it by the chevron phase, one repeat per chevron wavelength. The
# renderer's WakeMesh.EDGE_INSIDE, WakeRenderer.EDGE_OUTSIDE and
# WakeField.CHEVRON_WAVELENGTH are these.
EDGE_INSIDE = -3.0
EDGE_OUTSIDE = 0.5
CHEVRON_WAVELENGTH = 3.0
ACROSS = int(round((EDGE_OUTSIDE - EDGE_INSIDE) * PX))     # 56
ALONG = int(round(CHEVRON_WAVELENGTH * PX))                 # 48
# The churn's texture tiles over WakeMesh.FOAM_TILE blocks.
FOAM_TILE = 3.0


def blocks_across(x_pixel: int, width: int) -> float:
    """The edge coordinate, in blocks, at the centre of a pixel column of the skin or line texture."""
    return EDGE_INSIDE + (x_pixel + 0.5) / width * (EDGE_OUTSIDE - EDGE_INSIDE)


def skin(width: int = ACROSS, height: int = ALONG):
    """The sheet of disturbed water inside the V and the white line on its
    edge: pale blue inside, fullest down the middle and thinner toward the
    arms so the lines carry the V, a hard white line two pixels wide on the
    edge itself, and nothing outside it. One still frame: a long straight
    line that stepped between frames read as a vibration, not as foam."""
    px = []
    for y in range(height):
        shift = 0.0
        row = []
        for x in range(width):
            e = blocks_across(x, width)
            if -2.0 / PX + shift <= e < shift:
                row.append(LINE)
            elif e < shift:
                inside = 0.45 + 0.55 * max(0.0, min(1.0, -e / 2.5))     # fuller toward the middle
                row.append((SHEET[0], SHEET[1], SHEET[2], int(SHEET[3] * inside)))
            else:
                row.append(CLEAR)
        px.append(row)
    return px


def lines(width: int = ACROSS, height: int = ALONG):
    """The white lines along the chevron ridges: a line two pixels wide at
    phase zero and a fainter one at phase one half, each stopping short of
    the V's edge so it never crosses the edge line; nothing anywhere else.
    One still frame, for the same reason as the skin's."""
    px = [[CLEAR] * width for _ in range(height)]
    for x in range(width):
        if blocks_across(x, width) > -0.18:
            continue
        for dy in (0, 1):
            px[dy][x] = LINE
        px[height // 2][x] = (255, 255, 255, int(LINE[3] * 0.3))
    return px


def foam(frame: int, size: int = int(FOAM_TILE * PX), count: int = 90, seed: int = 0xF0A):
    """The churn: a cloud of round white bubbles, one to four pixels in
    radius, hard-edged with a pale blue rim inside each, overlapping, with
    holes between; tiling both ways over FOAM_TILE blocks. The second
    frame is the same cloud with every bubble nudged a pixel and a few
    popped, so the churn boils as the frames alternate."""
    noise = Noise(seed)
    discs = [(noise.next() * size, noise.next() * size, 1.0 + 3.2 * noise.next() ** 1.6) for _ in range(count)]
    if frame:
        nudge = Noise(seed ^ 0xBEEF)
        moved = []
        for cx, cy, r in discs:
            if nudge.next() < 0.15:
                continue
            moved.append((cx + int(nudge.next() * 3) - 1, cy + int(nudge.next() * 3) - 1, r))
        discs = moved
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            best = None
            for cx, cy, r in discs:
                dx = (x + 0.5 - cx + size / 2) % size - size / 2
                dy = (y + 0.5 - cy + size / 2) % size - size / 2
                d = math.hypot(dx, dy)
                if d <= r and (best is None or r - d > best):
                    best = r - d
            if best is None:
                row.append(CLEAR)
            elif best < 1.0:
                row.append(RIM)
            else:
                row.append((255, 255, 255, 255))
        px.append(row)
    return px


def bubble(size: int = PX):
    """One bubble off the bow: a white disc with a pale blue rim, drawn as a billboard."""
    px = []
    c = (size - 1) / 2.0
    radius = size * 0.42
    for y in range(size):
        row = []
        for x in range(size):
            d = math.hypot(x - c, y - c)
            if d > radius:
                row.append(CLEAR)
            elif d > radius - 1.2:
                row.append(RIM)
            else:
                row.append((255, 255, 255, 255))
        px.append(row)
    return px


def flecks(frame: int, size: int = 64):
    """The foam a splash leaves on its ring: white pixels -- one and two
    across, hard-edged, the way a pixel sea draws foam -- scattered thickly
    round the crest of the ring texture, thinning inward and outward, with
    a few left in the middle where the thing went in. Drawn over the ring
    at the splash's foam opacity, so a gentle drop shows none of them. Each
    frame is its own scatter; the renderer cycles them, so the foam churns."""
    noise = Noise(0xF1EC + frame * 7919)
    px = [[CLEAR] * size for _ in range(size)]
    c = (size - 1) / 2.0
    for _ in range(170):
        r = 0.78 + (noise.next() - 0.5) * 0.26                      # round the crest, some in, some out
        a = noise.next() * math.tau
        x = int(c + r * c * math.cos(a))
        y = int(c + r * c * math.sin(a))
        big = noise.next() < 0.3
        for dy in range(2 if big else 1):
            for dx in range(2 if big else 1):
                if 0 <= x + dx < size and 0 <= y + dy < size:
                    px[y + dy][x + dx] = (255, 255, 255, 255)
    for _ in range(14):
        r = noise.next() * 0.4
        a = noise.next() * math.tau
        x = min(size - 1, max(0, int(c + r * c * math.cos(a))))
        y = min(size - 1, max(0, int(c + r * c * math.sin(a))))
        px[y][x] = (255, 255, 255, 255)
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
    write_png(ASSETS / "textures/skin.png", ACROSS, ALONG, skin())
    write_png(ASSETS / "textures/lines.png", ACROSS, ALONG, lines())
    for frame in (0, 1):
        write_png(ASSETS / f"textures/foam_{frame}.png", int(FOAM_TILE * PX), int(FOAM_TILE * PX), foam(frame))
    for frame in (0, 1, 2):
        write_png(ASSETS / f"textures/flecks_{frame}.png", 64, 64, flecks(frame))
    write_png(ASSETS / "textures/bubble.png", PX, PX, bubble())
    write_png(ASSETS / "textures/ring.png", 64, 64, ring())
    for stale in ("skin_0.png", "skin_1.png", "lines_0.png", "lines_1.png", "foam.png", "flecks.png"):
        (ASSETS / "textures" / stale).unlink(missing_ok=True)
    print(f"textures: skin ({ACROSS}x{ALONG}), lines ({ACROSS}x{ALONG}), foam_0/1 ({int(FOAM_TILE * PX)}x{int(FOAM_TILE * PX)}), "
          f"flecks_0/1/2 (64x64), bubble ({PX}x{PX}), ring (64x64)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""Draws res/drawable-nodpi/ic_launcher.png (192x192) with no dependencies.

Flat 4x supersampled render: a dark rounded tile, a teal radio body, a screen,
a knob and an antenna.  Run from the android/ folder: python gen_icon.py
"""
import math
import os
import struct
import zlib

SIZE = 192
SS = 4                      # supersampling factor
N = SIZE * SS

BG_TOP = (15, 42, 51)
BG_BOT = (11, 18, 32)
TEAL = (45, 212, 191)
SCREEN = (45, 212, 191)
DARK = (11, 18, 32)

px = [[(0, 0, 0, 0)] * N for _ in range(N)]


def put(x, y, color, alpha=255):
    if 0 <= x < N and 0 <= y < N:
        px[y][x] = (color[0], color[1], color[2], alpha)


def rounded_rect(x0, y0, x1, y1, r, color, alpha=255):
    for y in range(int(y0), int(y1) + 1):
        for x in range(int(x0), int(x1) + 1):
            dx = max(x0 + r - x, 0, x - (x1 - r))
            dy = max(y0 + r - y, 0, y - (y1 - r))
            if dx * dx + dy * dy <= r * r:
                put(x, y, color, alpha)


def ring(cx, cy, r, thickness, color):
    for y in range(int(cy - r - thickness), int(cy + r + thickness) + 1):
        for x in range(int(cx - r - thickness), int(cx + r + thickness) + 1):
            d = math.hypot(x - cx, y - cy)
            if r - thickness <= d <= r:
                put(x, y, color)


def disc(cx, cy, r, color):
    for y in range(int(cy - r), int(cy + r) + 1):
        for x in range(int(cx - r), int(cx + r) + 1):
            if math.hypot(x - cx, y - cy) <= r:
                put(x, y, color)


def thick_line(x0, y0, x1, y1, width, color):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    for i in range(steps + 1):
        t = i / steps
        x = x0 + (x1 - x0) * t
        y = y0 + (y1 - y0) * t
        disc(x, y, width / 2, color)


def scaled(v):
    return v * SS


# tile background, vertical gradient
for y in range(N):
    t = y / (N - 1)
    color = tuple(round(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
    for x in range(N):
        px[y][x] = (color[0], color[1], color[2], 255)

# cut the rounded corners of the tile
radius = scaled(38)
for y in range(N):
    for x in range(N):
        dx = max(radius - x, 0, x - (N - 1 - radius))
        dy = max(radius - y, 0, y - (N - 1 - radius))
        if dx * dx + dy * dy > radius * radius:
            px[y][x] = (0, 0, 0, 0)

# antenna
thick_line(scaled(126), scaled(70), scaled(150), scaled(26), scaled(7), TEAL)
# radio body
rounded_rect(scaled(42), scaled(64), scaled(150), scaled(150), scaled(14), TEAL)
rounded_rect(scaled(48), scaled(70), scaled(144), scaled(144), scaled(10), DARK)
# screen
rounded_rect(scaled(56), scaled(80), scaled(112), scaled(110), scaled(5), SCREEN)
# knob
ring(scaled(128), scaled(95), scaled(10), scaled(5), TEAL)
# speaker slots
for i in range(3):
    rounded_rect(scaled(57), scaled(120 + i * 8), scaled(104), scaled(122 + i * 8), scaled(1.5), TEAL)

# downsample
rows = []
for y in range(SIZE):
    row = bytearray()
    for x in range(SIZE):
        r = g = b = a = 0
        for sy in range(SS):
            for sx in range(SS):
                p = px[y * SS + sy][x * SS + sx]
                r += p[0] * p[3]
                g += p[1] * p[3]
                b += p[2] * p[3]
                a += p[3]
        if a == 0:
            row += b"\x00\x00\x00\x00"
        else:
            row += bytes((r // a, g // a, b // a, a // (SS * SS)))
    rows.append(bytes(row))


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


raw = b"".join(b"\x00" + r for r in rows)
png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(raw, 9))
       + chunk(b"IEND", b""))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "drawable-nodpi")
os.makedirs(out, exist_ok=True)
path = os.path.join(out, "ic_launcher.png")
with open(path, "wb") as f:
    f.write(png)
print(path, SIZE, SIZE, len(png), "bytes")

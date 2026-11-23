#!/usr/bin/env python3
"""Рисует текстуру разметочного карандаша 16x16 (без внешних библиотек) и модель предмета.
Usage: make_pencil.py <корень assets-папки>   -> <корень>/assets/ptm2/textures/item/line_marker.png
                                                  <корень>/assets/ptm2/models/item/line_marker.json
"""
import os
import struct
import sys
import zlib

W = H = 16
px = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c


OUTLINE = (58, 38, 22, 255)
GRAPHITE = (52, 52, 62, 255)
WOOD = (226, 188, 140, 255)
WOOD_D = (196, 150, 104, 255)
Y_L = (255, 228, 96, 255)
Y_M = (250, 200, 40, 255)
Y_D = (214, 150, 20, 255)
FER_L = (208, 214, 224, 255)
FER_D = (160, 166, 178, 255)
ER_L = (250, 150, 162, 255)
ER_D = (226, 108, 122, 255)

# карандаш по диагонали из левого нижнего угла (кончик) в правый верхний (ластик)
# s = x + y: 14, 15, 16 - полоса карандаша, 13 и 17 - контур; p = x - y - положение вдоль оси
for p in range(-16, 17):
    for s in (13, 14, 15, 16, 17):
        if (p + s) % 2:
            continue
        x = (p + s) // 2
        y = (s - p) // 2
        edge = s in (13, 17)
        if p <= -13:
            c = OUTLINE if edge else GRAPHITE
        elif p <= -9:
            c = OUTLINE if edge else (WOOD if s == 15 else WOOD_D)
        elif p <= 7:
            c = OUTLINE if edge else {14: Y_L, 15: Y_M, 16: Y_D}[s]
        elif p <= 9:
            c = OUTLINE if edge else (FER_L if s == 15 else FER_D)
        elif p <= 13:
            c = OUTLINE if edge else (ER_L if s == 15 else ER_D)
        else:
            c = OUTLINE
        put(x, y, c)

# грифель на самом кончике и тёмный торец ластика
put(0, 15, GRAPHITE)
put(14, 1, OUTLINE)
put(15, 0, OUTLINE)


def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)


raw = b"".join(b"\x00" + bytes(v for p4 in row for v in p4) for row in px)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

root = sys.argv[1]
tex = os.path.join(root, "assets", "ptm2", "textures", "item")
mod = os.path.join(root, "assets", "ptm2", "models", "item")
os.makedirs(tex, exist_ok=True)
os.makedirs(mod, exist_ok=True)
with open(os.path.join(tex, "line_marker.png"), "wb") as f:
    f.write(png)
with open(os.path.join(mod, "line_marker.json"), "w") as f:
    f.write('{\n  "parent": "minecraft:item/handheld",\n  "textures": {\n    "layer0": "ptm2:item/line_marker"\n  }\n}\n')
print("pencil texture and model written to", root)

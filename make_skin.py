"""Generates the Claude's Puppet robot skin (64x64, standard wide player layout). Run: python3 make_skin.py"""
import random, struct, zlib

random.seed(7)
W = H = 64
img = [[(0, 0, 0, 0)] * W for _ in range(H)]

METAL = (168, 174, 182)
EDGE = (112, 118, 128)
DARK = (58, 62, 72)
VISOR = (22, 26, 36)
ORANGE = (217, 119, 87)
GLOW = (255, 186, 140)

def metal():
    n = random.randint(-7, 7)
    return tuple(max(0, min(255, c + n)) for c in METAL) + (255,)

def solid(c):
    return tuple(c) + (255,)

def box(part, u, v, w, h, d, painter):
    faces = {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h),
    }
    for face, (fx, fy, fw, fh) in faces.items():
        for y in range(fh):
            for x in range(fw):
                img[fy + y][fx + x] = painter(part, face, x, y, fw, fh)

def painter(part, face, x, y, fw, fh):
    border = x == 0 or y == 0 or x == fw - 1 or y == fh - 1
    if part == "head":
        if face == "front":
            if y in (2, 3, 4) and 0 < x < 7:
                if y == 3 and x in (2, 5):
                    return solid(GLOW)
                if y == 3 and x in (1, 6):
                    return solid(ORANGE)
                return solid(VISOR)
            if y == 6 and 2 <= x <= 5:
                return solid(DARK if x % 2 == 0 else EDGE)
        if face == "top" and x in (3, 4) and y in (3, 4):
            return solid(ORANGE)
        if face in ("left", "right") and y in (3, 4) and x in (3, 4):
            return solid(DARK)  # ear bolts
        return solid(EDGE) if border else metal()
    if part == "body":
        if face == "front":
            if 3 <= x <= 4 and 3 <= y <= 4:
                return solid(GLOW)
            if 2 <= x <= 5 and 2 <= y <= 5:
                return solid(ORANGE) if (x in (2, 5) or y in (2, 5)) and not (x in (2, 5) and y in (2, 5)) else solid(DARK)
            if y == 8:
                return solid(DARK)
            if y == 10 and x % 2 == 1:
                return solid(EDGE)
        if face == "back" and 2 <= y <= 6 and x in (2, 5):
            return solid(DARK)  # vents
        if face in ("front", "back", "left", "right") and y == 8:
            return solid(DARK)
        return solid(EDGE) if border else metal()
    # Arms and legs:
    if part in ("arm", "leg"):
        if face in ("front", "back", "left", "right"):
            if y == 4 or y == 5 and part == "leg":
                return solid(DARK)  # elbow / knee
            if y >= fh - 2:
                return solid(DARK)  # hands / feet
            if face == "front" and part == "arm" and y == 7 and x in (1, 2):
                return solid(ORANGE)
        if face == "bottom":
            return solid(DARK)
        return solid(EDGE) if (x == 0 or x == fw - 1) else metal()
    return metal()

box("head", 0, 0, 8, 8, 8, painter)
box("body", 16, 16, 8, 12, 4, painter)
box("arm", 40, 16, 4, 12, 4, painter)
box("arm", 32, 48, 4, 12, 4, painter)
box("leg", 0, 16, 4, 12, 4, painter)
box("leg", 16, 48, 4, 12, 4, painter)

raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *img[y][x]) for x in range(W)) for y in range(H))
def chunk(t, data):
    return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
open("src/main/resources/assets/claudepuppet/textures/entity/robot.png", "wb").write(png)
print("wrote robot.png")

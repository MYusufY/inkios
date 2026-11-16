#!/usr/bin/env python3
import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "..", "core", "src", "main", "assets", "fonts")
LOGO_DIR = os.path.join(HERE, "logo")

TILE = 1024
CORNER = 230
R = 60                      # base unit = drop radius = stem half-width
CX = TILE // 2              # symmetry axis
DROP_CY = 272
APEX_Y = DROP_CY - 2 * R    # 152
GAP = R
STEM_TOP = DROP_CY + R + GAP  # 392
STEM_H = 8 * R              # 480
STEM_BOTTOM = STEM_TOP + STEM_H  # 872
BANDS = 15
BAND_H = STEM_H // BANDS    # 32
HALF_ANGLE = math.asin(R / (2 * R))  # 30°, tangent from apex to circle

assert STEM_H % BANDS == 0
assert APEX_Y == TILE - STEM_BOTTOM, "mark must be vertically centred"

def level(k):
    return k * 0x11

MODES = {
    "light": {"tile": level(15), "drop": level(0), "bands": [level(k) for k in range(0, 15)]},
    "dark": {"tile": level(0), "drop": level(15), "bands": [level(k) for k in range(15, 0, -1)]},
}

def tangent_points():
    phi = math.pi / 2 - HALF_ANGLE
    dx, dy = R * math.sin(phi), R * math.cos(phi)
    return (CX - dx, DROP_CY - dy), (CX + dx, DROP_CY - dy)

def svg(mode="light", border=False, size=TILE):
    m = MODES[mode]
    (lx, ly), (rx, ry) = tangent_points()
    hexc = lambda g: "#%02x%02x%02x" % (g, g, g)
    sw = R / 2  # stem half-width
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
        % (size, size, TILE, TILE),
        '<defs><clipPath id="stem"><rect x="%g" y="%g" width="%g" height="%g" rx="%g"/></clipPath></defs>'
        % (CX - R, STEM_TOP, 2 * R, STEM_H, R),
        '<rect width="%d" height="%d" rx="%d" fill="%s"%s/>' % (
            TILE, TILE, CORNER, hexc(m["tile"]),
            ' stroke="#cccccc" stroke-width="12"' if border else ""),
        '<path d="M %.4f %.4f A %d %d 0 1 0 %.4f %.4f L %d %d Z" fill="%s"/>'
        % (lx, ly, R, R, rx, ry, CX, APEX_Y, hexc(m["drop"])),
        '<g clip-path="url(#stem)">',
    ]
    for i, g in enumerate(m["bands"]):
        parts.append('<rect x="%d" y="%d" width="%d" height="%d" fill="%s"/>'
                     % (CX - R, STEM_TOP + i * BAND_H, 2 * R, BAND_H, hexc(g)))
    parts += ["</g>", "</svg>"]
    del sw
    return "\n".join(parts)

SS = 4  # supersampling for clean anti-aliased edges

def mirror_left(im):
    w, h = im.size
    left = im.crop((0, 0, w // 2, h))
    im.paste(left.transpose(Image.FLIP_LEFT_RIGHT), (w - w // 2, 0))
    return im

def render(mode="light", size=1024, border=False, transparent_corners=True, bands=BANDS):
    m = MODES[mode]
    k = SS * size / TILE
    W = int(TILE * k)
    im = Image.new("L", (W, W), m["tile"])
    d = ImageDraw.Draw(im)
    s = lambda v: v * k

    stem = Image.new("L", (W, W), 0)
    sd = ImageDraw.Draw(stem)
    for i, g in enumerate(m["bands"][:bands]):
        sd.rectangle([s(CX - R), s(STEM_TOP + i * BAND_H), s(CX + R) - 1, s(STEM_TOP + (i + 1) * BAND_H) - 1], fill=g)
    if bands < BANDS:  # unfilled part of the stem shows the tile colour
        sd.rectangle([s(CX - R), s(STEM_TOP + bands * BAND_H), s(CX + R) - 1, s(STEM_BOTTOM) - 1], fill=m["tile"])
    cap = Image.new("L", (W, W), 0)
    ImageDraw.Draw(cap).rounded_rectangle([s(CX - R), s(STEM_TOP), s(CX + R) - 1, s(STEM_BOTTOM) - 1],
                                          radius=s(R), fill=255)
    im.paste(stem, (0, 0), cap)

    d.ellipse([s(CX - R), s(DROP_CY - R), s(CX + R), s(DROP_CY + R)], fill=m["drop"])
    (lx, ly), (rx, ry) = tangent_points()
    d.polygon([(s(lx), s(ly)), (s(CX), s(APEX_Y)), (s(rx), s(ry)), (s(CX), s(DROP_CY))], fill=m["drop"])

    if border:
        d.rounded_rectangle([0, 0, W - 1, W - 1], radius=s(CORNER), outline=0xCC, width=max(1, int(s(12))))

    mirror_left(im)
    im = mirror_left(im.resize((size, size), Image.LANCZOS))
    if not transparent_corners:
        return im
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=s(CORNER), fill=255)
    out = im.convert("RGBA")
    out.putalpha(mirror_left(mirror_left(mask).resize((size, size), Image.LANCZOS)))
    return out

def mark_only(mode="light", size=512):
    tile = render(mode, size, transparent_corners=False)
    bg = MODES[mode]["tile"]
    alpha = tile.point(lambda v: 255 if v != bg else 0)
    k = SS * size / TILE
    W = int(TILE * k)
    s = lambda v: v * k
    mask = Image.new("L", (W, W), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([s(CX - R), s(STEM_TOP), s(CX + R) - 1, s(STEM_BOTTOM) - 1], radius=s(R), fill=255)
    md.ellipse([s(CX - R), s(DROP_CY - R), s(CX + R), s(DROP_CY + R)], fill=255)
    (lx, ly), (rx, ry) = tangent_points()
    md.polygon([(s(lx), s(ly)), (s(CX), s(APEX_Y)), (s(rx), s(ry)), (s(CX), s(DROP_CY))], fill=255)
    del alpha
    out = tile.convert("RGBA")
    out.putalpha(mirror_left(mirror_left(mask).resize((size, size), Image.LANCZOS)))
    return out

def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name + ".ttf"), size)

def construction_sheet(path):
    P = 1024 + 2 * 120
    W, H = P * 2 + 60, P + 380
    sheet = Image.new("RGB", (W, H), (244, 244, 244))
    d = ImageDraw.Draw(sheet)
    lab = font("Inter-Medium", 22)
    title = font("InterDisplay-SemiBold", 40)
    blue = (40, 110, 230)
    for col, mode in enumerate(["light", "dark"]):
        ox, oy = 30 + col * (P + 0) + 120, 120
        sheet.paste(render(mode, 1024, border=(mode == "light")), (ox, oy), render(mode, 1024, border=(mode == "light")))
        if col == 0:
            def hline(y, txt):
                d.line([(ox - 60, oy + y), (ox + 1024 + 10, oy + y)], fill=blue, width=2)
                d.text((ox + 1024 + 16, oy + y - 13), txt, font=lab, fill=blue)
            for y, txt in [(APEX_Y, "152  apex"), (DROP_CY, "272  drop centre"), (DROP_CY + R, "332"),
                           (STEM_TOP, "392  stem"), (STEM_BOTTOM, "872")]:
                hline(y, txt)
            for x in (CX - R, CX, CX + R):
                d.line([(ox + x, oy - 40), (ox + x, oy + 1024 + 20)], fill=blue, width=2)
            d.text((ox + CX - R - 10, oy - 75), "452", font=lab, fill=blue)
            d.text((ox + CX - 22, oy - 105), "512 axis", font=lab, fill=blue)
            d.text((ox + CX + R - 20, oy - 75), "572", font=lab, fill=blue)
            d.ellipse([ox + CX - R, oy + DROP_CY - R, ox + CX + R, oy + DROP_CY + R], outline=blue, width=2)
            (lx, ly), (rx, ry) = tangent_points()
            for px, py in [(lx, ly), (rx, ry)]:
                d.ellipse([ox + px - 6, oy + py - 6, ox + px + 6, oy + py + 6], fill=blue)
    notes = [
        "Inkios mark · construction",
        "base unit r = 60 · drop Ø = stem width = 2r · gap = r · stem = 8r · tip angle 60° (tangents from apex 2r above centre)",
        "mark height 12r = 720, centred (152 / 152) · tile 1024, corner 230 · stem = 15 bands × 32 = panel grey levels",
    ]
    y = P + 40
    d.text((30, y), notes[0], font=title, fill=(0, 0, 0))
    for n in notes[1:]:
        y += 62
        d.text((30, y), n, font=font("Inter-Regular", 26), fill=(68, 68, 68))
    x = 30
    y += 70
    for mode in ("light", "dark"):
        for sz in (128, 64, 48, 32, 24):
            ic = render(mode, sz, border=(mode == "light"))
            sheet.paste(ic, (x, y + (128 - sz) // 2), ic)
            x += sz + 30
        x += 60
    sheet.save(path)

def main():
    os.makedirs(LOGO_DIR, exist_ok=True)
    for mode in ("light", "dark"):
        with open(os.path.join(LOGO_DIR, "inkios-mark-%s.svg" % mode), "w") as f:
            f.write(svg(mode))
        render(mode, 1024).save(os.path.join(LOGO_DIR, "inkios-mark-%s-1024.png" % mode))
    construction_sheet(os.path.join(LOGO_DIR, "construction.png"))
    print("wrote", LOGO_DIR)

if __name__ == "__main__":
    main()

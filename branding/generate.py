#!/usr/bin/env python3
import argparse
import io
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import logo  # noqa: E402

FONTS = os.path.join(HERE, "..", "core", "src", "main", "assets", "fonts")
OUT = os.path.join(HERE, "out")

INK, PAPER, GREY = 0x00, 0xFF, 0x44

MARK_PT = 132
MARK_TOP_FROM_CENTER_PT = -106
WORDMARK_PT = 34
WORDMARK_GAP_PT = 62
CAPTION_PT = 16
CAPTION_FROM_BOTTOM_PT = 72

SLEEP_CAPTION = "Sleeping  ·  Press the power button to wake"

def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name + ".ttf"), size)

def system_screen(w, h, bands=logo.BANDS, caption=None, wordmark=True):
    u = w / 560.0
    im = Image.new("L", (w, h), PAPER)
    d = ImageDraw.Draw(im)
    size = round(MARK_PT * u)
    left = round((w - size) / 2)
    top = round(h / 2 + MARK_TOP_FROM_CENTER_PT * u)
    mark = logo.render("dark", size, bands=bands)
    im.paste(mark.convert("L"), (left, top), mark.split()[-1])
    if wordmark:
        d.text((w / 2, top + size + WORDMARK_GAP_PT * u), "Inkios",
               font=font("InterDisplay-SemiBold", round(WORDMARK_PT * u)), fill=INK, anchor="ms")
    if caption:
        d.text((w / 2, h - CAPTION_FROM_BOTTOM_PT * u), caption,
               font=font("Inter-Medium", round(CAPTION_PT * u)), fill=GREY, anchor="ms")
    return im

def battery_glyph(d, cx, cy, w, level, fill=INK):
    h = w * 0.48
    l, t = cx - w / 2, cy - h / 2
    sw = max(3, int(w * 0.045))
    cap = w * 0.07
    d.rounded_rectangle([l, t, l + w - cap, t + h], radius=h * 0.22, outline=fill, width=sw)
    d.rounded_rectangle([l + w - cap + sw * 0.6, t + h * 0.32, l + w, t + h * 0.68], radius=cap * 0.4, fill=fill)
    if level > 0:
        pad = sw * 2.2
        iw = (w - cap) - pad * 2
        d.rounded_rectangle([l + pad, t + pad, l + pad + iw * level, t + h - pad], radius=h * 0.12, fill=fill)

def battery_screen(w, h, title, caption, level):
    u = w / 560.0
    im = Image.new("L", (w, h), PAPER)
    d = ImageDraw.Draw(im)
    size = MARK_PT * u
    top = h / 2 + MARK_TOP_FROM_CENTER_PT * u
    battery_glyph(d, w / 2, top + size / 2, size * 1.1, level)
    d.text((w / 2, top + size + WORDMARK_GAP_PT * u), title,
           font=font("InterDisplay-SemiBold", round(WORDMARK_PT * u)), fill=INK, anchor="ms")
    d.text((w / 2, h - CAPTION_FROM_BOTTOM_PT * u), caption,
           font=font("Inter-Medium", round(CAPTION_PT * u)), fill=GREY, anchor="ms")
    return im

def png_bytes(im):
    b = io.BytesIO()
    im.convert("RGB").save(b, "PNG", optimize=True)
    return b.getvalue()

def boot_animation(w, h, path, fps=8):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", "%d %d %d\np 1 0 part0\np 0 0 part1\n" % (w, h, fps))
        for b in range(0, logo.BANDS + 1):
            z.writestr("part0/%05d.png" % b, png_bytes(system_screen(w, h, bands=b)))
        z.writestr("part1/%05d.png" % (logo.BANDS + 1), png_bytes(system_screen(w, h)))

def launcher_icons(res_dir):
    for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(res_dir, "mipmap-" + density)
        os.makedirs(out, exist_ok=True)
        logo.render("light", px, border=True).save(os.path.join(out, "ic_launcher.png"))

def notes_icon(size):
    import math
    S = logo.TILE
    k = 4 * size / S
    W = int(S * k)
    im = Image.new("L", (W, W), 255)
    d = ImageDraw.Draw(im)
    for y in (360, 512, 664):
        d.rounded_rectangle([200 * k, (y - 6) * k, 824 * k, (y + 6) * k], radius=6 * k, fill=0xCC)
    pts = []
    for i in range(0, 101):
        t = i / 100.0
        x = 230 + 560 * t
        y = 560 - 120 * math.sin(t * math.pi * 2.2) * (1 - 0.35 * t) - 40 * t
        pts.append((x * k, y * k))
    d.line(pts, fill=0, width=int(36 * k), joint="curve")
    for p in (pts[0], pts[-1]):
        r = 18 * k
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=0)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, outline=0xCC, width=int(12 * k))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out

def books_icon(size):
    S = logo.TILE
    k = 4 * size / S
    W = int(S * k)
    im = Image.new("L", (W, W), 255)
    d = ImageDraw.Draw(im)
    shelf = 792
    spines = [(232, 120, 480, 0x00), (368, 120, 420, 0x55), (504, 120, 540, 0x00), (640, 120, 360, 0x99)]
    for x, w, h, g in spines:
        d.rounded_rectangle([x * k, (shelf - h) * k, (x + w) * k, shelf * k], radius=18 * k, fill=g)
    d.rounded_rectangle([200 * k, (shelf + 24) * k, 824 * k, (shelf + 48) * k], radius=12 * k, fill=0)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, outline=0xCC, width=int(12 * k))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out

def share_icon(size):
    S = logo.TILE
    k = 4 * size / S
    W = int(S * k)
    im = Image.new("L", (W, W), 255)
    d = ImageDraw.Draw(im)
    sw = 60  # stroke = r
    d.rounded_rectangle([272 * k, 560 * k, (272 + sw) * k, 812 * k], radius=30 * k, fill=0)
    d.rounded_rectangle([(752 - sw) * k, 560 * k, 752 * k, 812 * k], radius=30 * k, fill=0)
    d.rounded_rectangle([272 * k, (812 - sw) * k, 752 * k, 812 * k], radius=30 * k, fill=0)
    d.rounded_rectangle([(512 - sw / 2) * k, 212 * k, (512 + sw / 2) * k, 620 * k], radius=30 * k, fill=0)
    d.polygon([(392 * k, 520 * k), (632 * k, 520 * k), (512 * k, 680 * k)], fill=0)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, outline=0xCC, width=int(12 * k))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out

def browser_icon(size):
    S = logo.TILE
    k = 4 * size / S
    W = int(S * k)
    im = Image.new("L", (W, W), 255)
    d = ImageDraw.Draw(im)
    sw = int(44 * k)
    R = 300
    c = 512
    d.ellipse([(c - R) * k, (c - R) * k, (c + R) * k, (c + R) * k], outline=0, width=sw)
    d.ellipse([(c - R * 0.45) * k, (c - R) * k, (c + R * 0.45) * k, (c + R) * k], outline=0, width=sw)
    d.line([((c - R) * k, c * k), ((c + R) * k, c * k)], fill=0, width=sw)
    d.line([(c * k, (c - R) * k), (c * k, (c + R) * k)], fill=0, width=sw)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, outline=0xCC, width=int(12 * k))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out

def games_icon(size):
    S = logo.TILE
    k = 4 * size / S
    W = int(S * k)
    im = Image.new("L", (W, W), 255)
    d = ImageDraw.Draw(im)
    R = 180
    d.ellipse([(332 - R) * k, (512 - R) * k, (332 + R) * k, (512 + R) * k], fill=0)
    d.ellipse([(692 - R) * k, (512 - R) * k, (692 + R) * k, (512 + R) * k], fill=255, outline=0, width=int(36 * k))
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, outline=0xCC, width=int(12 * k))
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=logo.CORNER * k, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out

def app_icons(res_dir, render):
    for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(res_dir, "mipmap-" + density)
        os.makedirs(out, exist_ok=True)
        render(px).save(os.path.join(out, "ic_launcher.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", default="1080x1440", help="portrait panel size WxH")
    args = ap.parse_args()
    w, h = (int(x) for x in args.size.lower().split("x"))
    os.makedirs(OUT, exist_ok=True)

    boot_animation(w, h, os.path.join(OUT, "bootanimation.zip"))
    system_screen(w, h, caption=SLEEP_CAPTION).convert("RGB").save(os.path.join(OUT, "sleep.png"), optimize=True)
    system_screen(w, h, caption="Powered off  ·  Hold the power button to turn on").convert("RGB").save(
        os.path.join(OUT, "shutdown.png"), optimize=True)
    battery_screen(w, h, "Battery empty", "Connect a charger to continue", 0).convert("RGB").save(
        os.path.join(OUT, "battery_empty.png"), optimize=True)
    battery_screen(w, h, "Battery low", "Connect a charger soon", 0.12).convert("RGB").save(
        os.path.join(OUT, "battery_low.png"), optimize=True)
    system_screen(w, h, bands=7).convert("RGB").save(os.path.join(OUT, "boot_preview.png"), optimize=True)
    launcher_icons(os.path.join(HERE, "..", "app", "src", "main", "res"))
    app_icons(os.path.join(HERE, "..", "notes", "src", "main", "res"), notes_icon)
    notes_icon(512).save(os.path.join(OUT, "icon_notes.png"))
    app_icons(os.path.join(HERE, "..", "books", "src", "main", "res"), books_icon)
    books_icon(512).save(os.path.join(OUT, "icon_books.png"))
    app_icons(os.path.join(HERE, "..", "share", "src", "main", "res"), share_icon)
    share_icon(512).save(os.path.join(OUT, "icon_share.png"))
    app_icons(os.path.join(HERE, "..", "browser", "src", "main", "res"), browser_icon)
    browser_icon(512).save(os.path.join(OUT, "icon_browser.png"))
    app_icons(os.path.join(HERE, "..", "games", "src", "main", "res"), games_icon)
    games_icon(512).save(os.path.join(OUT, "icon_games.png"))
    print("wrote", OUT)

if __name__ == "__main__":
    main()

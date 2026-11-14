"""Builds every Strata:3D icon asset from the master logo PNG (transparent background).

Usage: python tools/make_icons.py branding/strata3d-logo.png

The logo is split into two alpha masks: the cyan strokes and the dark "ink" strokes. The app tints the
ink layer with the theme's text colour, so the logo stays readable on both light and dark bars.
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
CYAN = (0x01, 0xB2, 0xC6)
INK = (0x11, 0x10, 0x10)
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def split_layers(src: Image.Image):
    src = src.convert("RGBA")
    w, h = src.size
    cyan = Image.new("L", (w, h), 0)
    ink = Image.new("L", (w, h), 0)
    sp, cp, ip = src.load(), cyan.load(), ink.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = sp[x, y]
            if a < 16:  # the source has faint alpha noise across the canvas
                continue
            t = max(0.0, min(1.0, (g - INK[1]) / (CYAN[1] - INK[1])))
            cp[x, y] = round(a * t)
            ip[x, y] = round(a * (1 - t))
    box = Image.merge("RGBA", (cyan, cyan, cyan, cyan)).getbbox()
    box2 = Image.merge("RGBA", (ink, ink, ink, ink)).getbbox()
    bbox = (min(box[0], box2[0]), min(box[1], box2[1]), max(box[2], box2[2]), max(box[3], box2[3]))
    return cyan.crop(bbox), ink.crop(bbox)


def colorize(mask: Image.Image, rgb):
    layer = Image.new("RGBA", mask.size, rgb + (0,))
    layer.putalpha(mask)
    return layer


def composite(cyan, ink, ink_rgb=INK):
    out = colorize(ink, ink_rgb)
    out.alpha_composite(colorize(cyan, CYAN))
    return out


def fit(img: Image.Image, box_px: int):
    s = box_px / max(img.size)
    return img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)


def centered(img: Image.Image, canvas_px: int, bg=(0, 0, 0, 0)):
    c = Image.new("RGBA", (canvas_px, canvas_px), bg)
    c.alpha_composite(img, ((canvas_px - img.width) // 2, (canvas_px - img.height) // 2))
    return c


def main(src_path):
    cyan, ink = split_layers(Image.open(src_path))
    logo = composite(cyan, ink)

    # In-app logo layers (white masks, tinted at runtime).
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    for name, mask in (("logo_layer_cyan", cyan), ("logo_layer_ink", ink)):
        m = fit(colorize(mask, (255, 255, 255)), 256)
        m.save(nodpi / f"{name}.png", optimize=True)

    # Adaptive launcher icon: 108dp canvas, logo inside the 66dp circular safe zone (52dp box; the rounded bracket corners stay inside a 72dp circle).
    for dens, k in DENSITIES.items():
        d = RES / f"mipmap-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        canvas = round(108 * k)
        centered(fit(logo, round(52 * k)), canvas).save(d / "ic_launcher_foreground.png", optimize=True)
        both = ImageChops.lighter(cyan, ink)  # full silhouette for themed (monochrome) icons
        mono = fit(colorize(both, (255, 255, 255)), round(52 * k))
        centered(mono, canvas).save(d / "ic_launcher_monochrome.png", optimize=True)

    # Store listing icon (512px, white rounded-square background).
    store = centered(fit(logo, 400), 512, (255, 255, 255, 255))
    store.convert("RGB").save(ROOT / "fastlane/metadata/android/en-US/images/icon.png", optimize=True)
    print("icons written")


if __name__ == "__main__":
    main(sys.argv[1])

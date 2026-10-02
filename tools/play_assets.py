"""Builds the Google Play store graphics for exCover from the screenshots in docs/.

Play wants screenshots no more than twice as tall as they are wide, so each one is placed on a
1080x2160 canvas under a short caption. Also writes the 512px icon and the 1024x500 feature
graphic. Output goes to play/.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "play"
FONT = str(ROOT / "app/src/main/res/font/outfit.ttf")
BG = (11, 11, 15)
TEXT = (245, 245, 247)
MUTED = (160, 160, 170)

SHOTS = [
    ("screenshot-main.jpg", "Your GIFs, one swipe away"),
    ("screenshot-giphy.jpg", "Search GIPHY right inside"),
    ("screenshot-edit.jpg", "Frame it for each screen"),
    ("screenshot-set.jpg", "Cover, lock or main screen"),
    ("screenshot-themes.jpg", "Clock themes in Motorola's picker"),
    ("screenshot-aod.jpg", "Your GIF on the always-on display"),
]


def font(size, weight=600):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([weight])
    except (OSError, AttributeError):
        pass  # not a variable font
    return f


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *im.size), radius, fill=255)
    out = Image.new("RGBA", im.size)
    out.paste(im, (0, 0), mask)
    return out


def centred_text(draw, y, text, f, fill, width):
    w = draw.textlength(text, font=f)
    draw.text(((width - w) / 2, y), text, font=f, fill=fill)


def screenshot(name, caption, index):
    canvas = Image.new("RGB", (1080, 2160), BG)
    draw = ImageDraw.Draw(canvas)
    centred_text(draw, 110, caption, font(64), TEXT, 1080)
    shot = Image.open(ROOT / "docs" / name).convert("RGB")
    height = 1800
    width = round(shot.width * height / shot.height)
    if width > 960:
        width = 960
        height = round(shot.height * width / shot.width)
    shot = rounded(shot.resize((width, height), Image.LANCZOS), 48)
    canvas.paste(shot, ((1080 - width) // 2, 2160 - height - 90), shot)
    canvas.save(OUT / f"screenshot-{index}.png")


def feature_graphic():
    canvas = Image.new("RGB", (1024, 500), BG)
    draw = ImageDraw.Draw(canvas)
    logo = Image.open(ROOT / "docs/logo.png").convert("RGBA").resize((132, 132), Image.LANCZOS)
    canvas.paste(logo, (64, 104), logo)
    draw.text((64, 262), "exCover", font=font(68, 700), fill=TEXT)
    draw.text((66, 350), "Animated GIFs on your", font=font(30, 400), fill=MUTED)
    draw.text((66, 388), "Razr's cover screen", font=font(30, 400), fill=MUTED)
    gif = Image.open(ROOT / "docs/cover-showcase.gif")
    gif.seek(min(24, gif.n_frames - 1))
    frame = gif.convert("RGB")
    width = 520
    frame = rounded(frame.resize((width, round(frame.height * width / frame.width)), Image.LANCZOS), 28)
    canvas.paste(frame, (1024 - width - 48, (500 - frame.height) // 2), frame)
    canvas.save(OUT / "feature-graphic.png")


def main():
    OUT.mkdir(exist_ok=True)
    for i, (name, caption) in enumerate(SHOTS, 1):
        screenshot(name, caption, i)
    Image.open(ROOT / "docs/logo.png").convert("RGBA").save(OUT / "icon-512.png")
    feature_graphic()
    print("wrote", sorted(p.name for p in OUT.iterdir()))


if __name__ == "__main__":
    main()

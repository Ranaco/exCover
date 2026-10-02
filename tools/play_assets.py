"""Builds exCover's Google Play graphics: seven phone screenshots, the feature graphic and the icon.

Each screenshot is a 1080x1920 card: a dark background lit by a soft glow in exCover's icon
colours, a headline and one supporting line, and the screen itself in a drawn Razr. Cover shots
sit in the folded phone; app shots sit in the open phone, rising from the bottom edge.
Output goes to play/.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "play"
FONT = str(ROOT / "app/src/main/res/font/outfit.ttf")

W, H = 1080, 1920
BG = (9, 9, 14)
TEXT = (246, 246, 250)
MUTED = (164, 166, 184)
PINK, VIOLET, BLUE = (255, 92, 170), (150, 82, 255), (66, 120, 255)

# (kind, source, headline, supporting line, glow colours)
SHOTS = [
    ("cover", "cover-home.png", "Animated GIFs\non your cover", "No root, no computer. Pick a GIF, close the phone.", (VIOLET, PINK)),
    ("main", "docs/screenshot-main.jpg", "Your GIFs,\none swipe away", "Recent ones and favourites, always close.", (BLUE, VIOLET)),
    ("main", "docs/screenshot-giphy.jpg", "Search GIPHY\nright inside", "Or pick from Photos, or paste any link.", (PINK, BLUE)),
    ("main", "docs/screenshot-edit.jpg", "Frame it\nfor each screen", "Pinch and drag. Cover and main, separately.", (VIOLET, BLUE)),
    ("main", "docs/screenshot-set.jpg", "Every screen,\nits own GIF", "Cover home and lock, main home and lock.", (BLUE, PINK)),
    ("main", "docs/screenshot-themes.jpg", "Clock themes in\nMotorola's picker", "Five styles, in your font and colour.", (PINK, VIOLET)),
    ("main", "docs/screenshot-aod.jpg", "Your GIF on the\nalways-on display", "Six looks, most of them made to save battery.", (VIOLET, PINK)),
]


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([weight])
    except (OSError, AttributeError):
        pass
    return f


def glow(size, colours, centre_y):
    """Two soft colour clouds behind the phone, like light spilling from its screen."""
    layer = Image.new("RGB", size, BG)
    d = ImageDraw.Draw(layer)
    w, h = size
    a, b = colours
    d.ellipse((w * -0.15, centre_y - h * 0.22, w * 0.75, centre_y + h * 0.18), fill=a)
    d.ellipse((w * 0.3, centre_y - h * 0.08, w * 1.2, centre_y + h * 0.3), fill=b)
    layer = layer.filter(ImageFilter.GaussianBlur(min(w, h) * 0.2))
    return Image.blend(Image.new("RGB", size, BG), layer, 0.55)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def shadow(canvas, box, radius, spread=60, opacity=150):
    x0, y0, x1, y1 = box
    sh = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle((x0, y0 + 30, x1, y1 + 30), radius, fill=opacity)
    sh = sh.filter(ImageFilter.GaussianBlur(spread))
    canvas.paste(Image.new("RGB", canvas.size, (0, 0, 0)), (0, 0), sh)


def device(canvas, screen, x, y, screen_w, screen_radius, bezel, body_radius, cameras=None, punch=False):
    """A phone body with the screen inset; returns its bottom edge."""
    screen = screen.convert("RGB")
    screen_h = round(screen.height * screen_w / screen.width)
    screen = screen.resize((screen_w, screen_h), Image.LANCZOS)
    bw, bh = screen_w + 2 * bezel, screen_h + 2 * bezel
    shadow(canvas, (x, y, x + bw, y + bh), body_radius)
    body = Image.new("RGB", (bw, bh), (16, 16, 21))
    bd = ImageDraw.Draw(body)
    bd.rounded_rectangle((0, 0, bw - 1, bh - 1), body_radius, outline=(58, 58, 70), width=3)  # the metal edge
    bd.rounded_rectangle((3, 3, bw - 4, bh - 4), body_radius - 3, outline=(28, 28, 36), width=2)
    canvas.paste(body, (x, y), rounded_mask((bw, bh), body_radius))
    canvas.paste(screen, (x + bezel, y + bezel), rounded_mask((screen_w, screen_h), screen_radius))
    d = ImageDraw.Draw(canvas)
    if punch:  # the main screen's front camera
        cx, cy, r = x + bw // 2, y + bezel + 40, 15
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(4, 4, 6))
    if cameras:  # the cover's two lenses, set into the screen's bottom-right
        r = cameras
        for i in range(2):
            cx = x + bezel + screen_w - r * 1.45 - i * r * 2.55
            cy = y + bezel + screen_h - r * 1.45
            d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(10, 10, 13), outline=(70, 70, 82), width=4)
            d.ellipse((cx - r * 0.55, cy - r * 0.55, cx + r * 0.55, cy + r * 0.55), fill=(22, 26, 40))
            d.ellipse((cx - r * 0.2, cy - r * 0.42, cx + r * 0.05, cy - r * 0.17), fill=(120, 130, 170))  # glint
    return y + bh


def headline(canvas, title, line):
    d = ImageDraw.Draw(canvas)
    f = font(92, 700)
    y = 150
    for part in title.split("\n"):
        w = d.textlength(part, font=f)
        d.text(((W - w) / 2, y), part, font=f, fill=TEXT)
        y += 106
    small = font(38, 400)
    w = d.textlength(line, font=small)
    if w > W - 140:  # wrap a long supporting line onto two
        words, first = line.split(), ""
        for word in words:
            if d.textlength((first + " " + word).strip(), font=small) > W - 140:
                break
            first = (first + " " + word).strip()
        rest = line[len(first):].strip()
        for part in (first, rest):
            d.text(((W - d.textlength(part, font=small)) / 2, y + 26), part, font=small, fill=MUTED)
            y += 50
        return y + 26
    d.text(((W - w) / 2, y + 26), line, font=small, fill=MUTED)
    return y + 76


def shot(kind, source, title, line, colours, index):
    canvas = glow((W, H), colours, H * 0.68)
    top = headline(canvas, title, line) + 70
    screen = Image.open(ROOT / source)
    if kind == "cover":
        sw = 860
        body_h = round(screen.height * sw / screen.width) + 56
        y = top + (H - top - body_h) // 2 - 20  # centred in the space under the words
        device(canvas, screen, (W - sw - 56) // 2, y, sw, 54, 28, 82, cameras=48)
    else:
        sw = 760
        device(canvas, screen, (W - sw - 40) // 2, top, sw, 64, 20, 84, punch=True)  # runs off the bottom
    canvas.save(OUT / f"screenshot-{index}.png")


def feature_graphic():
    fw, fh = 1024, 500
    canvas = glow((fw, fh), (VIOLET, PINK), fh * 0.55)
    d = ImageDraw.Draw(canvas)
    logo = Image.open(ROOT / "docs/logo.png").convert("RGBA").resize((112, 112), Image.LANCZOS)
    canvas.paste(logo, (70, 96), logo)
    d.text((70, 232), "exCover", font=font(78, 700), fill=TEXT)
    d.text((72, 330), "Animated GIFs for", font=font(32, 400), fill=MUTED)
    d.text((72, 372), "your Razr's cover screen", font=font(32, 400), fill=MUTED)
    device(canvas, Image.open(ROOT / "cover-home.png"), 600, 58, 330, 26, 14, 40, cameras=19)
    canvas.save(OUT / "feature-graphic.png")


def main():
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("screenshot-*.png"):
        old.unlink()
    for i, (kind, source, title, line, colours) in enumerate(SHOTS, 1):
        shot(kind, source, title, line, colours, i)
    Image.open(ROOT / "docs/logo.png").convert("RGBA").save(OUT / "icon-512.png")
    feature_graphic()
    print("wrote", sorted(p.name for p in OUT.iterdir()))


if __name__ == "__main__":
    main()

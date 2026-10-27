"""fade_wallpaper.py <picture> <out.jpg>: a Hearth Home wallpaper from any picture, as the bundled ones were made: cropped to
16:9 at 1920x1080, a little darker, darker still on the left under the clock, and melting into the background colour
(#0A0B0F) before the tiles, so only the top of the picture shows."""
import sys
from PIL import Image, ImageEnhance, ImageChops, ImageOps

W, H, BG = 1920, 1080, (10, 11, 15)


def smooth(a, b, x):
    t = min(1, max(0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


im = ImageOps.fit(ImageOps.exif_transpose(Image.open(sys.argv[1])).convert('RGB'), (W, H), Image.LANCZOS)
im = ImageEnhance.Color(ImageEnhance.Brightness(im).enhance(0.82)).enhance(1.08)
vert = Image.new('L', (1, H))
vert.putdata([int(255 * (1 - smooth(0.28, 0.82, y / (H - 1)))) for y in range(H)])
horiz = Image.new('L', (W, 1))
horiz.putdata([int(255 * (0.55 + 0.45 * smooth(0.0, 0.55, x / (W - 1)))) for x in range(W)])
horiz = horiz.resize((W, H))
im = ImageChops.multiply(im, Image.merge('RGB', (horiz, horiz, horiz)))
Image.composite(im, Image.new('RGB', (W, H), BG), vert.resize((W, H))).save(sys.argv[2], quality=86, optimize=True)

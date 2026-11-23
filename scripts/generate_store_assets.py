#!/usr/bin/env python3
from pathlib import Path
import argparse
import math
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--regular-font', default='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
parser.add_argument('--bold-font', default='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')
args = parser.parse_args()
output = root / 'docs/store'
source = Image.open(root / 'scripts/assets/gimbo-calendar-mark.png').convert('RGBA')
alpha = source.getchannel('A')
bounds = alpha.point(lambda value: 255 if value >= 16 else 0).getbbox()
cx = (bounds[0] + bounds[2] - 1) / 2
cy = (bounds[1] + bounds[3] - 1) / 2
pixels = alpha.load()
radius = max(math.hypot(x - cx, y - cy) for y in range(bounds[1], bounds[3]) for x in range(bounds[0], bounds[2]) if pixels[x, y] >= 16)

def mark(target_radius):
    size = round(source.width * target_radius / radius)
    scale = size / source.width
    artwork = source.resize((size, round(source.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (512, 512))
    canvas.alpha_composite(artwork, (round(256 - cx * scale), round(256 - cy * scale)))
    return canvas

foreground = mark(164)
foreground.save(root / 'app/src/main/res/drawable-nodpi/gimbo_calendar_foreground.png')
icon = Image.new('RGBA', (512, 512), '#111525')
icon.alpha_composite(mark(241))
icon.convert('RGB').save(output / 'icon-512.png')
rounded = icon.copy()
rounded.putalpha(Image.open(root / 'scripts/assets/gimbo-calendar-icon-mask.png').convert('L'))
rounded.save(output / 'icon-rounded-512.png')
for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    directory = root / f'app/src/main/res/mipmap-{density}'
    directory.mkdir(parents=True, exist_ok=True)
    rounded.resize((size, size), Image.Resampling.LANCZOS).save(directory / 'ic_launcher.png')
def phone(locale, name, height):
    shot = Image.open(root / 'docs/screenshots' / locale / name).convert('RGBA')
    shot = shot.resize((round(shot.width * height / shot.height), height), Image.Resampling.LANCZOS)
    frame = Image.new('RGBA', (shot.width + 24, shot.height + 24), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle((0, 0, frame.width - 1, frame.height - 1), radius=64, fill='#2A2A33')
    mask = Image.new('L', shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius=52, fill=255)
    frame.paste(shot, (12, 12), mask)
    return frame

for locale, headline, detail in [('de', 'Deine Woche. Im Blick.', 'Termine · Aufgaben · Notizen'), ('en', 'Your week. At a glance.', 'Events · Tasks · Notes')]:
    image = Image.new('RGBA', (2048, 1000), '#121216')
    draw = ImageDraw.Draw(image)
    tile = rounded.resize((150, 150), Image.Resampling.LANCZOS)
    image.alpha_composite(tile, (100, 150))
    draw.text((100, 345), 'Gimbo Kalender', font=ImageFont.truetype(args.bold_font, 88), fill='#F4F4FA')
    draw.text((100, 480), headline, font=ImageFont.truetype(args.regular_font, 70), fill='#B9A7FF')
    draw.text((100, 640), detail, font=ImageFont.truetype(args.regular_font, 38), fill='#BBBAC7')
    image.alpha_composite(phone(locale, '05-notes.png', 1180), (1530, 150))
    image.alpha_composite(phone(locale, '01-month.png', 1180), (1050, 90))
    image.convert('RGB').resize((1024, 500), Image.Resampling.LANCZOS).save(output / f'feature-{locale}-1024x500.png')

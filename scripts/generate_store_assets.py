#!/usr/bin/env python3
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--regular-font', default='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
parser.add_argument('--bold-font', default='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')
args = parser.parse_args()
output = root / 'docs/store'
res = root / 'app/src/main/res'
mark = Image.open(root / 'scripts/assets/gimbo-calendar-mark.png').convert('RGBA')
monochrome = Image.open(root / 'scripts/assets/gimbo-calendar-monochrome.png').convert('RGBA')
corners = [(12, 10, 20), (16, 24, 54), (82, 46, 130), (40, 66, 140)]


def gradient(size, inset):
    steps = 64
    small = Image.new('RGB', (steps, steps))
    for y in range(steps):
        for x in range(steps):
            u = min(1, max(0, (x / (steps - 1) - inset) / (1 - 2 * inset)))
            v = min(1, max(0, (y / (steps - 1) - inset) / (1 - 2 * inset))) ** 1.6
            top = [a + (b - a) * u for a, b in zip(corners[0], corners[1])]
            bottom = [a + (b - a) * u for a, b in zip(corners[2], corners[3])]
            small.putpixel((x, y), tuple(round(a + (b - a) * v) for a, b in zip(top, bottom)))
    return small.resize((size, size), Image.Resampling.BICUBIC).convert('RGBA')


for name, image in [('foreground', mark), ('monochrome', monochrome), ('background', gradient(512, 1 / 6))]:
    image.resize((512, 512), Image.Resampling.LANCZOS).save(res / f'drawable-nodpi/gimbo_calendar_{name}.png', optimize=True)
icon = gradient(512, 0)
icon.alpha_composite(mark.resize((768, 768), Image.Resampling.LANCZOS).crop((128, 128, 640, 640)))
icon.convert('RGB').save(output / 'icon-512.png')
rounded = icon.copy()
corner = Image.new('L', (2048, 2048), 0)
ImageDraw.Draw(corner).rounded_rectangle((0, 0, 2047, 2047), radius=464, fill=255)
rounded.putalpha(corner.resize((512, 512), Image.Resampling.LANCZOS))
rounded.save(output / 'icon-rounded-512.png')
for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    rounded.resize((size, size), Image.Resampling.LANCZOS).save(res / f'mipmap-{density}/ic_launcher.png')


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

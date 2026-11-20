#!/usr/bin/env python3
"""Compare unchanged UI Kit references with saved Compose captures using Pillow.

Pass the UI Kit v2 handoff directory, the Compose report directory and output.
Blue outlines are fitted reference bounds. Gold outlines are Compose layout bounds.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageOps


SCREENS = {
    "now-playing": (
        "01_now_playing", "now-playing-dark", "now-playing-compact",
        {"album_art": "mio-cover", "track_metadata": "mio-track-metadata",
         "transport": "mio-transport", "format_row": "mio-format"},
    ),
    "library": (
        "02_library", "library-dark", "library-compact",
        {"mini_player": "mio-mini-player", "bottom_nav": "mio-navigation"},
    ),
    "retro": (
        "03_retro_cassette", "retro-cassette", "retro-cassette-compact",
        {"hardware_housing": "mio-case", "cassette_window": "mio-window",
         "transport_keys": "mio-key-row"},
    ),
}


def compare(reference, actual, target):
    fitted = ImageOps.contain(reference, actual.size, Image.Resampling.LANCZOS)
    x = (actual.width - fitted.width) // 2
    y = (actual.height - fitted.height) // 2
    canvas = Image.new("RGB", actual.size, "#0d1116")
    canvas.paste(fitted, (x, y))
    side = Image.new("RGB", (actual.width * 2, actual.height))
    side.paste(canvas, (0, 0))
    side.paste(actual, (actual.width, 0))
    side.save(target / "side_by_side.png")
    Image.blend(canvas, actual, .5).save(target / "overlay_50pct.png")
    ImageEnhance.Contrast(ImageChops.difference(canvas, actual)).enhance(2).save(target / "difference.png")
    return x, y, fitted.width / reference.width, fitted.height / reference.height


def compare_geometry(actual, regions, bounds, pairs, fit, target, screen):
    x, y, sx, sy = fit
    sheet = Image.new("RGB", actual.size, "#121820")
    draw = ImageDraw.Draw(sheet)
    ref_color, actual_color = "#79b7ea", "#e7bd77"

    def reference_box(box):
        return [box[0] * sx + x, box[1] * sy + y, box[2] * sx + x, box[3] * sy + y]

    def label_box(box, color, label, offset):
        draw.rectangle(box, outline=color, width=1)
        draw.text((box[0] + 5, box[1] + offset), label, fill=color)

    for region, layout_tag in pairs.items():
        label_box(reference_box(regions[region]["px"]), ref_color, region, 3)
        for box in bounds[layout_tag]:
            label_box(box, actual_color, layout_tag.removeprefix("mio-"), 15)

    if screen == "library":
        # The handoff's aggregate grid includes labels. These are its six individual covers.
        for x0, x1 in [(39, 409), (443, 813)]:
            for y0, y1 in [(257, 617), (726, 1009), (1117, 1407)]:
                label_box(reference_box([x0, y0, x1, y1]), ref_color, "cover", 3)
        for box in bounds["mio-album-cover"]:
            label_box(box, actual_color, "cover", 15)
    draw.text((12, actual.height - 16), "Blue: reference   Gold: Compose", fill="#ffffff",
              stroke_width=2, stroke_fill="#121820")
    sheet.save(target / "geometry.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("handoff", type=Path)
    parser.add_argument("reports", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    regions = json.loads((args.handoff / "measurements/regions.json").read_text())
    for screen, (ref, main_capture, compact, pairs) in SCREENS.items():
        target = args.output / screen
        target.mkdir(parents=True, exist_ok=True)
        with Image.open(args.handoff / "references" / f"{ref}.png") as reference:
            for capture, name in [(main_capture, "actual"), (compact, "compact")]:
                # Keep the actual PNG byte-for-byte as produced by the Compose test.
                source = args.reports / f"{capture}.png"
                (target / f"{name}.png").write_bytes(source.read_bytes())
            actual = Image.open(target / "actual.png").convert("RGB")
            fit = compare(reference.convert("RGB"), actual, target)
            bounds = json.loads((args.reports / f"{main_capture}-geometry.json").read_text())
            compare_geometry(actual, regions[ref]["regions"], bounds, pairs, fit, target, screen)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate only 23 neutral preview PNGs; never build or touch runtime.

Why: independently drawn replacement media, not upstream art or templates.
Use: python tools/generate_preview_neutral_ui.py [--check] (Pillow required).
No network, fonts, or external assets. Baseline PNGs: IHDR/hash reads only.
All drawing and six metadata checks finish in memory before output writes.
--check is read-only and verifies deterministic bytes and provenance.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import platform
import struct
import subprocess
from PIL import Image, ImageDraw, PngImagePlugin, __version__ as PILLOW_VERSION

BASE = "4d9907d931e90c2be319d0ecc86e4edc7d14cd84"
ROOT = Path(__file__).resolve().parents[1]
GUI = "api/common/src/main/resources/assets/numen_api/textures/gui/"
SPRITES = GUI + "sprites/"
PROVENANCE = "tools/preview-neutral-ui-provenance.json"
WHITE, SLATE = (255, 255, 255, 255), (15, 23, 42, 255)
PALE, CYAN, CLEAR = (226, 232, 240, 255), (6, 182, 212, 255), (0, 0, 0, 0)
SPRITE_SIZES = {
    "card": (172, 40), "panel": (380, 232),
    "chevron_down": (11, 6), "chevron_up": (11, 6),
    "scroll_thumb": (4, 10), "scroll_track": (4, 8),
    "eye": (16, 16), "eye_off": (16, 16),
    "slot": (16, 16), "slot_alt": (16, 16),
    "food_empty": (9, 9), "food_full": (9, 9), "food_half": (9, 9),
    "heart_empty": (9, 9), "heart_full": (9, 9), "heart_half": (9, 9),
    "icon_compact": (11, 11), "icon_mic": (11, 11),
    "icon_send": (11, 11), "icon_stop": (11, 11),
}
SIZES = {
    "core/common/src/main/resources/numen.png": (512, 512),
    "api/common/src/main/resources/numen_api.png": (512, 512),
    GUI + "white.png": (4, 4),
    **{SPRITES + name + ".png": size for name, size in SPRITE_SIZES.items()},
}
OPAQUE = {"numen", "numen_api", "white", "card", "panel", "scroll_thumb", "scroll_track"}
SCALING = {
    "card": {"type": "nine_slice", "width": 172, "height": 40,
             "border": {"left": 2, "top": 15, "right": 2, "bottom": 2}},
    "panel": {"type": "stretch"},
    "scroll_thumb": {"type": "nine_slice", "width": 4, "height": 10,
                     "border": {"left": 1, "top": 2, "right": 1, "bottom": 2}},
    "scroll_track": {"type": "nine_slice", "width": 4, "height": 8, "border": 1},
    "slot": {"type": "nine_slice", "width": 16, "height": 16, "border": 2},
    "slot_alt": {"type": "nine_slice", "width": 16, "height": 16, "border": 2},
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def baseline(path):
    return subprocess.run(["git", "-C", str(ROOT), "show", BASE + ":" + path],
                          check=True, stdout=subprocess.PIPE).stdout


def dimensions(data):
    if data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
        raise ValueError("Invalid PNG IHDR")
    return struct.unpack(">II", data[16:24])


def draw(name, size):
    image = Image.new("RGBA", size, WHITE if name in OPAQUE else CLEAR)
    d = ImageDraw.Draw(image)
    w, h = size
    if name in {"numen", "numen_api"}:
        # Neutral frames and cross/dot; no text, character, or brand mark.
        d.rectangle((0, 0, w - 1, h - 1), fill=SLATE)
        d.rectangle((64, 64, 447, 447), outline=WHITE, width=12)
        d.rectangle((104, 104, 407, 407), outline=CYAN, width=8)
        if name == "numen":
            d.rectangle((240, 164, 271, 347), fill=WHITE)
            d.rectangle((164, 240, 347, 271), fill=WHITE)
        else:
            d.rectangle((208, 208, 303, 303), fill=WHITE)
        d.rectangle((232, 72, 279, 87), fill=CYAN)
    elif name == "white":
        pass  # Independently regenerated pure-white tint utility; new provenance chunk.
    elif name == "panel":
        d.rectangle((0, 0, w - 1, h - 1), outline=SLATE, width=2)
        d.rectangle((2, 2, w - 3, 17), fill=PALE)
        d.line((2, 18, w - 3, 18), fill=CYAN, width=2)
    elif name == "card":
        d.rectangle((0, 0, w - 1, h - 1), outline=SLATE, width=2)
        d.rectangle((2, 2, w - 3, 12), fill=PALE)
        d.line((2, 13, w - 3, 13), fill=CYAN, width=2)
    elif name in {"slot", "slot_alt"}:
        # All decoration inside fixed two-pixel border; transparent item center.
        d.rectangle((0, 0, 15, 15), outline=SLATE, width=2)
        d.rectangle((1, 1, 14, 14), outline=WHITE if name == "slot" else CYAN)
    elif name == "scroll_track":
        d.rectangle((0, 0, 3, 7), fill=SLATE)
        d.rectangle((1, 1, 2, 6), fill=PALE)
    elif name == "scroll_thumb":
        d.rectangle((0, 0, 3, 9), fill=SLATE)
        d.rectangle((1, 2, 2, 7), fill=CYAN)
    elif name.startswith("chevron_"):
        points = [(1, 1), (5, 5), (9, 1)] if name.endswith("down") else [(1, 4), (5, 0), (9, 4)]
        d.line(points, fill=SLATE, width=2)
    elif name in {"eye", "eye_off"}:
        d.polygon([(1, 7), (4, 4), (11, 4), (14, 7), (14, 8), (11, 11), (4, 11), (1, 8)], fill=SLATE)
        d.rectangle((4, 6, 11, 9), fill=WHITE)
        d.rectangle((7, 6, 8, 9), fill=SLATE)
        if name == "eye_off":
            d.line((2, 1, 13, 14), fill=WHITE, width=4)
            d.line((2, 1, 13, 14), fill=SLATE, width=2)
    elif name.startswith("food_") or name.startswith("heart_"):
        # Generic geometric food/health symbols; independent empty/half/full masks.
        points = ([(1, 2), (3, 1), (7, 1), (7, 6), (5, 7), (1, 7)] if name.startswith("food_")
                  else [(1, 2), (2, 1), (4, 3), (6, 1), (7, 2), (7, 4), (4, 7), (1, 4)])
        mask = Image.new("1", size, 0)
        ImageDraw.Draw(mask).polygon(points, fill=1)
        image.paste(SLATE, mask=mask)
        inner = Image.new("1", size, 0)
        for y in range(1, 8):
            for x in range(1, 8):
                if all(mask.getpixel((x + dx, y + dy)) for dx, dy in [(0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)]):
                    inner.putpixel((x, y), 1)
        image.paste(WHITE, mask=inner)
        for y in range(9):
            for x in range(9):
                if inner.getpixel((x, y)) and (name.endswith("full") or (name.endswith("half") and x <= 3)):
                    image.putpixel((x, y), CYAN)
    elif name == "icon_compact":
        d.line([(1, 4), (1, 1), (4, 1)], fill=SLATE, width=2)
        d.line([(6, 9), (9, 9), (9, 6)], fill=SLATE, width=2)
        d.line((3, 7, 7, 3), fill=SLATE, width=2)
    elif name == "icon_mic":
        d.rectangle((4, 1, 6, 5), fill=SLATE)
        d.line([(2, 4), (2, 6), (4, 8), (6, 8), (8, 6), (8, 4)], fill=SLATE)
        d.line((5, 8, 5, 10), fill=SLATE)
        d.line((3, 10, 7, 10), fill=SLATE)
    elif name == "icon_send":
        d.polygon([(1, 1), (9, 5), (1, 9), (3, 5)], fill=SLATE)
        d.line((4, 5, 6, 5), fill=WHITE)
    elif name == "icon_stop":
        d.rectangle((2, 2, 8, 8), fill=SLATE)
        d.rectangle((4, 4, 6, 6), fill=CYAN)
    else:
        raise ValueError("No drawing for " + name)
    return image


def encode(image):
    info = PngImagePlugin.PngInfo()
    info.add_text("Source", "New neutral geometry generated for this project; preview candidate")
    stream = io.BytesIO()
    image.save(stream, format="PNG", pnginfo=info, optimize=False, compress_level=9)
    return stream.getvalue()


def validate(data, path, size):
    with Image.open(io.BytesIO(data)) as image:
        image.load()  # Generated output only; no baseline PNG decoding.
        if image.size != size or image.mode != "RGBA":
            raise ValueError("Output size/mode mismatch: " + path)
        alpha = image.getchannel("A")
        lo, hi = alpha.getextrema()
        opaque = Path(path).stem in OPAQUE
        if (opaque and (lo, hi) != (255, 255)) or (not opaque and (lo, hi) != (0, 255)):
            raise ValueError("Alpha class mismatch: " + path)
        if any(alpha.histogram()[1:255]):
            raise ValueError("Unexpected partial alpha: " + path)
        return {"width": size[0], "height": size[1], "mode": image.mode,
                "alpha": "opaque" if opaque else "transparent glyph/frame", "alpha_extrema": [lo, hi]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    metadata = []
    for name, contract in SCALING.items():
        path = SPRITES + name + ".png.mcmeta"
        data = (ROOT / path).read_bytes()
        if data != baseline(path) or json.loads(data)["gui"]["scaling"] != contract:
            raise ValueError("Slicing metadata changed: " + path)
        metadata.append({"path": path, "sha256": digest(data), "scaling": contract})
    outputs, records = {}, []
    for path, size in SIZES.items():
        old = baseline(path)
        current = (ROOT / path).read_bytes()  # IHDR/hash only; not a reference image.
        if dimensions(old) != size or dimensions(current) != size:
            raise ValueError("Baseline/current dimensions mismatch: " + path)
        data = encode(draw(Path(path).stem, size))
        record = validate(data, path, size)
        if digest(old) == digest(data):
            raise ValueError("Output still matches baseline hash: " + path)
        if args.check and current != data:
            raise ValueError("Regeneration mismatch: " + path)
        outputs[path] = data
        records.append({"path": path, **record, "bytes": len(data),
                        "baseline_sha256": digest(old), "sha256": digest(data)})
    evidence = {
        "status": "OFFLINE SOURCE-ONLY CANDIDATE; not runtime/UI/legal validated",
        "base_commit": BASE,
        "generator": "tools/generate_preview_neutral_ui.py",
        "generator_command": "python tools/generate_preview_neutral_ui.py",
        "check_command": "python tools/generate_preview_neutral_ui.py --check",
        "tested_environment": {"python": platform.python_version(), "Pillow": PILLOW_VERSION},
        "origin": "All 23 outputs independently drawn from new simple geometry on blank RGBA canvases. No upstream pixels decoded, copied, traced, or imitated; only baseline IHDR dimensions and file hashes read. No upstream names, logos, skins, or art drawn. Pure-white utility independently regenerated for tinting.",
        "rights": "Generated outputs for this project. User ownership is not waived or reassigned; no CC0 or other new license selected. Existing source and upstream asset licenses remain unchanged. No written authorization from upstream or blanket legal approval claimed. This provenance is not distribution clearance.",
        "scope": "Only 23 listed release PNGs, two displayName fields, this generator and provenance. Resource names/IDs stay for compatibility; no logic, dependencies, authors, version, license or Gradle properties changed. No Java/Gradle/build/deploy/game/config actions.",
        "verification": "All 23 size/RGBA/hash/alpha checks passed; six unchanged mcmeta contracts checked before drawing. Panel uses stretch; nine-slice frame decoration stays within fixed borders. Runtime packing, in-game readability and legal release review remain unvalidated.",
        "outputs": records,
        "unchanged_metadata": metadata,
    }
    text = json.dumps(evidence, indent=2, ensure_ascii=False) + "\n"
    if args.check:
        if (ROOT / PROVENANCE).read_text(encoding="utf-8") != text:
            raise ValueError("Provenance mismatch")
    else:
        for path, data in outputs.items():
            (ROOT / path).write_bytes(data)
        (ROOT / PROVENANCE).write_text(text, encoding="utf-8", newline="\n")
    # Re-open actual outputs rather than reporting only in-memory intentions.
    for path, size in SIZES.items():
        actual = (ROOT / path).read_bytes()
        validate(actual, path, size)
        if actual != outputs[path]:
            raise ValueError("Written output mismatch: " + path)
    print(json.dumps({"status": "PASS", "mode": "check" if args.check else "generate",
                      "png_count": len(outputs), "unchanged_mcmeta": len(metadata), "provenance": PROVENANCE}))


if __name__ == "__main__":
    main()

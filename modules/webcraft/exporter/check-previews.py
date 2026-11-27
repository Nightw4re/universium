"""Audit the static catalog previews in a WebCraft ZIP. Requires Pillow."""

import argparse
from collections import Counter
from functools import lru_cache
from io import BytesIO
import json
from zipfile import ZipFile

from PIL import Image
from preview_atlas import GRID, IMAGE_SIZE, TILE_SIZE, image_path, read_atlas_index


def is_missing_texture(bounding_image):
    """Recognize the four solid black and magenta quadrants of Minecraft's placeholder."""
    width, height = bounding_image.size
    if width < 4 or height < 4 or width % 2 or height % 2:
        return False
    colors = []
    for y in (0, height // 2):
        for x in (0, width // 2):
            quadrant = bounding_image.crop((x, y, x + width // 2, y + height // 2))
            channels = quadrant.getextrema()
            if channels[3] != (255, 255) or any(low != high for low, high in channels):
                return False
            red, green, blue, _ = (low for low, _ in channels)
            if red == green == blue == 0:
                colors.append("black")
            elif green == 0 and red == blue and red > 0:
                colors.append("magenta")
            else:
                return False
    return colors[0] == colors[3] and colors[1] == colors[2] and colors[0] != colors[1]


def load_image(archive, name, size):
    with Image.open(BytesIO(archive.read(name))) as source:
        return source.convert("RGBA") if source.size == (size, size) else None


def check_image(archive, names, key, sprite, load_sheet):
    try:
        for extension in ("webp", "png"):
            name = f"previews/{key}.{extension}"
            if name in names:
                image = load_image(archive, name, TILE_SIZE)
                break
        else:
            if sprite is None:
                return "missing_image_file"
            sheet, tile = sprite
            image = load_sheet(sheet)
            if image is not None:
                x, y = tile % GRID * TILE_SIZE, tile // GRID * TILE_SIZE
                image = image.crop((x, y, x + TILE_SIZE, y + TILE_SIZE))
    except KeyError:
        return "missing_image_file"
    except (OSError, ValueError, Image.DecompressionBombError):
        return "invalid_image"
    if image is None:
        return "unexpected_image_size"
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        return "transparent_image"
    return "missing_texture" if is_missing_texture(image.crop(bounds)) else None


def audit(path, selected):
    with ZipFile(path) as archive:
        names = set(archive.namelist())
        hashes, sprites = read_atlas_index(archive, names)

        @lru_cache(maxsize=4)
        def load_sheet(index):
            return load_image(archive, image_path(hashes[index]), IMAGE_SIZE)

        items = json.loads(archive.read("catalog.json"))
        diagnostics = {}
        if "export-diagnostics.json" in names:
            report = json.loads(archive.read("export-diagnostics.json"))
            diagnostics = {(issue["id"], issue.get("variant")): issue["reason"]
                           for issue in report.get("missingImages", []) if issue["kind"] == "item"}
        issues = []
        counts = Counter()
        cache = {}
        seen = set()
        for row in items:
            item = row["id"]
            if selected and item not in selected:
                continue
            seen.add(item)
            for entry in [row, *row.get("variants", [])]:
                variant = None if entry is row else entry["id"]
                counts["checked"] += 1
                key = entry.get("previewKey")
                if key and key not in cache:
                    cache[key] = check_image(archive, names, key, sprites.get(key), load_sheet)
                reason = cache[key] if key else diagnostics.get((item, variant), "missing_preview_key")
                if reason:
                    issue = {"id": item, "reason": reason}
                    if variant is not None:
                        issue["variant"] = variant
                    issues.append(issue)
                    counts[reason] += 1
        for item in sorted(selected - seen):
            issues.append({"id": item, "reason": "absent_from_catalog"})
            counts["absent_from_catalog"] += 1
        return {"counts": dict(counts), "issues": issues}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="WebCraft previews.zip")
    parser.add_argument("items", nargs="*", help="Optional item IDs. Omit to audit the entire catalog.")
    args = parser.parse_args()
    report = audit(args.archive, set(args.items))
    print(json.dumps(report, indent=2))
    return int(bool(report["issues"]))


if __name__ == "__main__":
    raise SystemExit(main())

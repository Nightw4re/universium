"""Read and validate the optional static preview atlas index."""

from hashlib import sha256
import json
import re


INDEX = "previews/atlas-index.json"
TILE_SIZE = 64
GRID = 10
IMAGE_SIZE = TILE_SIZE * GRID
HASH = re.compile(r"[a-f0-9]{64}")


def image_path(digest):
    return f"previews/atlases/{digest}.webp"


def read_atlas_index(archive, names):
    if INDEX not in names:
        return [], {}
    if archive.getinfo(INDEX).file_size > 32 * 1024 * 1024:
        raise ValueError("Preview atlas index exceeds the supported size.")
    index = json.loads(archive.read(INDEX).decode("utf-8"))
    geometry = {"version": 1, "tileSize": TILE_SIZE, "columns": GRID, "rows": GRID}
    if not isinstance(index, dict) or any(
            type(index.get(key)) is not int or index[key] != value for key, value in geometry.items()):
        raise ValueError("Unsupported preview atlas layout.")
    hashes, sprites = index.get("atlases"), index.get("sprites")
    if not isinstance(hashes, list) or not all(isinstance(digest, str) and HASH.fullmatch(digest) for digest in hashes):
        raise ValueError("Invalid preview atlas hashes.")
    if not isinstance(sprites, dict):
        raise ValueError("Invalid preview atlas sprite map.")
    for key, location in sprites.items():
        if (not HASH.fullmatch(key) or not isinstance(location, list) or len(location) != 2
                or any(type(index) is not int for index in location)
                or not 0 <= location[0] < len(hashes) or not 0 <= location[1] < GRID * GRID):
            raise ValueError(f"Invalid preview atlas location for {key}.")
    return hashes, sprites


def validate_atlas(archive, names):
    hashes, sprites = read_atlas_index(archive, names)
    for digest in hashes:
        path = image_path(digest)
        if path not in names or archive.getinfo(path).file_size > 4 * 1024 * 1024:
            raise ValueError(f"Missing or oversized preview atlas sheet: {path}")
        data = archive.read(path)
        chunk_size = int.from_bytes(data[16:20], "little")
        dimensions = int.from_bytes(data[21:25], "little")
        if (len(data) < 26 or data[:4] != b"RIFF" or data[8:16] != b"WEBPVP8L" or data[20] != 0x2f
                or int.from_bytes(data[4:8], "little") != len(data) - 8
                or chunk_size <= 5 or chunk_size + (chunk_size & 1) != len(data) - 20
                or (dimensions & 0xefffffff) != ((IMAGE_SIZE - 1) | ((IMAGE_SIZE - 1) << 14))
                or sha256(data).hexdigest() != digest):
            raise ValueError(f"Invalid lossless WebP preview atlas sheet: {path}")
    for key in sprites:
        if any(f"{directory}/{key}.{extension}" in names
               for directory in ("previews", "animations") for extension in ("webp", "png")):
            raise ValueError(f"Preview {key} has both an atlas tile and a standalone attachment.")

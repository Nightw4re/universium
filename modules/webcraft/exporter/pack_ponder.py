"""Pack a captured compact Ponder ZIP for Capycraft before publishing it."""

from base64 import urlsafe_b64encode
from collections import Counter, defaultdict
from functools import lru_cache
from hashlib import sha256
import json
import math
from pathlib import Path
import re
import struct
import tempfile
from time import monotonic
import zlib
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


DEFINITIONS = "ponder/definitions.json"
MODELS = "ponder/block-models.json"
DIAGNOSTICS = "export-diagnostics.json"
SMALL_MESH = 1024
BUNDLE_LIMIT = 4 * 1024**2
STREAM_MESH_LIMIT = 1024**2
PLAYBACK_TICKS = 16
PICKING_TICKS = 64
MESH = re.compile(r"ponder/meshes/([a-f0-9]{64})\.bin\Z")
ATLAS_ANIMATION = re.compile(r"ponder/textures/[a-f0-9]{64}\.json\Z")
ATLAS_SHEET = re.compile(r"ponder/textures/[a-f0-9]{64}\.png\Z")
RIGID_BATCH = re.compile(r"ponder/rigid-batches/v([12345])/[a-f0-9]{64}\.bin\Z")
RIGID_GLINT = re.compile(r"ponder/rigid-glint/v1/[a-f0-9]{64}\.bin\Z")
MIXED_BOX_BATCH = re.compile(r"ponder/box-faces/v4/[a-f0-9]{64}\.bin\Z")
BUNDLED_GEOMETRY = re.compile(r"ponder/(particle-quads/v[1234]|outline-cuboids/v1|box-faces/v[1234]|rigid-batches/v[12345]|rigid-glint/v1|lightning/v1)/([a-f0-9]{64})\.bin\Z")
BUNDLE_CODECS = {codec: index for index, codec in enumerate((
    "", "particle-quads/v1", "particle-quads/v2", "outline-cuboids/v1", "box-faces/v1",
    "rigid-batches/v1", "rigid-batches/v2", "rigid-batches/v3", "rigid-batches/v4", "rigid-glint/v1", "box-faces/v2",
    "lightning/v1", "rigid-batches/v5", "particle-quads/v3", "particle-quads/v4", "box-faces/v3", "box-faces/v4",
))}
RIGID_POSE_BYTES = 130
RIGID_ATTRIBUTE_DIGEST_BYTES = 32
RIGID_ATTRIBUTE_VERTEX_BYTES = 12

# These are the draw formats accepted by both Capycraft renderers.
COLOR_SHADERS = {"position", "position_color", "rendertype_gui", "rendertype_gui_overlay",
                 "rendertype_gui_text_highlight", "rendertype_text_background", "rendertype_text_background_see_through",
                 "rendertype_lightning"}
TEXTURED_SHADERS = {
    "position_tex", "position_tex_color", "position_color_tex", "position_tex_lightmap_color", "particle",
    "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent",
    "rendertype_translucent_moving_block", "rendertype_text", "rendertype_text_see_through",
    "rendertype_text_polygon_offset", "rendertype_entity_solid", "rendertype_entity_cutout",
    "rendertype_entity_cutout_no_cull", "rendertype_entity_cutout_no_cull_z_offset",
    "rendertype_entity_translucent", "rendertype_entity_translucent_cull",
    "rendertype_entity_no_outline", "rendertype_eyes",
    "rendertype_glint", "rendertype_armor_cutout_no_cull", "rendertype_crumbling",
    "neoforge:rendertype_entity_unlit_translucent",
}
DRAW_MODES = {"QUADS", "TRIANGLES", "TRIANGLE_STRIP", "TRIANGLE_FAN", "DEBUG_LINES", "DEBUG_LINE_STRIP"}
WIDE_LINE_MODES = {"LINES", "LINE_STRIP"}
TIMELINE_LISTS = ("layout", "movingLayout", "scene", "overlay", "fluidFaces")
TIMELINE_VECTORS = {"transform": 16, "modelView": 16, "projection": 16, "textureMatrix": 16, "color": 4}
IDENTITY_TRANSFORM = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
LAYOUT_LANES = ("transform", "blocks", "textureOffsets")
LAYOUT_PALETTES = ("blocks", "textureOffsets")
DRAW_PALETTES = ("attributes", "textures", "modelView", "projection", "textureMatrix", "color", "blendFactors", "vertices", "indices")
ROTATION_PLANES = ((0, 1, 4, 5), (0, 2, 8, 10), (5, 6, 9, 10))


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def encode_vector(key, before, current):
    length = TIMELINE_VECTORS.get(key)
    if length is None or not isinstance(before, list) or not isinstance(current, list) \
            or len(before) != length or len(current) != length:
        return current
    if not all(type(value) in (int, float) and math.isfinite(value) for values in (before, current) for value in values):
        return current
    mask = 0
    edits = []
    for index, (old, value) in enumerate(zip(before, current)):
        if value != old or (value == 0 and math.copysign(1, value) != math.copysign(1, old)):
            mask |= 1 << index
            edits.append(value)
    delta = {"d": [mask, *edits]}
    return delta if edits and len(json_bytes(delta)) < len(json_bytes(current)) else current


def map_timeline_transforms(patches, replace):
    result = []
    for patch in patches:
        fields = dict(patch)
        for name in ("layout", "movingLayout", "fluidFaces"):
            if name not in fields:
                continue
            value = fields[name]
            fields[name] = ({**value, "edits": [[slot, replace(entry, name), *tail]
                            for slot, entry, *tail in value["edits"]]} if isinstance(value, dict)
                            else [replace(entry, name) for entry in value])
        result.append(fields)
    return result


def share_timeline_transforms(timeline):
    occurrences = Counter()
    for patch in timeline["patches"]:
        for name in ("layout", "movingLayout", "fluidFaces"):
            value = patch.get(name, [])
            entries = (edit[1] for edit in value["edits"]) if isinstance(value, dict) else value
            for entry in entries:
                if "transform" in entry:
                    occurrences[json_bytes(entry["transform"])] += 1

    palette = []
    references = {}
    for key, count in occurrences.items():
        if len(palette) >= 8192:
            break
        reference = len(palette)
        if (count - 1) * len(key) <= count * len(str(reference)) + 1:
            continue
        palette.append(json.loads(key))
        references[key] = reference
    if not palette:
        return timeline

    def replace(entry, _name):
        reference = references.get(json_bytes(entry["transform"])) if "transform" in entry else None
        return entry if reference is None else {**entry, "transform": reference}

    return {"version": 3, "transforms": palette, "patches": map_timeline_transforms(timeline["patches"], replace)}


def share_layout_values(timeline):
    occurrences = {lane: Counter() for lane in LAYOUT_PALETTES}
    for patch in timeline["patches"]:
        for name in ("layout", "movingLayout"):
            value = patch.get(name, [])
            entries = (edit[1] for edit in value["edits"]) if isinstance(value, dict) else value
            for entry in entries:
                for lane in LAYOUT_PALETTES:
                    if isinstance(entry.get(lane), list):
                        occurrences[lane][json_bytes(entry[lane])] += 1

    palettes = {}
    references = {}
    for lane, values in occurrences.items():
        palette = []
        indices = {}
        for key, count in values.items():
            index = len(palette)
            if index >= 8192:
                break
            if (count - 1) * len(key) <= count * len(str(index)) + 1:
                continue
            palette.append(json.loads(key))
            indices[key] = index
        if palette:
            palettes[lane] = palette
            references[lane] = indices
    if not palettes:
        return timeline

    def replace(entry, name):
        if name == "fluidFaces":
            return entry
        fields = dict(entry)
        for lane, indices in references.items():
            if isinstance(entry.get(lane), list):
                fields[lane] = indices.get(json_bytes(entry[lane]), entry[lane])
        return fields

    return {**timeline, "version": 6, "layoutValues": palettes,
            "patches": map_timeline_transforms(timeline["patches"], replace)}


def binary_matrix_edit(values):
    mask, *lanes = values
    result = bytearray(struct.pack("<H", mask))
    fixed = {}
    fields = {}
    for index, value in zip((index for index in range(16) if mask & 1 << index), lanes):
        if abs(value) > 2147.483647 or (value == 0 and math.copysign(1, value) < 0):
            return None
        integer = round(value * 1_000_000)
        if integer / 1_000_000 != value:
            return None
        fixed[index] = integer
        start = len(result)
        zigzag = (abs(integer) << 1) - (integer < 0)
        while zigzag >= 128:
            result.append((zigzag & 127) | 128)
            zigzag >>= 7
        result.append(zigzag)
        fields[index] = result[start:]
    token = urlsafe_b64encode(result).rstrip(b"=").decode("ascii")
    for plane, (x, y, opposite, diagonal) in enumerate(ROTATION_PLANES):
        if not all(index in fixed for index in (x, y, opposite, diagonal)) \
                or fixed[x] != fixed[diagonal] or fixed[y] != -fixed[opposite]:
            continue
        reduced = mask ^ (1 << opposite | 1 << diagonal)
        data = struct.pack("<H", reduced) + b"".join(
            field for index, field in fields.items() if reduced & 1 << index
        )
        candidate = f".{plane}" + urlsafe_b64encode(data).rstrip(b"=").decode("ascii")
        if len(candidate) < len(token):
            token = candidate
    return token if len(json_bytes(token)) < len(json_bytes(values)) else None


def compact_layout_values(timeline, binary=False):
    changed = False
    packed_matrices = False
    packed_rotation = False

    def encode_edit(key, values):
        nonlocal packed_matrices, packed_rotation
        token = binary_matrix_edit(values) if binary else None
        if token is not None:
            packed_matrices = True
            packed_rotation |= token.startswith(".")
            return {key: token}
        return {key: values}

    def pack(value):
        nonlocal changed
        if binary and isinstance(value, dict) and list(value) == ["d"] and isinstance(value["d"], list):
            result = encode_edit("d", value["d"])
            if isinstance(result["d"], str):
                changed = True
                return result
        if not isinstance(value, list) or len(value) != 16 \
                or not all(type(lane) in (int, float) and math.isfinite(lane) for lane in value):
            return value
        edits = [(index, lane) for index, lane in enumerate(value)
                 if lane != IDENTITY_TRANSFORM[index] or (lane == 0 and math.copysign(1, lane) < 0)]
        packed = encode_edit("a", [sum(1 << index for index, _ in edits), *(lane for _, lane in edits)])
        if len(json_bytes(packed)) >= len(json_bytes(value)):
            return value
        changed = True
        return packed

    def replace(entry, name):
        nonlocal changed
        value = entry.get("transform")
        packed = pack(value)
        result = entry if packed is value else {**entry, "transform": packed}
        if name == "fluidFaces" or any(key not in LAYOUT_LANES or lane is None for key, lane in result.items()):
            return result
        slots = [result.get(key) for key in LAYOUT_LANES]
        while slots and slots[-1] is None:
            slots.pop()
        if not slots or len(json_bytes(slots)) >= len(json_bytes(result)):
            return result
        changed = True
        return slots

    result = {**timeline, "version": max(4, timeline["version"]),
              "patches": map_timeline_transforms(timeline["patches"], replace)}
    if "transforms" in result:
        result["transforms"] = [pack(value) for value in timeline["transforms"]]
    if packed_matrices and changed:
        result["version"] = 9 if packed_rotation else 7
    return result if changed else timeline


def share_draw_values(document):
    timeline = {"patches": document} if isinstance(document, list) else document
    occurrences = {lane: Counter() for lane in DRAW_PALETTES}

    def draw_entries(value):
        return (edit[1] for edit in value["edits"]) if isinstance(value, dict) else value

    for patch in timeline["patches"]:
        for field in ("scene", "overlay"):
            for draw in draw_entries(patch.get(field, [])):
                for lane in DRAW_PALETTES:
                    value = draw.get(lane)
                    if isinstance(value, (list, str)) or lane == "textures" and isinstance(value, dict):
                        occurrences[lane][json_bytes(value)] += 1
    palettes, references = {}, {}
    for lane, counts in occurrences.items():
        values, indices = [], {}
        for key, count in counts.items():
            if len(values) == 8192:
                break
            if (count - 1) * len(key) <= count * len(str(len(values))) + 1:
                continue
            indices[key] = len(values)
            values.append(json.loads(key))
        if values:
            palettes[lane] = values
            references[lane] = indices
    if not palettes:
        return document

    def replace(draw):
        return {lane: references[lane].get(json_bytes(value), value) if lane in references else value
                for lane, value in draw.items()}

    def replace_field(value):
        if isinstance(value, dict):
            return {**value, "edits": [[slot, replace(draw), *tail] for slot, draw, *tail in value["edits"]]}
        return [replace(draw) for draw in value]

    return {**timeline, "version": max(8, timeline.get("version", 0)), "drawValues": palettes, "patches": [
        {field: replace_field(value) if field in ("scene", "overlay") else value for field, value in patch.items()}
        for patch in timeline["patches"]
    ]}


def pack_moving_edits(document):
    """Store positional edits without repeating the list and field names at every tick."""
    if not isinstance(document, dict):
        return document
    patches = []
    changed = False
    for patch in document["patches"]:
        moving = patch.get("movingLayout")
        if not isinstance(moving, dict):
            patches.append(patch)
            continue
        edits = [moving["length"]]
        for entry in moving["edits"]:
            slot, fields, *removed = entry
            if not removed and isinstance(fields, list):
                if len(fields) == 1 and type(fields[0]) is int and fields[0] >= 0:
                    entry = [slot, fields[0]]
                elif len(fields) == 1 and isinstance(fields[0], dict) and list(fields[0]) == ["d"] \
                        and isinstance(fields[0]["d"], str):
                    entry = [slot, fields[0]["d"]]
                elif len(fields) == 3 and fields[:2] == [None, None] and type(fields[2]) is int and fields[2] >= 0:
                    entry = [slot, -1 - fields[2]]
            edits.append(entry)
        patches.append({**patch, "movingLayout": edits})
        changed = True
    return {**document, "version": 10, "patches": patches} if changed else document


def encode_timeline(patches, local_refs=False):
    previous = {}
    encoded = []
    version = 1
    for patch in patches:
        fields = dict(patch)
        for name in TIMELINE_LISTS:
            if name not in patch:
                continue
            current = patch[name]
            before = previous.get(name)
            previous[name] = current
            if before is None:
                continue
            edits = []
            vector_edits = False
            for slot, item in enumerate(current):
                if slot >= len(before):
                    edits.append([slot, item])
                    continue
                old = before[slot]
                changes = {}
                for key, value in item.items():
                    prior = old.get(key)
                    if key in old and prior == value:
                        continue
                    encoded_value = encode_vector(key, prior, value)
                    vector_edits |= encoded_value is not value
                    changes[key] = encoded_value
                removed = [key for key in old if key not in item]
                if changes or removed:
                    edits.append([slot, changes, removed] if removed else [slot, changes])
            delta = {"length": len(current), "edits": edits}
            if len(json_bytes(delta)) < len(json_bytes(current)):
                fields[name] = delta
                if vector_edits:
                    version = 2
        encoded.append(fields)
    full = json_bytes({"version": 5, "patches": patches} if local_refs else patches)
    timeline = {"version": version, "patches": encoded}
    shared_timeline = share_timeline_transforms(timeline)
    candidates = [timeline] if shared_timeline is timeline else [timeline, shared_timeline]
    layouts = []
    for candidate in candidates:
        layouts.append(candidate)
        shared = share_layout_values(candidate)
        if shared is not candidate:
            layouts.append(shared)
    compact = [json_bytes({**candidate, "version": max(5, candidate["version"])} if local_refs else candidate)
               for layout in layouts
               for candidate in (layout, compact_layout_values(layout), compact_layout_values(layout, binary=True))]
    result = min(full, *compact, key=len)
    document = json.loads(result)
    shared = share_draw_values(document)
    compressed_length = None
    if shared is not document:
        packed = json_bytes(shared)
        if len(packed) < len(result):
            compressed_length = len(zlib.compress(result))
            packed_length = len(zlib.compress(packed))
            if packed_length <= compressed_length:
                result, document, compressed_length = packed, shared, packed_length
    moving = pack_moving_edits(document)
    if moving is not document:
        packed = json_bytes(moving)
        if len(packed) < len(result):
            if compressed_length is None:
                compressed_length = len(zlib.compress(result))
            if len(zlib.compress(packed)) <= compressed_length:
                return packed
    return result


def share_definitions(definitions):
    scenes = []
    ids = {}
    for registration in definitions["registrations"]:
        scene = registration.get("scene")
        if scene is None:
            continue
        key = json_bytes(scene)
        identifier = ids.setdefault(key, len(scenes))
        if identifier == len(scenes):
            scenes.append(scene)
        registration["scene"] = identifier
    if scenes:
        definitions["sceneDefinitions"] = scenes


def supported_draw(draw):
    attrs = {a["name"]: a for a in draw["attributes"]}
    position = attrs.get("Position", {})
    if position.get("type") != "FLOAT" or position.get("count") != 3:
        return False
    if draw["shader"] == "rendertype_lines":
        width = draw.get("lineWidth")
        vertices = draw["vertexCount"]
        mode = draw["mode"]
        return (mode in WIDE_LINE_MODES and attrs.get("Normal", {}).get("count") == 3
                and type(width) in (int, float) and math.isfinite(width) and width > 0
                and vertices >= 4 and vertices % (4 if mode == "LINES" else 2) == 0
                and ("indices" in draw or draw["indexCount"] == (vertices // 4 * 6 if mode == "LINES" else vertices)))
    if draw["mode"] not in DRAW_MODES:
        return False
    if draw["mode"] == "QUADS" and "indices" not in draw:
        vertices = draw["vertexCount"]
        if vertices % 4 or draw["indexCount"] != vertices // 4 * 6:
            return False
    shader = draw["shader"]
    return shader in COLOR_SHADERS or (shader in TEXTURED_SHADERS and "0" in draw["textures"]
                                        and attrs.get("UV0", {}).get("count") == 2)


class PickingChunk:
    def __init__(self):
        self.shapes = []
        self.placements = []
        self.frames = []
        self.shape_ids = {}
        self.placement_ids = {}
        self.previous = []

    def placement(self, block):
        shape = {k: v for k, v in block.items() if k != "position"}
        key = json_bytes(shape)
        if key not in self.shape_ids:
            self.shape_ids[key] = len(self.shapes)
            self.shapes.append(shape)
        placement = (*block["position"], self.shape_ids[key])
        if placement not in self.placement_ids:
            self.placement_ids[placement] = len(self.placements)
            self.placements.append(placement)
        return self.placement_ids[placement]

    def append(self, offset, groups):
        current = [(group["transform"], [self.placement(block) for block in group["blocks"]]) for group in groups]
        changes = []
        for index, (transform, blocks) in enumerate(current):
            before_transform, before_blocks = self.previous[index] if index < len(self.previous) else (None, [])
            patch = {}
            if transform != before_transform:
                patch["transform"] = transform
            if len(blocks) != len(before_blocks):
                patch["length"] = len(blocks)
            edits = [[slot, block] for slot, block in enumerate(blocks)
                     if slot >= len(before_blocks) or before_blocks[slot] != block]
            if edits:
                patch["blocks"] = edits
            if patch:
                changes.append([index, patch])
        if changes or len(current) != len(self.previous):
            self.frames.append([offset, len(current), changes])
        self.previous = current

    def encode(self):
        return json_bytes({"shapes": self.shapes, "placements": self.placements, "frames": self.frames})


def scene_timelines(original, scenes):
    for scene in scenes:
        manifest = scene["manifest"]
        folder = manifest.rsplit("/", 1)[0]
        count = json.loads(original.read(manifest))["frameCount"]
        yield scene, folder, count


def rigid_row_size(version):
    return {"1": RIGID_POSE_BYTES, "2": RIGID_POSE_BYTES + RIGID_ATTRIBUTE_DIGEST_BYTES,
            "3": 112, "4": RIGID_POSE_BYTES + 2 * RIGID_ATTRIBUTE_DIGEST_BYTES, "glint": 82}[version]


def unpack_sparse_poses(batch):
    if len(batch) < 7 or batch[0] not in (1, 2, 4):
        raise ValueError("Invalid sparse Ponder pose header")
    version = str(batch[0])
    stride = rigid_row_size(version)
    count, = struct.unpack_from("<H", batch, 1)
    if not 1 <= count <= 512 or len(batch) >= 2 + count * stride:
        raise ValueError("Invalid sparse Ponder pose count or size")
    defaults = bytearray(stride)
    for lane in (34, 50, 66, 82, 98, 114):
        struct.pack_into("<f", defaults, lane, 1)
    boundaries = [0, *range(34, RIGID_POSE_BYTES, 4), *range(RIGID_POSE_BYTES, stride + 1, 32)]
    lanes = len(boundaries) - 1
    result = bytearray(batch[1:3])
    cursor = 3
    for row in range(count):
        if cursor + 4 > len(batch):
            raise ValueError("Truncated sparse Ponder pose mask")
        mask, = struct.unpack_from("<I", batch, cursor)
        cursor += 4
        previous = mask >> 31
        if (previous and not row) or mask & ~((1 << 31) | ((1 << lanes) - 1)):
            raise ValueError("Invalid sparse Ponder pose mask")
        pose = result[-stride:] if previous else defaults[:]
        for lane in range(lanes):
            if not mask & (1 << lane):
                continue
            start, end = boundaries[lane:lane + 2]
            next_cursor = cursor + end - start
            if next_cursor > len(batch):
                raise ValueError("Truncated sparse Ponder pose")
            pose[start:end] = batch[cursor:next_cursor]
            cursor = next_cursor
        result.extend(pose)
    if cursor != len(batch):
        raise ValueError("Unexpected bytes after sparse Ponder poses")
    return result, version


def rigid_assets(original, path, version):
    size = original.getinfo(path).file_size
    max_stride = rigid_row_size("4" if version == "5" else version)
    if not 2 < size <= 2 + 512 * max_stride:
        raise ValueError(f"Invalid rigid Ponder batch size: {path}")
    return rigid_batch_assets(original, path, version, original.read(path))


def rigid_batch_assets(original, path, version, batch):
    size = len(batch)
    max_stride = rigid_row_size("4" if version == "5" else version)
    if not 2 < size <= 2 + 512 * max_stride:
        raise ValueError(f"Invalid rigid Ponder batch size: {path}")
    if version == "5":
        batch, version = unpack_sparse_poses(batch)
        size = len(batch)
    entry_size = rigid_row_size(version)
    count, = struct.unpack_from("<H", batch)
    if count < 1 or size != 2 + count * entry_size:
        raise ValueError(f"Invalid rigid Ponder batch: {path}")
    assets = set()
    for start in range(2, size, entry_size):
        part = f"ponder/rigid-parts/v1/{batch[start:start + 32].hex()}.bin"
        part_size = original.getinfo(part).file_size
        assets.add(part)
        if version == "1":
            continue
        vertices, = struct.unpack_from("<H", batch, start + 32)
        if not 4 <= vertices <= 4096 or vertices % 4:
            raise ValueError(f"Invalid rigid Ponder part vertex count: {path}")
        if version in ("3", "4", "glint") and part_size != vertices * 24:
            raise ValueError(f"Invalid rigid Ponder part size: {part}")
        if version == "glint":
            continue
        if version == "3":
            if vertices > 24:
                raise ValueError(f"Invalid rigid Ponder cuboid part size: {part}")
            continue
        attributes_at = start + RIGID_POSE_BYTES
        normals_at = attributes_at + RIGID_ATTRIBUTE_DIGEST_BYTES
        digest = batch[attributes_at:normals_at]
        if any(digest):
            attributes = f"ponder/rigid-attributes/v1/{digest.hex()}.bin"
            if original.getinfo(attributes).file_size != vertices * RIGID_ATTRIBUTE_VERTEX_BYTES:
                raise ValueError(f"Invalid rigid Ponder vertex attributes size: {attributes}")
            assets.add(attributes)
        if version == "4":
            normal_digest = batch[normals_at:start + entry_size]
            if any(normal_digest):
                normals = f"ponder/rigid-normals/v1/{normal_digest.hex()}.bin"
                normal_size = original.getinfo(normals).file_size
                if not 3 <= normal_size <= vertices * 3 or normal_size % 3:
                    raise ValueError(f"Invalid rigid Ponder normal palette size: {normals}")
                assets.add(normals)
    return assets


def mixed_box_assets(original, path):
    if not 2 < original.getinfo(path).file_size <= 1024 * 1024:
        raise ValueError(f"Invalid mixed Ponder face size: {path}")
    data = original.read(path)
    count, = struct.unpack_from("<H", data)
    if not 1 <= count <= 4096:
        raise ValueError(f"Invalid mixed Ponder face run count: {path}")
    cursor = 2
    quads = 0
    assets = set()
    for _ in range(count):
        if cursor + 6 > len(data):
            raise ValueError(f"Truncated mixed Ponder face run: {path}")
        header, size = struct.unpack_from("<HI", data, cursor)
        kind, run_quads = header & 0xc000, header & 0x3fff
        quads += run_quads
        cursor += 6
        end = cursor + size
        if not run_quads or quads > 4096 or not size or end > len(data) or kind == 0xc000:
            raise ValueError(f"Invalid mixed Ponder face run: {path}")
        if kind == 0x4000:
            version = data[cursor]
            if version not in (1, 2, 4, 5):
                raise ValueError(f"Invalid nested rigid Ponder version: {path}")
            assets.update(rigid_batch_assets(original, path, str(version), data[cursor + 1:end]))
        elif kind == 0 and size != run_quads * 4 * 36:
            raise ValueError(f"Invalid mixed Ponder vertex payload: {path}")
        cursor = end
    if cursor != len(data):
        raise ValueError(f"Unexpected bytes after mixed Ponder face runs: {path}")
    return assets


def collect_resources(original, scenes, models, sprite_animations, checkpoint):
    shared = {draw["vertices"] for model in models for draw in model}
    used = set(shared)
    used.update(image for model in models for draw in model for image, _ in draw.get("frames", []))
    used.update(image for animation in sprite_animations for image, _ in animation["frames"])
    candidates = {}
    vertex_strides = {}
    standalone = set(shared)
    rigid_batches = set()
    atlas_animations = set()
    excluded_draws = {}
    captured_vertices = {}

    def retain_texture(texture):
        used.add(texture["image"])
        animation = texture.get("atlasAnimation")
        if animation is None or animation in atlas_animations:
            return
        if not ATLAS_ANIMATION.fullmatch(animation):
            raise ValueError(f"Invalid Ponder atlas animation: {animation}")
        sheets = [patch["image"] for patch in json.loads(original.read(animation))]
        if not sheets or any(not ATLAS_SHEET.fullmatch(sheet) for sheet in sheets):
            raise ValueError(f"Invalid Ponder atlas animation sheets: {animation}")
        for sheet in sheets:
            original.getinfo(sheet)
        used.update(sheets)
        used.add(animation)
        atlas_animations.add(animation)

    for model in models:
        for draw in model:
            retain_texture(draw)

    for scene, folder, count in scene_timelines(original, scenes):
        excluded = Counter()
        captures = defaultdict(set)
        for start in range(0, count, PLAYBACK_TICKS):
            checkpoint()
            name = f"{folder}/timeline/{start}.json"
            bundle = f"{folder}/meshes/{start}.bin"
            patches = json.loads(original.read(name))
            if len(patches) != min(PLAYBACK_TICKS, count - start):
                raise ValueError(f"Incomplete Ponder timeline: {name}")
            for patch in patches:
                for face in patch.get("fluidFaces", []):
                    used.update((face["image"], face["lightmap"]))
                    used.update(image for image, _ in face.get("frames", []))
                for draw in patch.get("scene", []) + patch.get("overlay", []):
                    if not supported_draw(draw):
                        excluded[draw["shader"]] += 1
                        continue
                    if MESH.fullmatch(draw["vertices"]):
                        captures[draw["shader"]].add(draw["vertices"])
                    for key in ("vertices", "indices"):
                        if key not in draw:
                            continue
                        path = draw[key]
                        used.add(path)
                        if key == "vertices" and path not in rigid_batches:
                            rigid = RIGID_BATCH.fullmatch(path)
                            version = rigid[1] if rigid else "glint" if RIGID_GLINT.fullmatch(path) else None
                            if version:
                                used.update(rigid_assets(original, path, version))
                                rigid_batches.add(path)
                            elif MIXED_BOX_BATCH.fullmatch(path):
                                used.update(mixed_box_assets(original, path))
                                rigid_batches.add(path)
                        if key == "indices":
                            standalone.add(path)
                        elif MESH.fullmatch(path) and "stride" in draw:
                            stride = draw["stride"]
                            if vertex_strides.setdefault(path, stride) != stride:
                                standalone.add(path)
                        if path in shared or not (MESH.fullmatch(path) or (key == "vertices" and BUNDLED_GEOMETRY.fullmatch(path))) \
                                or original.getinfo(path).file_size > SMALL_MESH:
                            continue
                        if candidates.setdefault(path, bundle) != bundle:
                            candidates[path] = None
                    for texture in draw["textures"].values():
                        retain_texture(texture)
        if excluded:
            excluded_draws[scene["id"]] = dict(excluded.most_common())
        if captures:
            captured_vertices[scene["id"]] = {
                shader: {"files": len(paths), "bytes": sum(original.getinfo(path).file_size for path in paths)}
                for shader, paths in captures.items()
            }
    bundles = defaultdict(list)
    for path, bundle in candidates.items():
        if bundle and path not in standalone:
            bundles[bundle].append(path)
    stream_candidates = {path: stride for path, stride in vertex_strides.items()
                         if path not in standalone and stride in (28, 32, 36)}
    return used, bundles, stream_candidates, {"excludedDrawSnapshotsByScene": excluded_draws,
                                             "capturedVerticesByScene": captured_vertices}


def mesh_streams(data, stride):
    boundaries = (0, 12, 16, 24, 28) if stride == 28 else (0, 12, 16, 24, 28, stride)
    return [b"".join(data[column::stride] for column in range(start, end))
            for start, end in zip(boundaries, boundaries[1:])]


def pack_position_planes(planes, count):
    source = memoryview(planes)
    rows = [bytes(row) for row in zip(*(source[lane * count:(lane + 1) * count] for lane in range(12)))]
    best = planes
    for width, entries in ((12, rows), (4, (row[offset:offset + 4] for row in rows for offset in (0, 4, 8)))):
        palette = {}
        indices = []
        for entry in entries:
            indices.append(palette.setdefault(entry, len(palette)))
        if len(palette) > 65535:
            continue
        index_width = 1 if len(palette) <= 256 else 2
        if 3 + len(palette) * width + len(indices) * index_width + 8 >= len(best):
            continue
        codes = bytes(indices) if index_width == 1 else struct.pack(f"<{len(indices)}H", *indices)
        best = struct.pack("<BH", width, len(palette)) + b"".join(palette) + codes
    return best


def write_stream_meshes(original, packed, candidates, checkpoint):
    metadata = {}
    uses = Counter()
    for path, stride in candidates.items():
        checkpoint()
        size = original.getinfo(path).file_size
        if not SMALL_MESH < size <= STREAM_MESH_LIMIT or size % stride:
            continue
        parts = [(sha256(part).hexdigest(), len(part)) for part in mesh_streams(original.read(path), stride)]
        metadata[path] = parts
        uses.update(digest for digest, _ in parts)

    replacements = {}
    written = set()
    for path, parts in metadata.items():
        checkpoint()
        selected = [index for index, (digest, size) in enumerate(parts) if uses[digest] > 1 and size >= 192]
        if not selected:
            continue
        stride = candidates[path]
        count = original.getinfo(path).file_size // stride
        mask = sum(1 << index for index in selected)
        streams = mesh_streams(original.read(path), stride)
        remainder = b"".join(part for index, part in enumerate(streams) if not mask & (1 << index))
        shared = [parts[index][0] for index in selected]
        palette = pack_position_planes(remainder, count) if mask == (1 << len(parts)) - 2 else remainder
        encoded_size = f":{len(palette)}" if palette is not remainder else ""
        palette_savings = len(remainder) - len(palette)
        version = 2 if encoded_size else 1
        remainder = palette
        residual = sha256(remainder).hexdigest() if remainder else "-"
        ref = f"{path}#v{version}:{stride}:{count}:{mask:x}:{residual}{encoded_size}:" + ":".join(shared)
        saved = sum(parts[index][1] for index in selected if parts[index][0] in written)
        estimated = sum(size * (1 - 1 / uses[digest]) for index, (digest, size) in enumerate(parts)
                        if index in selected)
        if max(saved, estimated) + palette_savings <= len(ref) - len(path) + 150:
            continue
        for index in selected:
            digest = parts[index][0]
            if digest not in written:
                packed.writestr(f"ponder/meshes/{digest}.bin", streams[index])
                written.add(digest)
        if remainder and residual not in written:
            packed.writestr(f"ponder/meshes/{residual}.bin", remainder)
            written.add(residual)
        replacements[path] = ref
    return replacements, written


def write_bundles(original, packed, bundles, checkpoint):
    slices = {}
    for bundle, members in bundles.items():
        checkpoint()
        contents = bytearray()
        for path in members:
            size = original.getinfo(path).file_size
            if not size or len(contents) + size > BUNDLE_LIMIT:
                continue
            typed = BUNDLED_GEOMETRY.fullmatch(path)
            digest = typed[2] if typed else MESH.fullmatch(path)[1]
            codec = typed[1] if typed else ""
            slices[path] = compact_bundle_reference(len(contents), size, digest, codec)
            contents.extend(original.read(path))
        if contents:
            packed.writestr(bundle, contents)
    return slices


def compact_bundle_reference(offset, size, digest, codec):
    encoded = urlsafe_b64encode(bytes.fromhex(digest)).decode("ascii").rstrip("=")
    return f"~{'0123456789abcdefghijklmnopqrstuvwxyz'[BUNDLE_CODECS[codec]]}:{offset}:{size}:{encoded}"


def write_timelines(original, packed, scenes, slices, sprite_animations, checkpoint, report):
    animation_ids = {json_bytes(animation): index for index, animation in enumerate(sprite_animations)}

    @lru_cache(maxsize=64)
    def picking_snapshot(path):
        return json.loads(original.read(path))

    last_report = monotonic()
    for completed, (scene, folder, count) in enumerate(scene_timelines(original, scenes), start=1):
        manifest = scene["manifest"]
        packed.writestr(manifest, original.read(manifest))
        current_picking = None
        previous_output_path = None
        chunk = None
        for start in range(0, count, PLAYBACK_TICKS):
            checkpoint()
            name = f"{folder}/timeline/{start}.json"
            patches = json.loads(original.read(name))
            local_refs = False
            for offset, patch in enumerate(patches):
                tick = start + offset
                slot = tick % PICKING_TICKS
                picking_path = f"{folder}/picking/{tick - slot}.json"
                if not slot:
                    chunk = PickingChunk()
                    previous_snapshot = None
                current_picking = patch.pop("picking", current_picking)
                if current_picking and previous_snapshot != current_picking:
                    chunk.append(slot, picking_snapshot(current_picking))
                    previous_snapshot = current_picking
                if current_picking and previous_output_path != picking_path:
                    patch["picking"] = picking_path
                    previous_output_path = picking_path
                if (slot == PICKING_TICKS - 1 or tick == count - 1) and current_picking:
                    packed.writestr(picking_path, chunk.encode())
                patch.pop("events", None)
                for face in patch.get("fluidFaces", []):
                    frames = face.get("frames")
                    if frames is not None:
                        animation = {"frames": frames}
                        if face.get("interpolate"):
                            animation["interpolate"] = True
                        key = json_bytes(animation)
                        if key not in animation_ids:
                            if len(sprite_animations) >= 4096:
                                continue
                            animation_ids[key] = len(sprite_animations)
                            sprite_animations.append(animation)
                        face.pop("frames")
                        face.pop("interpolate", None)
                        face["spriteAnimation"] = animation_ids[key]
                for field in ("scene", "overlay"):
                    if field not in patch:
                        continue
                    patch[field] = [draw for draw in patch[field] if supported_draw(draw)]
                    for draw in patch[field]:
                        for key in ("vertices", "indices"):
                            if key in draw:
                                draw[key] = slices.get(draw[key], draw[key])
                                local_refs |= draw[key].startswith("~")
                        for texture in draw["textures"].values():
                            if texture.pop("animation", None):
                                texture["animated"] = True
            packed.writestr(name, encode_timeline(patches, local_refs))
        now = monotonic()
        if completed == len(scenes) or now - last_report >= 30:
            report(f"Packed Ponder timelines: {completed:,} / {len(scenes):,} scenes ({completed / len(scenes):.0%})")
            last_report = now


def pack_ponder_archive(source, output, checkpoint=lambda: None, report=lambda _: None):
    """Publish the packed ZIP atomically. Other export sections pass through unchanged."""
    with ZipFile(source) as original:
        names = set(original.namelist())
        if MODELS not in names or DEFINITIONS not in names:
            return False
        definitions = json.loads(original.read(DEFINITIONS))
        if definitions.get("capycraftPacked") is True:
            return False
        scenes = definitions["playback"]["scenes"]
        if not scenes:
            raise ValueError("No Ponder scenes to pack.")
        models = json.loads(original.read(MODELS))
        if len(names) != len(original.infolist()):
            raise ValueError("Duplicate entries in the Ponder archive.")
        sprite_animations = models.get("spriteAnimations", [])
        report(f"Collecting assets referenced by {len(scenes):,} Ponder scenes")
        used, bundles, stream_candidates, coverage = collect_resources(
            original, scenes, models["models"], sprite_animations, checkpoint)
        for registration in definitions["registrations"]:
            registration.pop("schematicFile", None)
        share_definitions(definitions)
        definitions["capycraftPacked"] = True
        models.pop("blockStates", None)
        diagnostics = json.loads(original.read(DIAGNOSTICS)) if DIAGNOSTICS in names else {}
        diagnostics.setdefault("ponder", {}).update(coverage)
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix="ponder-", suffix=".tmp", delete=False) as temp:
            temporary = Path(temp.name)
        try:
            with ZipFile(temporary, "w", compression=ZIP_DEFLATED) as packed:
                report(f"Bundling small geometry resources in {len(bundles):,} Ponder timeline sections")
                slices = write_bundles(original, packed, bundles, checkpoint)
                report(f"Checking {len(stream_candidates):,} meshes for shared vertex attributes")
                streams, written = write_stream_meshes(original, packed, stream_candidates, checkpoint)
                position_palettes = sum("#v2:" in reference for reference in streams.values())
                diagnostics["ponder"]["positionPaletteMeshes"] = position_palettes
                if position_palettes:
                    report(f"Packed exact position palettes for {position_palettes:,} meshes")
                slices.update(streams)
                report(f"Writing timelines and block picking for {len(scenes):,} Ponder scenes")
                write_timelines(original, packed, scenes, slices, sprite_animations, checkpoint, report)
                if sprite_animations:
                    models["spriteAnimations"] = sprite_animations
                report("Writing retained Ponder assets and completing the ZIP")
                for entry in original.infolist():
                    name = entry.filename
                    if name == DIAGNOSTICS:
                        continue
                    match = MESH.fullmatch(name)
                    if name.startswith("ponder/") and (name not in used or name in slices or
                                                        (match and match[1] in written)):
                        continue
                    checkpoint()
                    packed.writestr(name, original.read(name), compress_type=ZIP_STORED if name.endswith((".png", ".webp")) else entry.compress_type)
                packed.writestr(DEFINITIONS, json_bytes(definitions))
                packed.writestr(MODELS, json_bytes(models))
                packed.writestr(DIAGNOSTICS, json_bytes(diagnostics))
            checkpoint()
            original.close()  # Windows needs the source handle closed before in-place publication.
            temporary.replace(output)
            return True
        finally:
            temporary.unlink(missing_ok=True)

from base64 import urlsafe_b64decode
from contextlib import ExitStack, redirect_stdout
from datetime import datetime
import importlib.util
import gzip
from hashlib import sha256
from io import BytesIO, StringIO, TextIOWrapper
import json
from pathlib import Path
import signal
import struct
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from zipfile import ZIP_DEFLATED, ZipFile
from pack_ponder import encode_timeline, mixed_box_assets, pack_position_planes, rigid_assets, share_definitions, share_draw_values, supported_draw, unpack_sparse_poses, write_stream_meshes


spec = importlib.util.spec_from_file_location("launch_export", Path(__file__).with_name("launch-export.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LaunchExportTest(unittest.TestCase):
    def test_packs_native_line_draws_with_direction_and_stroke_width(self):
        draw = {"mode": "LINES", "shader": "rendertype_lines", "vertexCount": 4, "indexCount": 6, "lineWidth": 2.5,
                "attributes": [{"name": "Position", "type": "FLOAT", "count": 3},
                               {"name": "Normal", "type": "BYTE", "count": 3}], "textures": {}}
        self.assertTrue(supported_draw(draw))
        self.assertTrue(supported_draw({**draw, "mode": "LINE_STRIP", "indexCount": 4}))
        self.assertFalse(supported_draw({**draw, "attributes": draw["attributes"][:1]}))
        self.assertFalse(supported_draw({**draw, "lineWidth": float("inf")}))
        self.assertFalse(supported_draw({**draw, "indexCount": 4}))
        self.assertFalse(supported_draw({**draw, "mode": "QUADS"}))

    def test_shares_vertex_attributes_and_preserves_original_meshes(self):
        def mesh(offset):
            return b"".join(sha256(bytes([vertex + offset])).digest()[:12] + color_uv_light for vertex in range(64))

        color_uv_light = bytes(range(16))
        data = [mesh(0), mesh(64)]
        paths = [f"ponder/meshes/{sha256(value).hexdigest()}.bin" for value in data]
        original_bytes, packed_bytes = BytesIO(), BytesIO()
        with ZipFile(original_bytes, "w") as original:
            for path, value in zip(paths, data):
                original.writestr(path, value)
        with ZipFile(original_bytes) as original, ZipFile(packed_bytes, "w") as packed:
            refs, written = write_stream_meshes(original, packed, dict.fromkeys(paths, 28), lambda: None)
        self.assertEqual(set(refs), set(paths))
        with ZipFile(packed_bytes) as packed:
            self.assertLess(sum(info.file_size for info in packed.infolist()), sum(map(len, data)))
            for path, expected in zip(paths, data):
                header, stride, count, mask, remainder, *shared = refs[path].split(":")
                self.assertEqual(header, path + "#v1")
                self.assertEqual(int(mask, 16), 14)
                self.assertEqual(set([remainder, *shared]) - written, set())
                planes = b"".join(packed.read(f"ponder/meshes/{digest}.bin") for digest in [remainder, *shared])
                restored = b"".join(planes[column * int(count) + vertex: column * int(count) + vertex + 1]
                                    for vertex in range(int(count)) for column in range(int(stride)))
                self.assertEqual(restored, expected)

    def test_packs_position_planes_as_exact_float_bits(self):
        words = [bytes.fromhex(encoded) for encoded in ("00000000", "00000080", "0000803f")]
        rows = [b"".join(words[(vertex >> axis) % 3] for axis in range(3)) for vertex in range(48)]
        original = b"".join(bytes(row[lane] for row in rows) for lane in range(12))
        encoded = pack_position_planes(original, len(rows))
        width, count = struct.unpack_from("<BH", encoded)
        self.assertEqual((width, count), (4, 3))
        self.assertLess(len(encoded), len(original))
        palette = [encoded[start:start + width] for start in range(3, 3 + count * width, width)]
        self.assertEqual(b"".join(palette[index] for index in encoded[3 + count * width:]), b"".join(rows))

    def test_writes_position_palette_references_to_packed_meshes(self):
        original_bytes, packed_bytes = BytesIO(), BytesIO()
        data = [b"".join(struct.pack("<III", vertex % 8, vertex // 8, offset) + bytes(range(16))
                         for vertex in range(64)) for offset in (0, 1)]
        paths = [f"ponder/meshes/{sha256(value).hexdigest()}.bin" for value in data]
        with ZipFile(original_bytes, "w") as original:
            for path, value in zip(paths, data):
                original.writestr(path, value)
        with ZipFile(original_bytes) as original, ZipFile(packed_bytes, "w") as packed:
            refs, written = write_stream_meshes(original, packed, dict.fromkeys(paths, 28), lambda: None)
        self.assertEqual(set(refs), set(paths))
        with ZipFile(packed_bytes) as packed:
            for path, value in zip(paths, data):
                header, stride, count, mask, residual, size, *shared = refs[path].split(":")
                self.assertEqual((header, stride, count, mask), (path + "#v2", "28", "64", "e"))
                self.assertTrue({residual, *shared}.issubset(written))
                position_data = packed.read(f"ponder/meshes/{residual}.bin")
                self.assertEqual(int(size), len(position_data))
                width, entries = struct.unpack_from("<BH", position_data)
                palette = [position_data[start:start + width] for start in range(3, 3 + entries * width, width)]
                restored = b"".join(palette[index] for index in position_data[3 + entries * width:])
                self.assertEqual(restored, b"".join(value[vertex:vertex + 12] for vertex in range(0, len(value), 28)))

    def test_shared_scene_definitions_keep_all_item_registrations(self):
        scene = {"title": "Pipe", "instructions": [{"caption": "Connect the pipes"}]}
        definitions = {"registrations": [
            {"item": "example:pipe", "scene": scene},
            {"item": "example:pipe_variant", "scene": dict(scene)},
            {"item": "example:unavailable", "compileError": "Exception"},
        ]}
        share_definitions(definitions)
        self.assertEqual(definitions["sceneDefinitions"], [scene])
        self.assertEqual(definitions["registrations"], [
            {"item": "example:pipe", "scene": 0},
            {"item": "example:pipe_variant", "scene": 0},
            {"item": "example:unavailable", "compileError": "Exception"},
        ])

    def test_timeline_encodes_only_changed_draw_and_layout_fields(self):
        placement = {"transform": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
                     "blocks": [[x, 0, 0, 1] for x in range(32)], "textureOffsets": [[0, 0]]}
        moved = {**placement, "transform": [*placement["transform"][:12], 2, 0, 0, 1]}
        del moved["textureOffsets"]
        patches = [{"movingLayout": [placement], "captions": [{"text": "Original"}]}, {},
                   {"movingLayout": [moved]}, {"movingLayout": []}]
        packed = json.loads(encode_timeline(patches))
        self.assertEqual(packed["version"], 10)
        self.assertEqual(packed["patches"][:2], [
            {"movingLayout": [[{"a": [0]}, placement["blocks"], [[0, 0]]]], "captions": patches[0]["captions"]}, {}])
        self.assertEqual(packed["patches"][2]["movingLayout"],
                         [1, [0, [{"d": [4096, 2]}], ["textureOffsets"]]])
        self.assertEqual(packed["patches"][3], patches[3])

    def test_timeline_shares_transforms_between_moving_parts_and_fluid_faces(self):
        matrix = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 0, 0, 1]
        patches = [{"movingLayout": [{"transform": matrix, "blocks": [[0, 0, 0, 1]]}],
                    "fluidFaces": [{"transform": matrix, "geometry": [x, 0, 0]} for x in range(4)]}]
        packed = json.loads(encode_timeline(patches))
        self.assertEqual(packed["version"], 4)
        self.assertEqual(packed["transforms"], [{"a": [4096, 2]}])
        self.assertEqual(packed["patches"][0]["movingLayout"][0], [0, [[0, 0, 0, 1]]])
        self.assertEqual(packed["patches"][0]["fluidFaces"][0]["transform"], 0)
        self.assertEqual(patches[0]["movingLayout"][0]["transform"], matrix)

    def test_timeline_shares_repeated_layout_values_across_parts(self):
        blocks = [[0, 0, 0, 1, 0.125, 0, -0.25]]
        offsets = [[0.12345678, -0.98765432]]
        patches = [{"movingLayout": [
            {"transform": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, 0, 0, 1],
             "blocks": blocks, "textureOffsets": offsets} for x in range(6)]}]
        packed = json.loads(encode_timeline(patches))
        self.assertEqual(packed["version"], 6)
        self.assertEqual(packed["layoutValues"], {"blocks": [blocks], "textureOffsets": [offsets]})
        self.assertTrue(all(part[1:] == [0, 0] for part in packed["patches"][0]["movingLayout"]))

    def test_timeline_compacts_decimal_matrix_values_without_rounding(self):
        matrix = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -2.345678, 3.123456, 4.111111, 1]
        patches = [{"movingLayout": [{"transform": matrix, "blocks": [[0, 0, 0, 1]]}]}]
        packed = json.loads(encode_timeline(patches))
        self.assertEqual(packed["version"], 7)
        self.assertIsInstance(packed["patches"][0]["movingLayout"][0][0]["a"], str)
        matrix[12] = -2.3456789
        legacy = json.loads(encode_timeline(patches))
        self.assertLess(legacy["version"], 7)

    def test_timeline_stores_shared_coefficients_for_planar_rotations(self):
        parts = [{"transform": [cos, sin, 0, 0, -sin, cos, 0, 0, 0, 0, 1, 0, x, 0, 0, 1],
                  "blocks": [[0, 0, 0, 1]]}
                 for cos, sin, x in [(0.866025, 0.5, -2.345678), (0.5, 0.866025, -2.111111)]]
        packed = json.loads(encode_timeline([{"movingLayout": [part]} for part in parts]))
        self.assertEqual(packed["version"], 10)
        self.assertTrue(packed["patches"][0]["movingLayout"][0][0]["a"].startswith(".0"))
        self.assertTrue(packed["patches"][1]["movingLayout"][1][1].startswith(".0"))

    def test_timeline_shares_draw_attributes_across_scene_and_overlay(self):
        attributes = [{"name": "Position", "offset": 0, "type": "FLOAT", "count": 3}]
        projection = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 0, 0, 1]
        first = {"attributes": attributes, "projection": projection, "vertices": "first"}
        second = {**first, "vertices": "second"}
        patches = [{"scene": [first, second], "overlay": [second]}]
        packed = share_draw_values(patches)
        self.assertEqual(packed["version"], 8)
        self.assertEqual(packed["drawValues"], {"attributes": [attributes], "projection": [projection], "vertices": ["second"]})
        self.assertEqual(packed["patches"][0]["scene"], [
            {"attributes": 0, "projection": 0, "vertices": "first"}, {"attributes": 0, "projection": 0, "vertices": 0}])

    def test_sparse_pose_rows_restore_exact_bytes_and_reject_truncation(self):
        part = bytes(range(32)) + struct.pack("<H", 4)
        sparse = struct.pack("<BHI", 1, 2, 1) + part + struct.pack("<If", 0x80000400, 2.75)
        first = bytearray(part + bytes(96))
        for lane in (34, 50, 66, 82, 98, 114):
            struct.pack_into("<f", first, lane, 1)
        second = first[:]
        struct.pack_into("<f", second, 70, 2.75)
        self.assertEqual(unpack_sparse_poses(sparse), (struct.pack("<H", 2) + first + second, "1"))
        with self.assertRaises(ValueError):
            unpack_sparse_poses(sparse[:-1])

    def test_packs_compact_ponder_without_dropping_scenes_or_other_sections(self):
        with TemporaryDirectory() as directory:
            source = Path(directory) / "previews.zip"
            output = source.with_name("packed.zip")
            folder = "ponder/scenes/" + "a" * 64
            manifest = f"{folder}/scene.json"
            picking = "ponder/picking/snapshot.json"
            scene = {"id": "example:scene", "schematic": "example:scene", "manifest": manifest, "items": ["example:block"]}
            mesh = b"small mesh"
            digest = sha256(mesh).hexdigest()
            mesh_path = f"ponder/meshes/{digest}.bin"
            index_data = {"registrations": [{"item": "example:block", "schematicFile": "ponder/schematics/source.nbt"}],
                          "playback": {"scenes": [scene]}}
            draw = {"vertices": mesh_path, "shader": "rendertype_solid", "mode": "QUADS", "vertexCount": 4,
                    "indexCount": 6, "attributes": [{"name": "Position", "type": "FLOAT", "count": 3},
                                                    {"name": "UV0", "type": "FLOAT", "count": 2}],
                    "textures": {"0": {"image": "ponder/textures/used.png", "animation": "ponder/textures/frames.json"}}}
            sheet_bytes = b"shared atlas sprite frames"
            sheet = f"ponder/textures/{sha256(sheet_bytes).hexdigest()}.png"
            atlas_rows = json.dumps([{"image": sheet, "frames": [[0, 5], [1, 3]],
                                      "rect": [3, 7, 12, 16], "source": [4, 0, 16, 16]}]).encode()
            atlas_animation = f"ponder/textures/{sha256(atlas_rows).hexdigest()}.json"
            model_sheet_bytes = b"shared model atlas frames"
            model_sheet = f"ponder/textures/{sha256(model_sheet_bytes).hexdigest()}.png"
            model_rows = atlas_rows.replace(sheet.encode(), model_sheet.encode())
            model_animation = f"ponder/textures/{sha256(model_rows).hexdigest()}.json"
            model_mesh_bytes = b"shared model geometry"
            model_mesh = f"ponder/meshes/{sha256(model_mesh_bytes).hexdigest()}.bin"
            model = {"image": "ponder/textures/used.png", "vertices": model_mesh, "atlasAnimation": model_animation}
            unsupported = {**draw, "shader": "example:custom_pass", "vertices": "ponder/meshes/unused.bin"}
            particle_path = f"ponder/particle-quads/v3/{sha256(b'parameters').hexdigest()}.bin"
            particle = {**draw, "shader": "particle", "vertices": particle_path}
            icon = {**draw, "shader": "position_tex", "textures": {"0": {
                "image": "ponder/textures/used.png", "atlasAnimation": atlas_animation}}}
            unoutlined = {**draw, "shader": "rendertype_entity_no_outline"}
            eyes = {**draw, "shader": "rendertype_eyes"}
            glint = {**draw, "shader": "rendertype_glint"}
            part_bytes = b"shared local model"
            part_digest = sha256(part_bytes).digest()
            rigid_bytes = struct.pack("<H", 1) + part_digest + bytes(98)
            part_path = f"ponder/rigid-parts/v1/{part_digest.hex()}.bin"
            rigid_path = f"ponder/rigid-batches/v1/{sha256(rigid_bytes).hexdigest()}.bin"
            rigid = {**draw, "shader": "rendertype_entity_solid", "vertices": rigid_path}
            attribute_bytes = bytes(range(4 * 12))
            attribute_digest = sha256(attribute_bytes).digest()
            attribute_path = f"ponder/rigid-attributes/v1/{attribute_digest.hex()}.bin"
            unused_attributes = f"ponder/rigid-attributes/v1/{'0' * 64}.bin"
            pose = part_digest + struct.pack("<H", 4) + bytes(96)
            rigid_v2_bytes = struct.pack("<H", 2) + pose + attribute_digest + pose + bytes(32)
            rigid_v2_path = f"ponder/rigid-batches/v2/{sha256(rigid_v2_bytes).hexdigest()}.bin"
            rigid_v2 = {**rigid, "vertices": rigid_v2_path, "vertexCount": 8, "indexCount": 12}
            outline_bytes = struct.pack("<H", 1) + bytes(121)
            outline_path = f"ponder/outline-cuboids/v1/{sha256(outline_bytes).hexdigest()}.bin"
            outline = {**rigid, "vertices": outline_path, "vertexCount": 24, "indexCount": 36}
            cuboid_part = bytes(4 * 24)
            cuboid_digest = sha256(cuboid_part).digest()
            cuboid_part_path = f"ponder/rigid-parts/v1/{cuboid_digest.hex()}.bin"
            cuboid_pose = struct.pack("<H", 1) + cuboid_digest + struct.pack("<H", 4) + bytes(78)
            cuboid_path = f"ponder/rigid-batches/v3/{sha256(cuboid_pose).hexdigest()}.bin"
            cuboid = {**rigid, "vertices": cuboid_path}
            normal_bytes = bytes((90, 90, 0))
            normal_digest = sha256(normal_bytes).digest()
            normal_path = f"ponder/rigid-normals/v1/{normal_digest.hex()}.bin"
            normal_pose = struct.pack("<H", 1) + cuboid_digest + struct.pack("<H", 4) + bytes(96) + bytes(32) + normal_digest
            palette_path = f"ponder/rigid-batches/v4/{sha256(normal_pose).hexdigest()}.bin"
            palette = {**rigid, "vertices": palette_path}
            foil_part = bytes(range(4 * 24))
            foil_digest = sha256(foil_part).digest()
            foil_part_path = f"ponder/rigid-parts/v1/{foil_digest.hex()}.bin"
            foil_pose = struct.pack("<H", 1) + foil_digest + struct.pack("<H", 4) + bytes(48)
            foil_path = f"ponder/rigid-glint/v1/{sha256(foil_pose).hexdigest()}.bin"
            foil = {**glint, "vertices": foil_path, "stride": 20}
            lightning_bytes = struct.pack("<HQ12f", 1, 2345, 1, 0, 0, 0, 1, 0, 0, 0, 1, 3, 4, 5)
            lightning_path = f"ponder/lightning/v1/{sha256(lightning_bytes).hexdigest()}.bin"
            lightning = {**draw, "shader": "rendertype_lightning", "vertices": lightning_path, "stride": 16,
                         "vertexCount": 896, "indexCount": 1344}
            liquid = {"image": "ponder/textures/fluid.png", "lightmap": "ponder/textures/lightmap.png",
                      "frames": [["ponder/textures/fluid.png", 2], ["ponder/textures/fluid-next.png", 3]]}
            native_frame = "ponder/textures/fluid-native-next.png"
            native_animation = {"frames": [[liquid["image"], 2], [native_frame, 3]], "interpolate": True}
            native_fluid = {"image": liquid["image"], "lightmap": liquid["lightmap"], "spriteAnimation": 0}
            snapshot = [{"transform": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
                         "blocks": [{"position": [0, 0, 0], "block": "example:block", "boxes": [[0, 0, 0, 1, 1, 1]]}]}]
            with ZipFile(source, "w") as archive:
                for name, value in {"ponder/definitions.json": index_data,
                                    "ponder/block-models.json": {"models": [[model]], "spriteAnimations": [native_animation]},
                                    manifest: {"frameCount": 65, "platformShadow": [0, 0, 5]}, picking: snapshot}.items():
                    archive.writestr(name, json.dumps(value))
                for start in range(0, 65, 16):
                    timeline = [{} for _ in range(min(16, 65 - start))]
                    if start == 0:
                        timeline[0] = {"scene": [draw, unsupported, particle, rigid, rigid_v2, outline, cuboid, palette, foil, lightning,
                                                 unoutlined, eyes, glint],
                                       "picking": picking, "events": {"blocks": []},
                                       "fluidFaces": [liquid, liquid, native_fluid],
                                       "overlay": [icon], "captions": [{"text": "Copper casing"}]}
                    archive.writestr(f"{folder}/timeline/{start}.json", json.dumps(timeline))
                archive.writestr(mesh_path, mesh)
                archive.writestr(particle["vertices"], b"parameters")
                archive.writestr(rigid_path, rigid_bytes)
                archive.writestr(rigid_v2_path, rigid_v2_bytes)
                archive.writestr(outline_path, outline_bytes)
                archive.writestr(cuboid_path, cuboid_pose)
                archive.writestr(cuboid_part_path, cuboid_part)
                archive.writestr(palette_path, normal_pose)
                archive.writestr(foil_path, foil_pose)
                archive.writestr(lightning_path, lightning_bytes)
                archive.writestr(foil_part_path, foil_part)
                archive.writestr(normal_path, normal_bytes)
                archive.writestr(part_path, part_bytes)
                archive.writestr(attribute_path, attribute_bytes)
                archive.writestr(unused_attributes, attribute_bytes)
                archive.writestr("ponder/meshes/unused.bin", b"unsupported")
                archive.writestr("ponder/textures/used.png", b"sprite")
                archive.writestr("ponder/textures/frames.json", b"[]")
                archive.writestr(atlas_animation, atlas_rows)
                archive.writestr(sheet, sheet_bytes)
                archive.writestr(model_animation, model_rows)
                archive.writestr(model_sheet, model_sheet_bytes)
                archive.writestr(model_mesh, model_mesh_bytes)
                archive.writestr(liquid["image"], b"fluid")
                archive.writestr(liquid["lightmap"], b"lightmap")
                archive.writestr(liquid["frames"][1][0], b"next fluid frame")
                archive.writestr(native_frame, b"next interpolated fluid frame")
                archive.writestr("ponder/schematics/source.nbt", b"working data")
                archive.writestr("catalog.json", b"{}")

            with source.open("rb") as raw_archive:
                before = sha256(raw_archive.read()).digest()
                progress = []
                self.assertTrue(launcher.pack_ponder_archive(source, output, report=progress.append))
                self.assertIn("Packed Ponder timelines: 1 / 1 scenes (100%)", progress)
                raw_archive.seek(0)
                self.assertEqual(sha256(raw_archive.read()).digest(), before)
            with ZipFile(output) as packed:
                definitions = json.loads(packed.read("ponder/definitions.json"))
                diagnostics = json.loads(packed.read("export-diagnostics.json"))
                self.assertEqual(diagnostics["ponder"]["excludedDrawSnapshotsByScene"],
                                 {"example:scene": {"example:custom_pass": 1}})
                self.assertEqual(diagnostics["ponder"]["capturedVerticesByScene"]["example:scene"]["rendertype_solid"],
                                 {"files": 1, "bytes": len(mesh)})
                self.assertEqual(definitions["playback"]["scenes"], [scene])
                self.assertNotIn("schematicFile", definitions["registrations"][0])
                self.assertEqual(packed.read("catalog.json"), b"{}")
                self.assertEqual(json.loads(packed.read(manifest))["platformShadow"], [0, 0, 5])
                bundle = f"{folder}/meshes/0.bin"
                bundle_bytes = packed.read(bundle)
                self.assertEqual(bundle_bytes[:len(mesh)], mesh)
                self.assertNotIn(mesh_path, packed.namelist())
                self.assertNotIn("ponder/meshes/unused.bin", packed.namelist())
                self.assertNotIn("ponder/textures/frames.json", packed.namelist())
                self.assertEqual(packed.read(atlas_animation), atlas_rows)
                self.assertEqual(packed.read(sheet), sheet_bytes)
                self.assertEqual(packed.read(model_animation), model_rows)
                self.assertEqual(packed.read(model_sheet), model_sheet_bytes)
                self.assertNotIn("ponder/schematics/source.nbt", packed.namelist())
                timeline = json.loads(packed.read(f"{folder}/timeline/0.json"))
                self.assertIn(timeline["version"], (5, 8))
                first = timeline["patches"][0]
                for layer in ("scene", "overlay"):
                    for draw in first[layer]:
                        for lane, palette in timeline.get("drawValues", {}).items():
                            if isinstance(draw.get(lane), int):
                                draw[lane] = palette[draw[lane]]
                self.assertNotIn("events", first)
                self.assertEqual(first["overlay"][0]["textures"]["0"]["atlasAnimation"], atlas_animation)
                self.assertEqual(len(first["scene"]), 12)
                self.assertEqual([draw["shader"] for draw in first["scene"][-3:]],
                                 ["rendertype_entity_no_outline", "rendertype_eyes", "rendertype_glint"])
                self.assertEqual(first["overlay"][0]["shader"], "position_tex")
                self.assertEqual(first["captions"], [{"text": "Copper casing"}])
                self.assertTrue(first["scene"][0]["textures"]["0"]["animated"])
                members = [("~0", mesh_path, mesh), ("~d", particle["vertices"], b"parameters"), ("~5", rigid_path, rigid_bytes),
                           ("~6", rigid_v2_path, rigid_v2_bytes), ("~3", outline_path, outline_bytes), ("~7", cuboid_path, cuboid_pose),
                           ("~8", palette_path, normal_pose), ("~9", foil_path, foil_pose), ("~b", lightning_path, lightning_bytes)]
                for draw, (codec, path, contents) in zip(first["scene"][:len(members)], members):
                    expected_digest = path.rsplit("/", 1)[1].removesuffix(".bin")
                    encoded_codec, offset, length, encoded_hash = draw["vertices"].split(":")
                    self.assertEqual((int(length), urlsafe_b64decode(encoded_hash + "=").hex(), encoded_codec),
                                     (len(contents), expected_digest, codec))
                    self.assertEqual(bundle_bytes[int(offset):int(offset) + int(length)], contents)
                    self.assertNotIn(path, packed.namelist())
                self.assertEqual(packed.read(part_path), part_bytes)
                self.assertEqual(packed.read(cuboid_part_path), cuboid_part)
                self.assertEqual(packed.read(normal_path), normal_bytes)
                self.assertEqual(packed.read(foil_part_path), foil_part)
                self.assertEqual(packed.read(attribute_path), attribute_bytes)
                self.assertNotIn(unused_attributes, packed.namelist())
                sprite_ref = {**native_fluid, "spriteAnimation": 1}
                self.assertEqual(first["fluidFaces"], [sprite_ref, sprite_ref, native_fluid])
                self.assertEqual(json.loads(packed.read("ponder/block-models.json"))["spriteAnimations"],
                                 [native_animation, {"frames": liquid["frames"]}])
                self.assertEqual(packed.read(liquid["image"]), b"fluid")
                self.assertEqual(packed.read(liquid["lightmap"]), b"lightmap")
                self.assertEqual(packed.read(liquid["frames"][1][0]), b"next fluid frame")
                self.assertEqual(packed.read(native_frame), b"next interpolated fluid frame")
                second = json.loads(packed.read(f"{folder}/timeline/64.json"))[0]
                self.assertEqual(second["picking"], f"{folder}/picking/64.json")
                self.assertEqual(packed.read(first["picking"]), packed.read(second["picking"]))
            self.assertTrue(launcher.pack_ponder_archive(source, source))
            self.assertFalse(launcher.pack_ponder_archive(source, source))

    def test_rejects_rigid_attribute_files_with_the_wrong_vertex_count(self):
        part_digest, attributes = bytes([1] * 32), bytes([2] * 32)
        pose = part_digest + struct.pack("<H", 4) + bytes(96)
        batch = struct.pack("<H", 1) + pose + attributes
        raw = BytesIO()
        with ZipFile(raw, "w") as archive:
            archive.writestr("batch", batch)
            archive.writestr(f"ponder/rigid-parts/v1/{part_digest.hex()}.bin", bytes(4 * 24))
            archive.writestr(f"ponder/rigid-attributes/v1/{attributes.hex()}.bin", bytes(3 * 12))
        with ZipFile(raw) as archive, self.assertRaisesRegex(ValueError, "vertex attributes size"):
            rigid_assets(archive, "batch", "2")

    def test_retains_nested_rigid_models_and_attributes_for_mixed_faces(self):
        part, attributes = bytes([1] * 32), bytes([2] * 32)
        pose = part + struct.pack("<H", 4) + bytes(96)
        rigid = bytes([2]) + struct.pack("<H", 1) + pose + attributes
        mixed = struct.pack("<HHI", 2, 0x4001, len(rigid)) + rigid + struct.pack("<HI", 1, 144) + bytes(144)
        part_path = f"ponder/rigid-parts/v1/{part.hex()}.bin"
        attribute_path = f"ponder/rigid-attributes/v1/{attributes.hex()}.bin"
        raw = BytesIO()
        with ZipFile(raw, "w") as archive:
            archive.writestr("mixed", mixed)
            archive.writestr("bad-mixed", mixed + b"\0")
            archive.writestr(part_path, bytes(4 * 24))
            archive.writestr(attribute_path, bytes(4 * 12))
        with ZipFile(raw) as archive:
            self.assertEqual(mixed_box_assets(archive, "mixed"), {part_path, attribute_path})
            with self.assertRaisesRegex(ValueError, "Unexpected bytes"):
                mixed_box_assets(archive, "bad-mixed")

    def test_retains_verified_output_and_only_latest_failure(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)

            def publish(content, reference=None):
                with launcher.staging_run(root) as run:
                    world = run / "client/saves" / launcher.WORLD
                    world.mkdir(parents=True)
                    (world / "level.dat").write_bytes(gzip.compress(b"\x0a" + content))
                    archive = run / "previews.zip"
                    archive.write_bytes(content)
                    if reference is not None:
                        frame = run / "client/webcraft-exports/ponder-reference/frame.png"
                        frame.parent.mkdir(parents=True)
                        frame.write_bytes(reference)
                    return launcher.publish_export(run, archive)

            archive = publish(b"first")
            for marker in (b"a", b"b"):
                with self.assertRaises(launcher.ExportCancelled), launcher.staging_run(root) as run:
                    (run / "client.log").write_bytes(marker * 100000)
                    raise launcher.ExportCancelled
            self.assertEqual(archive.read_bytes(), b"first")
            self.assertEqual((root / "last-failure.log").read_bytes().strip(), b"b" * 65536)
            self.assertEqual(publish(b"second", b"reference"), archive)
            self.assertEqual(archive.read_bytes(), b"second")
            self.assertEqual((archive.parent / "ponder-reference/frame.png").read_bytes(), b"reference")
            self.assertEqual(gzip.decompress((launcher.last_world(root) / "level.dat").read_bytes()), b"\x0asecond")
            self.assertEqual(list(root.iterdir()), [root / "latest"])

    def test_reordered_sections_share_output_but_filtered_exports_are_independent(self):
        def key(sections, scenes=None):
            return launcher.configuration_key("pack", "webp", False, sections, scenes)
        self.assertEqual(key(["recipes", "items"]), key(["items", "recipes"]))
        self.assertEqual(key(["ponder"]), key(["ponder", "ponder-compact"]))
        self.assertNotEqual(key(["ponder"]), key(["ponder-legacy"]))
        self.assertNotEqual(key(["ponder"]), key(["ponder"], ["create:smart_pipe"]))
        self.assertNotEqual(key(["ponder"], ["create:smart_pipe"]),
                            launcher.configuration_key("pack", "webp", False, ["ponder"], ["create:smart_pipe"], reference_ticks=[150]))
        self.assertNotEqual(key(None), key(["recipes"]))
        self.assertEqual("pack", key(None))
        self.assertNotEqual(key(None), launcher.configuration_key("pack", "webp", False, None, None, True))
        self.assertNotEqual(key(["recipes"]), launcher.configuration_key("pack", "webp", False, ["recipes"], None, True))

    def test_reads_preview_atlas_only_from_the_selected_profile(self):
        with TemporaryDirectory() as directory:
            instance = Path(directory)
            config = instance / "config" / "webcraft-export.toml"
            self.assertFalse(launcher.profile_preview_atlas(instance))
            config.parent.mkdir()
            config.write_text('# previewAtlas = true\nsections = ["items"]\n', encoding="utf-8")
            self.assertFalse(launcher.profile_preview_atlas(instance))
            config.write_text('  "previewAtlas" = true # pack default\n', encoding="utf-8-sig")
            self.assertTrue(launcher.profile_preview_atlas(instance))
            config.write_text('[unrelated]\npreviewAtlas = true\n', encoding="utf-8")
            self.assertFalse(launcher.profile_preview_atlas(instance))

    def test_atlas_mode_is_validated_before_publication(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1\n")
                output.writestr("catalog.json", "[]")
                output.writestr("export-diagnostics.json", '{"selectedSections":["items"]}')
            launcher.validate_archive(archive, ["items"], False)
            with self.assertRaisesRegex(ValueError, "requested preview atlas mode"):
                launcher.validate_archive(archive, ["items"], True)
            index = {"version": 1, "tileSize": 64, "columns": 10, "rows": 10, "atlases": [], "sprites": {}}
            with ZipFile(archive, "a") as output:
                output.writestr(launcher.ATLAS_INDEX, json.dumps(index))
            launcher.validate_archive(archive, ["items"], True)
            with self.assertRaisesRegex(ValueError, "requested preview atlas mode"):
                launcher.validate_archive(archive, ["items"], False)

    @unittest.skipUnless(launcher.os.name == "nt", "Windows export locking")
    def test_lock_recovers_interrupted_publication_and_rejects_second_writer(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            previous = root / "previous"
            previous.mkdir()
            (previous / "previews.zip").write_bytes(b"first")
            with launcher.pack_lock(root):
                self.assertEqual((root / "latest/previews.zip").read_bytes(), b"first")
                with self.assertRaisesRegex(ValueError, "Another export"), launcher.pack_lock(root):
                    self.fail("A second writer acquired the same pack")
            with launcher.pack_lock(root):
                self.assertFalse(previous.exists())

    def test_compact_ponder_requires_its_shared_model_palette(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["ponder"]}))
                output.writestr("ponder/definitions.json", json.dumps({"playback": {"scenes": [{"id": "create:demo"}]}}))
            with self.assertRaisesRegex(ValueError, "ZIP validation"):
                launcher.validate_archive(archive, ["ponder"])
            with ZipFile(archive, "a") as output:
                output.writestr("ponder/block-models.json", '{"version":1,"models":[]}')
            launcher.validate_archive(archive, ["ponder"])
            launcher.validate_archive(archive, ["ponder-compact"])

    def test_ponder_size_report_groups_zip_members_and_uses_texture_names(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            texture = "ponder/textures/atlas.png"
            with ZipFile(archive, "w", compression=ZIP_DEFLATED) as output:
                output.writestr("ponder/meshes/block.bin", b"0" * (1024**2))
                output.writestr(texture, b"atlas")
                output.writestr("ponder/scenes/demo/timeline/0.json", "[]")
                output.writestr("ponder/other-fixture/blob.bin", b"fixture")
                output.writestr("catalog.json", b"ignored catalog bytes")
                output.writestr("export-diagnostics.json", json.dumps({"ponder": {
                    "textureSources": {texture: "minecraft:textures/atlas/blocks.png"},
                }}))
            with redirect_stdout(StringIO()) as console:
                launcher.print_ponder_archive_sizes(archive)
            message = console.getvalue()
            self.assertIn("Total: 4 files, 0.00 packed payload, 1.00 unpacked", message)
            self.assertIn("Other Ponder assets: 1 files", message)
            self.assertIn("Captured mesh buffers: 1 files, 0.00 packed, 1.00 unpacked", message)
            self.assertIn("ponder/meshes/block.bin", message)
            self.assertIn("minecraft:textures/atlas/blocks.png", message)
            self.assertIn("Scene timelines and frames: 1 files", message)

    def test_prepares_an_isolated_copy_and_reuses_only_the_export_world(self):
        with TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            instance = root / "source instance"
            (instance / "config").mkdir(parents=True)
            (instance / "mods").mkdir()
            (instance / "saves" / "Personal World").mkdir(parents=True)
            (instance / "manifest.json").write_text("{}", encoding="utf-8")
            options = instance / "options.txt"
            options.write_text("lang:cs_cz\nsoundCategory_master:0.7\nfullscreen:true\n", encoding="utf-8")
            fml = instance / "config" / "fml.toml"
            fml.write_text("earlyWindowControl = true\nkeepConfig = true\n", encoding="utf-8")
            for name in ("Example.jar", "webcraft-exporter-old.jar", "CrashAssistant-example.jar"):
                (instance / "mods" / name).write_bytes(b"original")
            exporter = root / "webcraft-0.1.0.jar"
            exporter.write_bytes(b"built")
            native = root / "natives.jar"
            with ZipFile(native, "w") as archive:
                archive.writestr("example.dll", b"dll")
            prior = "run-" + "a" * 32
            world = root / prior / "client" / "saves" / launcher.WORLD
            world.mkdir(parents=True)
            (world / "level.dat").write_bytes(b"export world")
            (world / "session.lock").write_bytes(b"lock")
            (root / "last-successful-run.txt").write_text(prior, encoding="utf-8")
            game = root / "another run" / "client"

            installed = launcher.prepare_game(instance, game, exporter, [native], launcher.last_world(root))

            self.assertEqual({path.name for path in (game / "mods").iterdir()}, {"Example.jar", exporter.name})
            self.assertEqual(installed, game / "mods" / exporter.name)
            self.assertEqual(installed.read_bytes(), exporter.read_bytes())
            self.assertEqual(installed.stat().st_mtime, exporter.stat().st_mtime)
            with redirect_stdout(StringIO()) as output:
                launcher.print_build(installed)
            modified = datetime.fromtimestamp(exporter.stat().st_mtime).astimezone()
            self.assertIn(str(installed), output.getvalue())
            self.assertIn(modified.isoformat(sep=" ", timespec="seconds"), output.getvalue())
            self.assertEqual((game / "options.txt").read_text(encoding="utf-8").count("soundCategory_master:0.0"), 1)
            self.assertIn("lang:cs_cz", (game / "options.txt").read_text(encoding="utf-8"))
            self.assertIn("earlyWindowControl = false", (game / "config" / "fml.toml").read_text(encoding="utf-8"))
            self.assertIn("keepConfig = true", (game / "config" / "fml.toml").read_text(encoding="utf-8"))
            self.assertEqual((game / "natives" / "example.dll").read_bytes(), b"dll")
            self.assertEqual((game / "saves" / launcher.WORLD / "level.dat").read_bytes(), b"export world")
            self.assertFalse((game / "saves" / launcher.WORLD / "session.lock").exists())
            self.assertFalse((game / "saves" / "Personal World").exists())
            self.assertIn("soundCategory_master:0.7", options.read_text(encoding="utf-8"))
            self.assertIn("earlyWindowControl = true", fml.read_text(encoding="utf-8"))
            self.assertTrue((instance / "mods" / "CrashAssistant-example.jar").exists())

    def test_accepts_an_installed_curseforge_profile_without_a_pack_manifest(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            instance = root / "profile"
            (instance / "mods").mkdir(parents=True)
            (instance / "minecraftinstance.json").write_text(json.dumps({
                "gameVersion": "1.21.1",
                "baseModLoader": {"name": "neoforge-21.1.248"},
            }), encoding="utf-8")
            exporter = root / "webcraft-0.3.0.jar"
            exporter.write_bytes(b"built")
            game = root / "isolated"

            self.assertEqual(launcher.versions(instance), ("1.21.1", "neoforge-21.1.248"))
            self.assertEqual(launcher.prepare_game(instance, game, exporter, [], None).read_bytes(), b"built")
            self.assertFalse((game / "minecraftinstance.json").exists())

    def test_windows_launch_arguments_enable_the_export_without_world_or_account_inputs(self):
        with TemporaryDirectory() as directory:
            install = Path(directory)
            game = install / "a path with spaces"
            vanilla = {
                "assetIndex": {"id": "17"},
                "arguments": {"jvm": ["-Droot=\u0024{game_directory}"], "game": ["--username", "\u0024{auth_player_name}"]},
            }
            neo = {"mainClass": "example.Main", "arguments": {"jvm": [], "game": ["--launchTarget", "forgeclient"]}}
            expected = [vanilla, neo, [], []]
            arguments = launcher.java_arguments(expected, install, game, "neoforge-21.1.249", 8, "png", True)
            self.assertIn("-Dwebcraft.automaticExport=true", arguments)
            self.assertIn("-Dwebcraft.export.includeHidden=true", arguments)
            self.assertIn("-Dwebcraft.export.png=true", arguments)
            self.assertIn(f"-Droot={game}", arguments)
            self.assertFalse(any(argument.startswith("-Dwebcraft.export.sections=") for argument in arguments))
            self.assertFalse(any(argument.startswith("-Dwebcraft.export.backgroundBoost=") for argument in arguments))
            self.assertFalse(any(argument.startswith("-Dwebcraft.export.ponderScenes=") for argument in arguments))
            self.assertEqual(arguments[-4:], ["--width", "480", "--height", "320"])

            recipes = launcher.java_arguments(expected, install, game, "neoforge-21.1.249", 8, "png", False, ["recipes"])
            self.assertIn("-Dwebcraft.export.sections=recipes", recipes)

            scenes = launcher.java_arguments(expected, install, game, "neoforge-21.1.249", 8, "png", False,
                                             ponder_scenes=["create:smart_pipe", "create:fluid_tank/storage"], reference_ticks=[0, 150])
            self.assertIn("-Dwebcraft.export.ponderScenes=create:smart_pipe,create:fluid_tank/storage", scenes)
            self.assertIn("-Dwebcraft.export.ponderReferenceTicks=0,150", scenes)

            for boost in (True, False):
                override = launcher.java_arguments(expected, install, game, "neoforge-21.1.249", 8, "png", False,
                                                   boost=boost)
                self.assertIn(f"-Dwebcraft.export.backgroundBoost={str(boost).lower()}", override)

    def test_recipes_only_archive_validates_when_the_selection_came_from_config(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1\n")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["recipes"]}))
                output.writestr("catalog-data/recipes.json", '{}')
            launcher.validate_archive(archive, None)
            launcher.validate_archive(archive, ["recipes"])
            with self.assertRaisesRegex(ValueError, "requested sections"):
                launcher.validate_archive(archive, ["items", "recipes"])

    def test_legacy_ponder_archive_requires_its_scene_index(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1\n")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["ponder-legacy"]}))
            with self.assertRaisesRegex(ValueError, "ZIP validation"):
                launcher.validate_archive(archive, ["ponder-legacy"])
            with ZipFile(archive, "a") as output:
                output.writestr("ponder/index.json", '{}')
            launcher.validate_archive(archive, ["ponder-legacy"])
            with self.assertRaisesRegex(ValueError, "requested sections"):
                launcher.validate_archive(archive, ["ponder"])

    def test_ponder_definitions_archive_requires_its_definition_index(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["ponder-definitions"]}))
                output.writestr("ponder/index.json", "{}")
            with self.assertRaises(ValueError):
                launcher.validate_archive(archive, ["ponder-definitions"])
            with ZipFile(archive, "a") as output:
                output.writestr("ponder/definitions.json", '{}')
            with self.assertRaisesRegex(ValueError, "no animation timelines"):
                launcher.validate_archive(archive, ["ponder-definitions"])
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["ponder-definitions"]}))
                output.writestr("ponder/definitions.json", json.dumps({"playback": {"scenes": [{"id": "create:demo"}]}}))
            launcher.validate_archive(archive, ["ponder-definitions"])

    def test_prints_latest_progress_each_minute_and_ignores_unrelated_log_lines(self):
        log = BytesIO(b"Unrelated startup message\nStarting WebCraft automatic catalog export\n"
                      b"WebCraft export progress: Step 1/3 - Prepare item catalog\n"
                      b"WebCraft export progress: Step 2/3 - Render item previews (42 / 100 stacks processed)\n")
        process = Mock()
        process.wait.side_effect = [launcher.subprocess.TimeoutExpired("java", 60),
                                    launcher.subprocess.TimeoutExpired("java", 60), 0]
        marker = Mock()
        marker.is_file.return_value = False
        with patch.object(launcher, "monotonic", side_effect=[10, 70, 130]), patch("builtins.print") as output:
            self.assertEqual(launcher.wait_for_export(process, log, marker.is_file), 0)
        message = "Step 2/3 - Render item previews (42 / 100 stacks processed)"
        self.assertEqual([call.args[0] for call in output.call_args_list],
                         [f"[01:00] {message}", f"[02:00] {message}"])

    def test_progress_does_not_stop_export_on_an_unprintable_scene_title(self):
        log = BytesIO(b"WebCraft export progress: Encasing Fluid Pipes \xb7 tick 57\n")
        process = Mock()
        process.wait.side_effect = [launcher.subprocess.TimeoutExpired("java", 60), 0]
        marker = Mock()
        marker.is_file.return_value = False
        buffer = BytesIO()
        with TextIOWrapper(buffer, encoding="cp1250") as console:
            with redirect_stdout(console), patch.object(launcher, "monotonic", side_effect=[10, 70]):
                self.assertEqual(launcher.wait_for_export(process, log, marker.is_file), 0)
            self.assertIn(b"Encasing Fluid Pipes \\ufffd tick 57", buffer.getvalue())

    def test_progress_reader_retries_a_log_line_still_being_written(self):
        log = BytesIO(b"WebCraft export progress: Step 1/3")
        self.assertEqual(launcher.latest_progress(log, "Starting"), "Starting")
        self.assertEqual(log.tell(), 0)
        log.seek(0, 2)
        log.write(b" - Prepare item catalog\n")
        log.seek(0)
        self.assertEqual(launcher.latest_progress(log, "Starting"), "Step 1/3 - Prepare item catalog")

    @unittest.skipUnless(launcher.os.name == "nt", "Windows process flags")
    def test_console_interrupt_during_spawn_signals_the_isolated_client(self):
        with TemporaryDirectory() as directory:
            run = Path(directory)
            game = run / "client"
            game.mkdir()
            process = Mock()
            previous = signal.getsignal(signal.SIGINT)

            def spawn_client(*_args, **_kwargs):
                signal.raise_signal(signal.SIGINT)
                return process

            with patch.object(launcher.subprocess, "Popen", side_effect=spawn_client):
                with self.assertRaises(launcher.ExportCancelled):
                    launcher.run_client(Path("java.exe"), [], game, run)
            self.assertTrue((game / "cancel-export").is_file())
            self.assertIn("-Dwebcraft.export.watchLauncher=true",
                          (run / "client.args").read_text(encoding="utf-8"))
            self.assertIs(signal.getsignal(signal.SIGINT), previous)
            process.wait.assert_called_once_with(timeout=20)
            process.terminate.assert_not_called()

    @unittest.skipUnless(launcher.os.name == "nt", "Windows process handles")
    def test_npm_shell_exit_cancels_a_detached_child(self):
        with TemporaryDirectory() as directory, ExitStack() as resources:
            run = Path(directory)
            game = run / "client"
            game.mkdir()
            popen = subprocess.Popen
            shell = resources.enter_context(popen([sys.executable, "-c", "import time; time.sleep(10)"],
                                                  creationflags=subprocess.CREATE_NO_WINDOW))
            resources.callback(shell.kill)
            with patch.dict(launcher.os.environ, {"npm_lifecycle_event": "export"}), \
                    patch.object(launcher.os, "getppid", return_value=shell.pid):
                parent_exited = launcher.watch_npm_shell(resources)
            self.assertFalse(parent_exited())

            child = None

            def spawn_client(_command, **kwargs):
                nonlocal child
                child = resources.enter_context(popen([sys.executable, "-c", """
from pathlib import Path
import time

for attempt in range(100):
    if Path("cancel-export").is_file():
        raise SystemExit(0)
    time.sleep(0.05)
raise SystemExit(1)
"""], **kwargs))
                resources.callback(child.kill)
                shell.terminate()
                return child

            with patch.object(launcher.subprocess, "Popen", side_effect=spawn_client) as spawn:
                with self.assertRaises(launcher.ExportCancelled):
                    launcher.run_client(Path("java.exe"), [], game, run, parent_exited=parent_exited)
            spawn.assert_called_once()
            self.assertTrue(parent_exited())
            self.assertTrue((game / "cancel-export").is_file())
            self.assertEqual(child.returncode, 0)

    @unittest.skipUnless(launcher.os.name == "nt", "Windows process flags")
    def test_second_terminal_cancels_only_the_selected_run_and_times_out_a_hung_client(self):
        with TemporaryDirectory() as directory:
            exports = Path(directory).resolve()
            root = exports / ("a" * 12)
            run = root / ("run-" + "b" * 32)
            game = run / "client"
            game.mkdir(parents=True)
            other = root / ("run-" + "c" * 32)
            (other / "client").mkdir(parents=True)
            process = Mock()

            def finish_or_cancel(timeout=None):
                if timeout == 1:
                    launcher.request_cancel(run)
                if timeout is not None:
                    raise launcher.subprocess.TimeoutExpired("java", timeout)
                return 1

            process.wait.side_effect = finish_or_cancel
            with patch.object(launcher, "EXPORTS", exports), patch.object(launcher.subprocess, "Popen", return_value=process):
                with self.assertRaises(launcher.ExportCancelled):
                    launcher.run_client(Path("java.exe"), [], game, run)
                with self.assertRaises(ValueError):
                    launcher.request_cancel(other)

            self.assertTrue((game / "cancel-export").is_file())
            self.assertFalse((other / "client" / "cancel-export").exists())
            process.terminate.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()

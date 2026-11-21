import importlib.util
from io import BytesIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location("launch_export", Path(__file__).with_name("launch-export.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LaunchExportTest(unittest.TestCase):
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

            launcher.prepare_game(instance, game, exporter, [native], launcher.last_world(root))

            self.assertEqual({path.name for path in (game / "mods").iterdir()}, {"Example.jar", exporter.name})
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
            self.assertEqual(arguments[-4:], ["--width", "480", "--height", "320"])

            recipes = launcher.java_arguments(expected, install, game, "neoforge-21.1.249", 8, "png", False, ["recipes"])
            self.assertIn("-Dwebcraft.export.sections=recipes", recipes)

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

    def test_ponder_only_archive_requires_its_scene_index(self):
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with ZipFile(archive, "w") as output:
                output.writestr("format.txt", "webcraft-previews-v1/1.21.1\n")
                output.writestr("export-diagnostics.json", json.dumps({"selectedSections": ["ponder"]}))
            with self.assertRaisesRegex(ValueError, "ZIP validation"):
                launcher.validate_archive(archive, ["ponder"])
            with ZipFile(archive, "a") as output:
                output.writestr("ponder/index.json", '{}')
            launcher.validate_archive(archive, ["ponder"])

    def test_prints_latest_progress_each_minute_and_ignores_unrelated_log_lines(self):
        log = BytesIO(b"Unrelated startup message\nStarting WebCraft automatic catalog export\n"
                      b"WebCraft export progress: Step 1/3 - Prepare item catalog\n"
                      b"WebCraft export progress: Step 2/3 - Render item previews (42 / 100 stacks processed)\n")
        process = Mock()
        process.wait.side_effect = [launcher.subprocess.TimeoutExpired("java", 60),
                                    launcher.subprocess.TimeoutExpired("java", 60), 0]
        with patch.object(launcher, "monotonic", side_effect=[10, 70, 130]), patch("builtins.print") as output:
            self.assertEqual(launcher.wait_for_export(process, log), 0)
        message = "Step 2/3 - Render item previews (42 / 100 stacks processed)"
        self.assertEqual([call.args[0] for call in output.call_args_list],
                         [f"[01:00] {message}", f"[02:00] {message}"])

    def test_progress_reader_retries_a_log_line_still_being_written(self):
        log = BytesIO(b"WebCraft export progress: Step 1/3")
        self.assertEqual(launcher.latest_progress(log, "Starting"), "Starting")
        self.assertEqual(log.tell(), 0)
        log.seek(0, 2)
        log.write(b" - Prepare item catalog\n")
        log.seek(0)
        self.assertEqual(launcher.latest_progress(log, "Starting"), "Step 1/3 - Prepare item catalog")

    @unittest.skipUnless(launcher.os.name == "nt", "Windows process flags")
    def test_cancellation_signals_the_isolated_client(self):
        with TemporaryDirectory() as directory:
            run = Path(directory)
            game = run / "client"
            game.mkdir()
            process = Mock()
            process.wait.side_effect = [KeyboardInterrupt, 0]
            with patch.object(launcher.subprocess, "Popen", return_value=process):
                with self.assertRaises(KeyboardInterrupt):
                    launcher.run_client(Path("java.exe"), [], game, run)
            self.assertTrue((game / "cancel-export").is_file())
            process.terminate.assert_not_called()


if __name__ == "__main__":
    unittest.main()

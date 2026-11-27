"""Run a catalog export in an isolated Windows CurseForge client."""

import argparse
from contextlib import contextmanager, ExitStack, suppress
from datetime import datetime
import gzip
from hashlib import sha256
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
from time import monotonic
from uuid import uuid4
from zipfile import BadZipFile, ZipFile
from pack_ponder import pack_ponder_archive
from preview_atlas import INDEX as ATLAS_INDEX, validate_atlas


REPO = Path(__file__).resolve().parents[3]
WORLD = "WebCraft Export"
PACK_DIRECTORIES = ("config", "defaultconfigs", "kubejs", "resourcepacks",
                    "datapacks", "moonlight-global-datapacks", "patchouli_books")
COPIED_OPTIONS = ("version:", "lang:", "resourcePacks:", "incompatibleResourcePacks:")
DEFAULT_SECTIONS = ("items", "recipes", "tooltips", "designs")
SECTIONS = (*DEFAULT_SECTIONS, "ponder", "ponder-legacy", "ponder-definitions")
PROGRESS_INTERVAL = 60
PROGRESS_MARKER = b"WebCraft export progress: "
EXPORTS = REPO / ".local-artifacts" / "webcraft-instance-exports"


class ExportCancelled(Exception):
    pass


def canonical_section(section):
    return "ponder" if section == "ponder-compact" else section


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def versions(instance):
    manifest = instance / "manifest.json"
    if manifest.is_file():
        minecraft = read_json(manifest)["minecraft"]
        version = minecraft["version"]
        loader = next((entry["id"] for entry in minecraft["modLoaders"]
                       if entry["id"].startswith("neoforge-")), None)
    else:
        profile = read_json(instance / "minecraftinstance.json")
        version = profile.get("gameVersion")
        loader = (profile.get("baseModLoader") or {}).get("name")
    if version != "1.21.1" or not isinstance(loader, str) or not re.fullmatch(r"neoforge-21\.1\.\d+", loader):
        raise ValueError("This exporter supports CurseForge instances with Minecraft 1.21.1 and NeoForge 21.1.")
    return version, loader


def allowed(entry):
    rules = entry.get("rules")
    if not rules:
        return True
    allow = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule.get("name", "windows") != "windows":
            continue
        if os_rule.get("version") and not re.search(os_rule["version"], platform.version()):
            continue
        if os_rule.get("arch") not in (None, "x86_64", "amd64"):
            continue
        if any(rule.get("features", {}).values()):
            continue
        allow = rule["action"] == "allow"
    return allow


def installation(install, minecraft, loader):
    vanilla, neo = (read_json(install / "versions" / name / f"{name}.json") for name in (minecraft, loader))
    libraries = {}
    natives = set()
    directory = install / "libraries"
    for metadata in (vanilla, neo):
        for library in metadata["libraries"]:
            if not allowed(library):
                continue
            group, artifact, _, *classifier = library["name"].split(":")
            downloads = library.get("downloads") or {}
            if artifact_file := downloads.get("artifact"):
                libraries[(group, artifact, tuple(classifier))] = directory / artifact_file["path"]
            if native := (library.get("natives") or {}).get("windows"):
                path = downloads["classifiers"][native.replace("${arch}", "64")]["path"]
                natives.add(directory / path)
    classpath = [*libraries.values(), install / "versions" / loader / f"{loader}.jar"]
    asset_index = install / "assets" / "indexes" / (vanilla["assetIndex"]["id"] + ".json")
    for path in [*classpath, *sorted(natives), asset_index]:
        if not path.is_file():
            raise FileNotFoundError(f"Missing CurseForge installation file: {path}")
    return vanilla, neo, classpath, sorted(natives)


def expand(entries, placeholders):
    result = []
    for entry in entries:
        if isinstance(entry, dict):
            if not allowed(entry):
                continue
            entry = entry["value"]
        for value in entry if isinstance(entry, list) else [entry]:
            result.append(re.sub(r"\$\{([^}]+)\}", lambda match: str(placeholders[match[1]]), value))
    return result


def java_arguments(plan, install, game, loader, heap, image_format, include_hidden, sections=None, boost=None,
                   ponder_scenes=None, preview_atlas=None, reference_ticks=None):
    vanilla, neo, classpath, _ = plan
    placeholders = {
        "natives_directory": game / "natives",
        "launcher_name": "WebCraftExport",
        "launcher_version": "1",
        "classpath": os.pathsep.join(map(str, classpath)),
        "classpath_separator": os.pathsep,
        "library_directory": install / "libraries",
        "auth_player_name": "WebCraftExport",
        "auth_uuid": "a6b89684c3c94506a375f89320d4de85",
        "auth_access_token": "0",
        "clientid": "",
        "auth_xuid": "",
        "user_type": "legacy",
        "version_name": loader,
        "version_type": "release",
        "game_directory": game,
        "assets_root": install / "assets",
        "assets_index_name": vanilla["assetIndex"]["id"],
    }
    section_option = ["-Dwebcraft.export.sections=" + ",".join(sections)] if sections is not None else []
    boost_option = [f"-Dwebcraft.export.backgroundBoost={str(boost).lower()}"] if boost is not None else []
    ponder_option = ["-Dwebcraft.export.ponderScenes=" + ",".join(ponder_scenes)] if ponder_scenes is not None else []
    reference_option = ["-Dwebcraft.export.ponderReferenceTicks=" + ",".join(map(str, reference_ticks))] if reference_ticks else []
    atlas_option = [f"-Dwebcraft.export.previewAtlas={str(preview_atlas).lower()}"] if preview_atlas is not None else []
    return [
        "-Xms1G", f"-Xmx{heap}G", "-XX:+UseG1GC",
        "-Dwebcraft.automaticExport=true", f"-Dwebcraft.export.includeHidden={str(include_hidden).lower()}",
        f"-Dwebcraft.export.png={str(image_format == 'png').lower()}",
        *section_option, *boost_option, *ponder_option, *atlas_option, *reference_option,
        *expand(vanilla["arguments"]["jvm"], placeholders), *expand(neo["arguments"]["jvm"], placeholders),
        neo["mainClass"],
        *expand(neo["arguments"]["game"], placeholders), *expand(vanilla["arguments"]["game"], placeholders),
        "--width", "480", "--height", "320",
    ]


def argfile_line(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def path_to_java(command):
    probe = subprocess.run([command, "-XshowSettings:properties", "-version"],
                           check=True, text=True, capture_output=True)
    version = re.search(r"(?m)^\s*java\.specification\.version\s*=\s*(\S+)", probe.stderr)
    home = re.search(r"(?m)^\s*java\.home\s*=\s*(.+)$", probe.stderr)
    if not version or version[1] != "21" or not home:
        raise ValueError("A Java 21 installation is required. Use --java to select it.")
    return Path(home[1].strip()) / "bin" / "java.exe"


def exporter_jar():
    version_file = REPO / "modules/webcraft/core/gradle.properties"
    version = re.search(r"(?m)^\s*modVersion\s*=\s*(\S+)", version_file.read_text(encoding="utf-8"))
    if not version:
        raise ValueError(f"Could not read the WebCraft version in {version_file}")
    return REPO / f"modules/webcraft/core/build/libs/webcraft-{version[1]}.jar"


def build_exporter(java):
    gradle = shutil.which("gradle")
    if not gradle:
        raise ValueError("Gradle is required to build WebCraft. Use --webcraft-jar to supply a built JAR.")
    subprocess.run([gradle, ":webcraft-core:assemble"], cwd=REPO, check=True,
                   env={**os.environ, "JAVA_HOME": str(java.parent.parent)})
    return exporter_jar()


def print_build(jar):
    modified = datetime.fromtimestamp(jar.stat().st_mtime).astimezone()
    print(f"WebCraft JAR: {jar}", flush=True)
    print(f"WebCraft build (JAR last modified): {modified.isoformat(sep=' ', timespec='seconds')}", flush=True)


def prepare_game(instance, game, exporter, natives, previous_world):
    game.mkdir(parents=True)
    ignore = shutil.ignore_patterns("session.lock", "sessions", "cache", "user_variables.db",
                                    "*.local", "account*.json", "secrets*", "tokens*")
    for name in PACK_DIRECTORIES:
        if (instance / name).is_dir():
            shutil.copytree(instance / name, game / name, ignore=ignore)
    manifest = instance / "manifest.json"
    if manifest.is_file():
        shutil.copy2(manifest, game / "manifest.json")
    for name in ("mods", "natives", "saves"):
        (game / name).mkdir()
    for mod in (instance / "mods").glob("*.jar"):
        # The local build replaces prior WebCraft installs. Crash Assistant can open a foreground
        # dialog on process interruption, so omit it from this disposable copy.
        if not mod.name.lower().startswith(("webcraft-", "webcraft_", "crashassistant-")):
            shutil.copy2(mod, game / "mods" / mod.name)
    installed_exporter = game / "mods" / exporter.name
    shutil.copy2(exporter, installed_exporter)
    for native in natives:
        with ZipFile(native) as archive:
            for entry in archive.namelist():
                if entry.endswith(".dll"):
                    (game / "natives" / Path(entry).name).write_bytes(archive.read(entry))
    if previous_world is not None:
        shutil.copytree(previous_world, game / "saves" / WORLD, ignore=ignore)
    options = instance / "options.txt"
    lines = [line for line in options.read_text(encoding="utf-8-sig").splitlines()
             if line.startswith(COPIED_OPTIONS)] if options.is_file() else []
    lines.extend(("fullscreen:false", "renderDistance:4", "simulationDistance:4", "maxFps:60",
                  "pauseOnLostFocus:false", "soundCategory_master:0.0", "onboardAccessibility:false"))
    (game / "options.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    fml = game / "config" / "fml.toml"
    fml.parent.mkdir(exist_ok=True)
    loader_options = fml.read_text(encoding="utf-8-sig") if fml.is_file() else ""
    # The early NeoForge window appears before the client can apply GLFW visibility hints.
    loader_options = re.sub(r"(?m)^\s*earlyWindowControl\s*=.*$\n?", "", loader_options)
    fml.write_text("earlyWindowControl = false\n" + loader_options, encoding="utf-8")
    return installed_exporter


def last_world(root):
    current = root / "latest" / "world"
    if (current / "level.dat").is_file():
        return current
    pointer = root / "last-successful-run.txt"
    if not pointer.is_file():
        return None
    name = pointer.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"run-[0-9a-f]{32}", name):
        return None
    world = (root / name / "client" / "saves" / WORLD).resolve()
    return world if world.is_relative_to(root) and (world / "level.dat").is_file() else None


def profile_preview_atlas(instance):
    config = instance / "config" / "webcraft-export.toml"
    if not config.is_file():
        return False
    # The NeoForge export config uses top-level, single-line boolean settings.
    for line in config.read_text(encoding="utf-8-sig").splitlines():
        if line.lstrip().startswith("[") and re.match(r"\[\[?\s*[\w\"']", line.lstrip()):
            break
        match = re.fullmatch(r"""\s*(?:previewAtlas|"previewAtlas"|'previewAtlas')\s*=\s*(true|false)\s*(?:#.*)?""", line)
        if match:
            return match[1] == "true"
    return False


def configuration_key(pack, image_format, hidden, sections, scenes, preview_atlas=False, reference_ticks=None):
    if image_format == "webp" and not hidden and not sections and not scenes and not preview_atlas and not reference_ticks:
        return pack
    # Keep previous output and world directories when only the section name changes.
    storage_ids = {"ponder": "ponder-compact", "ponder-legacy": "ponder"}
    section_keys = {storage_ids.get(section, section) for section in map(canonical_section, sections or [])}
    options = [pack, image_format, hidden, sorted(section_keys), sorted(set(scenes or []))]
    if preview_atlas:
        options.append("previewAtlas")
    if reference_ticks:
        options.append({"ponderReferenceTicks": sorted(set(reference_ticks))})
    return sha256(json.dumps(options).encode()).hexdigest()[:12]


@contextmanager
def pack_lock(root):
    import msvcrt
    root.mkdir(parents=True, exist_ok=True)
    with (root / "export.lock").open("a+b") as lock:
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise ValueError(f"Another export is using {root}") from error
        previous = root / "previous"
        if previous.exists() and not (root / "latest").exists():
            previous.rename(root / "latest")
        yield


def publish_export(run, archive):
    root = run.parent
    latest = root / "latest"
    previous = root / "previous"
    world = run / "client" / "saves" / WORLD
    if not (world / "level.dat").is_file():
        raise ValueError("The completed export is missing its working world.")
    try:
        with gzip.open(world / "level.dat") as data:
            if data.read(1) != b"\x0a":
                raise ValueError("The export world is missing its root NBT compound.")
            while data.read(65536):
                pass  # Read through the gzip checksum before replacing the previous world.
    except EOFError as error:
        raise ValueError("The export world has truncated level data.") from error
    ready = run / "ready"
    ready.mkdir()
    archive.rename(ready / "previews.zip")
    world.rename(ready / "world")
    references = run / "client" / "webcraft-exports" / "ponder-reference"
    if references.is_dir():
        references.rename(ready / "ponder-reference")
    if previous.exists():
        shutil.rmtree(previous)
    if latest.exists():
        latest.rename(previous)
    try:
        ready.rename(latest)
    except OSError:
        if previous.exists():
            previous.rename(latest)
        raise
    if previous.exists():
        shutil.rmtree(previous)
    (root / "last-failure.log").unlink(missing_ok=True)
    return latest / "previews.zip"


@contextmanager
def staging_run(root):
    run = root / ("run-" + uuid4().hex)
    run.mkdir()
    try:
        yield run
    except BaseException as error:
        with (root / "last-failure.log").open("wb") as report:
            report.write((str(error) + "\n").encode("utf-8"))
            if (run / "client.log").is_file():
                with (run / "client.log").open("rb") as log:
                    log.seek(max(0, log.seek(0, os.SEEK_END) - 65536))
                    report.write(log.read())
        raise
    finally:
        shutil.rmtree(run)


def signal_cancel(marker):
    try:
        with marker.open("x"):
            pass
    except FileExistsError:
        pass


def request_cancel(path):
    run = path.resolve(strict=True)
    if (run.parent.parent != EXPORTS.resolve() or not re.fullmatch(r"[0-9a-f]{12}", run.parent.name)
            or not re.fullmatch(r"run-[0-9a-f]{32}", run.name) or not (run / "client.args").is_file()):
        raise ValueError("Pass the isolated run directory printed as 'Export run'.")
    game = run / "client"
    if not game.is_dir() or game.resolve() != game:
        raise ValueError("The isolated client directory could not be verified.")
    signal_cancel(game / "cancel-export")
    print(f"Cancellation requested for: {run}", flush=True)


def watch_npm_shell(resources):
    if os.environ.get("npm_lifecycle_event") != "export":
        return lambda: False
    import _winapi

    # npm can terminate its script shell without notifying the interpreter.
    # Retain the shell's handle so a recycled PID cannot mask its exit.
    try:
        handle = _winapi.OpenProcess(_winapi.SYNCHRONIZE, False, os.getppid())
    except OSError as error:
        if error.winerror == 87:  # ERROR_INVALID_PARAMETER: the parent PID no longer exists.
            raise ExportCancelled from error
        raise
    resources.callback(_winapi.CloseHandle, handle)
    return lambda: _winapi.WaitForSingleObject(handle, 0) == _winapi.WAIT_OBJECT_0


@contextmanager
def defer_console_interrupts():
    interrupted = False

    def request_stop(_number, _frame):
        nonlocal interrupted
        interrupted = True

    # Defer exceptions while Popen acquires the JVM handle and while cleanup owns it.
    with ExitStack() as handlers:
        for number in (signal.SIGINT, signal.SIGBREAK):
            previous = signal.signal(number, request_stop)
            handlers.callback(signal.signal, number, previous)
        yield lambda: interrupted


def run_client(java, arguments, game, run, sections=None, parent_exited=None, preview_atlas=None):
    with defer_console_interrupts() as interrupted:
        argfile = run / "client.args"
        # Only this isolated automatic client should stop when its launcher exits.
        arguments = ["-Dwebcraft.export.watchLauncher=true", *arguments]
        argfile.write_text("\n".join(map(argfile_line, arguments)) + "\n", encoding="utf-8")
        log = run / "client.log"
        cancel_file = game / "cancel-export"

        def cancel_requested():
            return interrupted() or cancel_file.is_file() or (parent_exited is not None and parent_exited())

        print(f"Export run: {run}", flush=True)
        print(f"Export log: {log}", flush=True)
        if cancel_requested():
            raise ExportCancelled
        with log.open("wb") as output, log.open("rb") as updates:
            process = subprocess.Popen([str(java), "@" + str(argfile)], cwd=game,
                                       stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
            try:
                code = wait_for_export(process, updates, cancel_requested)
            except BaseException:
                try:
                    signal_cancel(cancel_file)
                    with suppress(OSError):
                        print("Stopping the export client (up to 20 seconds)...", flush=True)
                    process.wait(timeout=20)
                except (subprocess.TimeoutExpired, OSError, KeyboardInterrupt):
                    process.terminate()
                    process.wait()
                raise
        if cancel_requested():
            raise ExportCancelled
        archive = game / "webcraft-exports" / "previews.zip"
        if code != 0 or not archive.is_file():
            raise ValueError(f"Minecraft exited without a completed export (exit {code}). See {log}")
        validate_archive(archive, sections, preview_atlas)
        if cancel_requested():
            raise ExportCancelled
        return archive


def wait_for_export(process, updates, cancel_requested):
    started = monotonic()
    next_progress = started + PROGRESS_INTERVAL
    progress = "Starting Minecraft and loading the export world"
    while True:
        if cancel_requested():
            raise ExportCancelled
        try:
            return process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            now = monotonic()
            if now < next_progress:
                continue
            next_progress = now + PROGRESS_INTERVAL
            progress = latest_progress(updates, progress)
            elapsed = int(now - started)
            minutes, seconds = divmod(elapsed, 60)
            message = f"[{minutes:02d}:{seconds:02d}] {progress}"
            encoding = sys.stdout.encoding or "utf-8"
            # Scene titles and Java log encodings can contain characters the Windows console cannot print.
            print(message.encode(encoding, errors="backslashreplace").decode(encoding), flush=True)


def latest_progress(updates, previous):
    position = updates.tell()
    while line := updates.readline():
        if not line.endswith(b"\n"):
            updates.seek(position)
            break
        position += len(line)
        if PROGRESS_MARKER in line:
            previous = line.partition(PROGRESS_MARKER)[2].decode("utf-8", errors="replace").strip()
        elif b"Starting WebCraft automatic catalog export" in line:
            previous = "WebCraft export started"
    return previous


def validate_archive(archive, sections, preview_atlas=None):
    with ZipFile(archive) as zip_file:
        files = set(zip_file.namelist())
        required = {"format.txt", "export-diagnostics.json"}
        if not required <= files:
            raise ValueError(f"The export did not pass ZIP validation: {archive}")
        diagnostics = json.loads(zip_file.read("export-diagnostics.json"))
        selected = diagnostics.get("selectedSections", list(DEFAULT_SECTIONS)) if isinstance(diagnostics, dict) else None
        if not isinstance(selected, list) or not selected or any(canonical_section(section) not in SECTIONS for section in selected):
            raise ValueError(f"The export has invalid section metadata: {archive}")
        selected = set(map(canonical_section, selected))
        if sections is not None and selected != set(map(canonical_section, sections)):
            raise ValueError(f"The export does not contain the requested sections: {archive}")
        if selected & {"items", "tooltips"}:
            required.add("catalog.json")
        if "recipes" in selected:
            required.add("catalog-data/recipes.json")
        if "ponder-legacy" in selected:
            required.add("ponder/index.json")
        if "ponder-definitions" in selected:
            required.add("ponder/definitions.json")
        if "ponder" in selected:
            required.update(("ponder/definitions.json", "ponder/block-models.json"))
        if not required <= files or zip_file.testzip() is not None:
            raise ValueError(f"The export did not pass ZIP validation: {archive}")
        if preview_atlas is not None and (ATLAS_INDEX in files) != (preview_atlas and "items" in selected):
            raise ValueError("The export does not match the requested preview atlas mode. Rebuild the WebCraft exporter.")
        if ATLAS_INDEX in files:
            validate_atlas(zip_file, files)
        if selected & {"ponder", "ponder-definitions"} and "ponder-legacy" not in selected:
            definitions = json.loads(zip_file.read("ponder/definitions.json"))
            playback = definitions.get("playback") if isinstance(definitions, dict) else None
            if not isinstance(playback, dict) or not playback.get("scenes"):
                raise ValueError("The Ponder definitions export has no animation timelines. Rebuild the WebCraft exporter.")


def print_ponder_archive_sizes(archive):
    labels = {
        "meshes": "Captured mesh buffers",
        "mesh-chunks": "Small mesh bundles",
        "particle-quads": "Particle quad parameters",
        "outline-cuboids": "Outline cuboid parameters",
        "box-faces": "Rectangular face parameters",
        "rigid-batches": "Animated model poses",
        "rigid-parts": "Shared animated model parts",
        "rigid-attributes": "Shared animated model vertex attributes",
        "rigid-normals": "Shared animated model normal palettes",
        "rigid-glint": "Foil poses on shared animated model parts",
        "textures": "Textures and animation metadata",
        "scenes": "Scene timelines and frames",
        "picking": "Block picking data",
        "schematics": "Source schematics",
        "definitions.json": "Registered definitions",
        "block-models.json": "Shared block model palette",
        "index.json": "Scene index",
        "other": "Other Ponder assets",
    }
    with ZipFile(archive) as zip_file:
        groups = {}
        for entry in zip_file.infolist():
            parts = entry.filename.split("/", 2)
            if parts[0] == "ponder" and len(parts) > 1 and not entry.is_dir():
                key = parts[1] if parts[1] in labels else "other"
                if key == "scenes" and "/meshes/" in entry.filename:
                    key = "mesh-chunks"
                elif key == "scenes" and "/picking/" in entry.filename:
                    key = "picking"
                groups.setdefault(key, []).append(entry)
        if not groups:
            return
        report = json.loads(zip_file.read("export-diagnostics.json"))
        ponder_report = report.get("ponder", {})
        texture_sources = ponder_report.get("textureSources", {})
        excluded = ponder_report.get("excludedDrawSnapshotsByScene", {})

    print("Ponder archive sizes (MiB):", flush=True)
    packed_total = sum(entry.compress_size for entries in groups.values() for entry in entries) / 1024**2
    raw_total = sum(entry.file_size for entries in groups.values() for entry in entries) / 1024**2
    file_count = sum(map(len, groups.values()))
    print(f"  Total: {file_count:,} files, {packed_total:.2f} packed payload, {raw_total:.2f} unpacked", flush=True)
    for key, label in labels.items():
        entries = groups.get(key)
        if not entries:
            continue
        packed = sum(entry.compress_size for entry in entries) / 1024**2
        raw = sum(entry.file_size for entry in entries) / 1024**2
        print(f"  {label}: {len(entries):,} files, {packed:.2f} packed, {raw:.2f} unpacked", flush=True)
        if key not in {"meshes", "textures"}:
            continue
        for entry in sorted(entries, key=lambda entry: (-entry.compress_size, entry.filename))[:3]:
            source = texture_sources.get(entry.filename, entry.filename)
            print(f"    {entry.compress_size / 1024:.1f} KiB packed: {source}", flush=True)
    if excluded:
        counts = {}
        for scene in excluded.values():
            for shader, count in scene.items():
                counts[shader] = counts.get(shader, 0) + count
        top = sorted(counts.items(), key=lambda entry: (-entry[1], entry[0]))[:3]
        details = ", ".join(f"{shader}: {count:,}" for shader, count in top)
        print(f"  Unsupported draw snapshots across {len(excluded):,} scenes: {sum(counts.values()):,} ({details})", flush=True)
    print("  Per-scene capture and sharing statistics: export-diagnostics.json", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path, help="CurseForge instance directory or the export run directory with --cancel")
    parser.add_argument("--install", type=Path, help="CurseForge Install directory when it is outside the default layout")
    parser.add_argument("--java", default="java", help="A Java 21 executable")
    parser.add_argument("--webcraft-jar", type=Path, help="Use a built WebCraft JAR instead of building the project")
    parser.add_argument("--heap-gb", default=8, type=int, help="Maximum client heap in GiB (default: 8)")
    parser.add_argument("--format", choices=("webp", "png"), default="webp")
    parser.add_argument("--preview-atlas", action=argparse.BooleanOptionalAction,
                        help="Override static WebP atlas packing in the instance's WebCraft export configuration")
    parser.add_argument("--include-hidden", action="store_true", help="Export the full item registry")
    parser.add_argument("--sections", nargs="+", type=canonical_section, choices=SECTIONS,
                        help="Override the sections in the modpack's WebCraft export configuration for this run")
    parser.add_argument("--ponder-scenes", nargs="+", metavar="SCHEMATIC_ID",
                        help="Limit Ponder data to these registered schematics for this run")
    parser.add_argument("--ponder-reference-ticks", nargs="+", type=int, metavar="TICK",
                        help="Save native Minecraft scene PNGs at these ticks for visual comparison")
    parser.add_argument("--boost", action=argparse.BooleanOptionalAction,
                        help="Override the experimental background capture boost for this run")
    parser.add_argument("--dry-run", action="store_true", help="Check the launch configuration without building or running Minecraft")
    parser.add_argument("--cancel", action="store_true", help="Request cancellation of the specified isolated export run")
    parser.add_argument("--pack-ponder", type=Path, metavar="OUTPUT_ZIP",
                        help="Pack the input compact previews ZIP for Capycraft without starting Minecraft")
    args = parser.parse_args()
    resources = ExitStack()
    try:
        if args.pack_ponder:
            if not pack_ponder_archive(args.instance, args.pack_ponder, report=lambda message: print(message, flush=True)):
                raise ValueError("Expected an unpacked compact Ponder export.")
            print_ponder_archive_sizes(args.pack_ponder)
            return 0
        if os.name != "nt" or platform.machine().lower() not in ("amd64", "x86_64"):
            raise ValueError("This launcher targets Windows x64.")
        if args.cancel:
            request_cancel(args.instance)
            return 0
        parent_exited = watch_npm_shell(resources)
        if args.heap_gb < 2:
            raise ValueError("--heap-gb must be at least 2.")
        if args.sections is not None:
            args.sections = list(dict.fromkeys(args.sections))
            if "ponder" in args.sections and {"ponder-legacy", "ponder-definitions"}.intersection(args.sections):
                raise ValueError("The ponder section cannot be combined with ponder-legacy or ponder-definitions.")
        if args.ponder_scenes and any(not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", value) for value in args.ponder_scenes):
            raise ValueError("--ponder-scenes requires namespaced schematic IDs.")
        if args.ponder_reference_ticks:
            args.ponder_reference_ticks = sorted(set(args.ponder_reference_ticks))
            if not args.ponder_scenes or not all(0 <= tick < 100_000 for tick in args.ponder_reference_ticks):
                raise ValueError("--ponder-reference-ticks requires --ponder-scenes and ticks between 0 and 99999.")
        instance = args.instance.resolve(strict=True)
        if args.preview_atlas and args.format != "webp":
            raise ValueError("--preview-atlas requires WebP output.")
        preview_atlas = args.format == "webp" and (profile_preview_atlas(instance)
                                                   if args.preview_atlas is None else args.preview_atlas)
        if not (instance / "mods").is_dir():
            raise ValueError(f"No mods directory in {instance}")
        install = (args.install or instance.parent.parent / "Install").resolve(strict=True)
        minecraft, loader = versions(instance)
        plan = installation(install, minecraft, loader)
        java = path_to_java(args.java)
        manifest = instance / "manifest.json"
        pack_version = manifest.read_bytes() if manifest.is_file() else f"{minecraft}\n{loader}".encode()
        fingerprint = sha256(str(instance).encode() + b"\n" + pack_version).hexdigest()[:12]
        key = configuration_key(fingerprint, args.format, args.include_hidden, args.sections, args.ponder_scenes,
                                preview_atlas, args.ponder_reference_ticks)
        root = (EXPORTS / key).resolve()
        provided_jar = args.webcraft_jar.resolve(strict=True) if args.webcraft_jar else None
        if provided_jar is not None and not provided_jar.is_file():
            raise ValueError(f"Not a WebCraft JAR file: {provided_jar}")
        if args.dry_run:
            java_arguments(plan, install, root / "client", loader, args.heap_gb, args.format,
                           args.include_hidden, args.sections, args.boost, args.ponder_scenes, preview_atlas, args.ponder_reference_ticks)
            print(f"Minecraft {minecraft}, {loader}, Java {java}")
            print(f"Static preview atlas: {'on' if preview_atlas else 'off'}")
            print(f"Isolated export directory: {root}")
            local_build = provided_jar or exporter_jar()
            if local_build.is_file():
                print_build(local_build)
            else:
                print(f"WebCraft JAR will be built before export: {local_build}")
            return 0
        if parent_exited():
            raise ExportCancelled
        exporter = provided_jar or build_exporter(java)
        if parent_exited():
            raise ExportCancelled
        resources.enter_context(pack_lock(root))
        with staging_run(root) as run:
            game = run / "client"
            installed_exporter = prepare_game(instance, game, exporter, plan[3], last_world(root))
            print_build(installed_exporter)
            arguments = java_arguments(plan, install, game, loader, args.heap_gb, args.format,
                                       args.include_hidden, args.sections, args.boost, args.ponder_scenes, preview_atlas, args.ponder_reference_ticks)
            archive = run_client(java, arguments, game, run, args.sections, parent_exited, preview_atlas)
            if args.ponder_reference_ticks and not any((game / "webcraft-exports" / "ponder-reference").glob("*.png")):
                raise ValueError("None of the requested Ponder reference ticks were captured.")
            def check_packing_cancel():
                if parent_exited() or (game / "cancel-export").is_file():
                    raise ExportCancelled
            print("Packing compact Ponder resources if present", flush=True)
            packed = run / "packed-previews.zip"
            if pack_ponder_archive(archive, packed, check_packing_cancel, lambda message: print(message, flush=True)):
                archive = packed
            print_ponder_archive_sizes(archive)
            archive = publish_export(run, archive)
            print(f"Export ready: {archive}", flush=True)
            if (archive.parent / "ponder-reference").is_dir():
                print(f"Native Ponder reference frames: {archive.parent / 'ponder-reference'}", flush=True)
            return 0
    except (KeyboardInterrupt, ExportCancelled):
        print("WebCraft export cancelled.", file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, BadZipFile, subprocess.CalledProcessError) as error:
        print(f"WebCraft launcher: {error}", file=sys.stderr)
        return 1
    finally:
        resources.close()


if __name__ == "__main__":
    raise SystemExit(main())

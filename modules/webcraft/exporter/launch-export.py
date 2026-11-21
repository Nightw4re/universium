"""Run a catalog export in an isolated Windows CurseForge client."""

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
from time import monotonic
from uuid import uuid4
from zipfile import BadZipFile, ZipFile


REPO = Path(__file__).resolve().parents[3]
WORLD = "WebCraft Export"
PACK_DIRECTORIES = ("config", "defaultconfigs", "kubejs", "resourcepacks",
                    "datapacks", "moonlight-global-datapacks", "patchouli_books")
COPIED_OPTIONS = ("version:", "lang:", "resourcePacks:", "incompatibleResourcePacks:")
DEFAULT_SECTIONS = ("items", "recipes", "tooltips", "designs")
SECTIONS = (*DEFAULT_SECTIONS, "ponder")
PROGRESS_INTERVAL = 60
PROGRESS_MARKER = b"WebCraft export progress: "


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def versions(instance):
    manifest = read_json(instance / "manifest.json")
    minecraft = manifest["minecraft"]
    loader = next((entry["id"] for entry in minecraft["modLoaders"]
                   if entry["id"].startswith("neoforge-")), None)
    if minecraft["version"] != "1.21.1" or not loader or not re.fullmatch(r"neoforge-21\.1\.\d+", loader):
        raise ValueError("This exporter supports CurseForge instances with Minecraft 1.21.1 and NeoForge 21.1.")
    return minecraft["version"], loader


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


def java_arguments(plan, install, game, loader, heap, image_format, include_hidden, sections=None):
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
    return [
        "-Xms1G", f"-Xmx{heap}G", "-XX:+UseG1GC",
        "-Dwebcraft.automaticExport=true", f"-Dwebcraft.export.includeHidden={str(include_hidden).lower()}",
        f"-Dwebcraft.export.png={str(image_format == 'png').lower()}",
        *section_option,
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


def build_exporter(java):
    gradle = shutil.which("gradle")
    if not gradle:
        raise ValueError("Gradle is required to build WebCraft. Use --webcraft-jar to supply a built JAR.")
    subprocess.run([gradle, ":webcraft-core:assemble"], cwd=REPO, check=True,
                   env={**os.environ, "JAVA_HOME": str(java.parent.parent)})
    version_file = REPO / "modules/webcraft/core/gradle.properties"
    version = re.search(r"(?m)^\s*modVersion\s*=\s*(\S+)", version_file.read_text(encoding="utf-8"))
    if not version:
        raise ValueError(f"Could not read the WebCraft version in {version_file}")
    return REPO / f"modules/webcraft/core/build/libs/webcraft-{version[1]}.jar"


def prepare_game(instance, game, exporter, natives, previous_world):
    game.mkdir(parents=True)
    ignore = shutil.ignore_patterns("session.lock", "sessions", "cache", "user_variables.db",
                                    "*.local", "account*.json", "secrets*", "tokens*")
    for name in PACK_DIRECTORIES:
        if (instance / name).is_dir():
            shutil.copytree(instance / name, game / name, ignore=ignore)
    shutil.copy2(instance / "manifest.json", game / "manifest.json")
    for name in ("mods", "natives", "saves"):
        (game / name).mkdir()
    for mod in (instance / "mods").glob("*.jar"):
        # The local build replaces prior WebCraft installs. Crash Assistant can open a foreground
        # dialog on process interruption, so omit it from this disposable copy.
        if not mod.name.lower().startswith(("webcraft-", "webcraft_", "crashassistant-")):
            shutil.copy2(mod, game / "mods" / mod.name)
    shutil.copy2(exporter, game / "mods" / exporter.name)
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


def last_world(root):
    pointer = root / "last-successful-run.txt"
    if not pointer.is_file():
        return None
    name = pointer.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"run-[0-9a-f]{32}", name):
        return None
    world = (root / name / "client" / "saves" / WORLD).resolve()
    return world if world.is_relative_to(root) and (world / "level.dat").is_file() else None


def run_client(java, arguments, game, run, sections=None):
    argfile = run / "client.args"
    argfile.write_text("\n".join(map(argfile_line, arguments)) + "\n", encoding="utf-8")
    log = run / "client.log"
    print(f"Export log: {log}", flush=True)
    with log.open("wb") as output, log.open("rb") as updates:
        process = subprocess.Popen([str(java), "@" + str(argfile)], cwd=game,
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
        try:
            code = wait_for_export(process, updates)
        except KeyboardInterrupt:
            (game / "cancel-export").touch()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait()
            raise
    archive = game / "webcraft-exports" / "previews.zip"
    if code != 0 or not archive.is_file():
        raise ValueError(f"Minecraft exited without a completed export (exit {code}). See {log}")
    validate_archive(archive, sections)
    return archive


def wait_for_export(process, updates):
    started = monotonic()
    progress = "Starting Minecraft and loading the export world"
    while True:
        try:
            return process.wait(timeout=PROGRESS_INTERVAL)
        except subprocess.TimeoutExpired:
            progress = latest_progress(updates, progress)
            elapsed = int(monotonic() - started)
            minutes, seconds = divmod(elapsed, 60)
            print(f"[{minutes:02d}:{seconds:02d}] {progress}", flush=True)


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


def validate_archive(archive, sections):
    with ZipFile(archive) as zip_file:
        files = set(zip_file.namelist())
        required = {"format.txt", "export-diagnostics.json"}
        if not required <= files:
            raise ValueError(f"The export did not pass ZIP validation: {archive}")
        diagnostics = json.loads(zip_file.read("export-diagnostics.json"))
        selected = diagnostics.get("selectedSections", list(DEFAULT_SECTIONS)) if isinstance(diagnostics, dict) else None
        if not isinstance(selected, list) or not selected or any(section not in SECTIONS for section in selected):
            raise ValueError(f"The export has invalid section metadata: {archive}")
        selected = set(selected)
        if sections is not None and selected != set(sections):
            raise ValueError(f"The export does not contain the requested sections: {archive}")
        if selected & {"items", "tooltips"}:
            required.add("catalog.json")
        if "recipes" in selected:
            required.add("catalog-data/recipes.json")
        if "ponder" in selected:
            required.add("ponder/index.json")
        if not required <= files or zip_file.testzip() is not None:
            raise ValueError(f"The export did not pass ZIP validation: {archive}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path, help="Path to the installed CurseForge modpack instance")
    parser.add_argument("--install", type=Path, help="CurseForge Install directory when it is outside the default layout")
    parser.add_argument("--java", default="java", help="A Java 21 executable")
    parser.add_argument("--webcraft-jar", type=Path, help="Use a built WebCraft JAR instead of building the project")
    parser.add_argument("--heap-gb", default=8, type=int, help="Maximum client heap in GiB (default: 8)")
    parser.add_argument("--format", choices=("webp", "png"), default="webp")
    parser.add_argument("--include-hidden", action="store_true", help="Export the full item registry")
    parser.add_argument("--sections", nargs="+", choices=SECTIONS,
                        help="Override the sections in the modpack's WebCraft export configuration for this run")
    parser.add_argument("--dry-run", action="store_true", help="Check the launch configuration without building or running Minecraft")
    args = parser.parse_args()
    try:
        if os.name != "nt" or platform.machine().lower() not in ("amd64", "x86_64"):
            raise ValueError("This launcher targets Windows x64.")
        if args.heap_gb < 2:
            raise ValueError("--heap-gb must be at least 2.")
        instance = args.instance.resolve(strict=True)
        if not (instance / "mods").is_dir():
            raise ValueError(f"No mods directory in {instance}")
        install = (args.install or instance.parent.parent / "Install").resolve(strict=True)
        minecraft, loader = versions(instance)
        plan = installation(install, minecraft, loader)
        java = path_to_java(args.java)
        manifest = (instance / "manifest.json").read_bytes()
        fingerprint = sha256(str(instance).encode() + b"\n" + manifest).hexdigest()[:12]
        root = (REPO / ".local-artifacts" / "webcraft-instance-exports" / fingerprint).resolve()
        if args.dry_run:
            if args.webcraft_jar:
                args.webcraft_jar.resolve(strict=True)
            java_arguments(plan, install, root / "client", loader, args.heap_gb, args.format, args.include_hidden, args.sections)
            print(f"Minecraft {minecraft}, {loader}, Java {java}")
            print(f"Isolated export directory: {root}")
            return 0
        exporter = args.webcraft_jar.resolve(strict=True) if args.webcraft_jar else build_exporter(java)
        run = root / ("run-" + uuid4().hex)
        game = run / "client"
        prepare_game(instance, game, exporter, plan[3], last_world(root))
        arguments = java_arguments(plan, install, game, loader, args.heap_gb, args.format, args.include_hidden, args.sections)
        archive = run_client(java, arguments, game, run, args.sections)
        pointer = root / ("last-successful-" + run.name + ".txt")
        pointer.write_text(run.name + "\n", encoding="utf-8")
        pointer.replace(root / "last-successful-run.txt")
        print(f"Export ready: {archive}", flush=True)
        return 0
    except KeyboardInterrupt:
        print("WebCraft export cancelled.", file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, BadZipFile, subprocess.CalledProcessError) as error:
        print(f"WebCraft launcher: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

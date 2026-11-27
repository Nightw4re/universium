# Preview and Ponder exports

[WebCraft index](README.md)

## Export inventory previews

Build WebCraft with `gradle :webcraft-core:build` and put `webcraft-<version>.jar` from `core/build/libs/` in the Minecraft client's `mods/` directory. The exporter uses the installed modpack and active client resource packs. Client-side export works independently of the HTTP listener.

1. Join the target server with the same modpack and select the desired client language and resource packs. A singleplayer export describes that world's recipes and datapacks.
2. Run the client command `/webcraft export`. Check the sections you want and select **Export selected data**. Choose WebP (the default) or PNG under **Preview format**. For WebP, **Preview atlas** controls static image packing for this run. During export, the screen shows the current phase and the remaining phases. Completion appears once the ZIP has been saved.
3. Take `webcraft-exports/previews.zip` from the client instance and install it as `webcraft-assets/previews.zip` in the server working directory. Replace it while the server is stopped so all indexes and attachments come from the same export. Each successful export replaces the previous client archive. Include all the inventory variants you want in a new export before replacing the server copy.
4. Repeat the catalog export after changing the server's recipes or datapacks, the modpack, or the client language or resource packs. The catalog snapshot refreshes after you replace its archive and [reload WebCraft](server.md#reload-webcraft-on-a-running-server) or restart the server. A separate website can ingest the [catalog data files](catalog.md#item-data-feeds) and catalog images directly without contacting the game server.

- **Items and previews** captures the visible item catalog, public stack data, and static and animated previews of catalog and inventory stacks. It also renders the exact display stacks for advancements, including component-based models and items outside the visible catalog. The optional `catalog-data/advancement-previews.json` index maps advancement IDs to preview keys. For complete coverage, export in a singleplayer world with the server's modpack and datapacks. Multiplayer exports can include only advancements sent to the client.
- **Recipes** captures world recipes and machine recipes available through JEI or EMI. Keep only this section checked for recipe data and its referenced resources. Recipe-only ZIPs contain no `catalog.json` and cannot replace an item catalog in importers that require it.
- **Tooltips** captures item tooltips and their shortcut alternatives. A tooltip export includes a catalog index so consumers can resolve item and variant IDs.
- **Banners and armor** captures design data and its textures.
- **Ponder scenes** records registered interactive scenes and their rendered geometry. This is an opt-in export section that requires Ponder on the client. The Create mod includes Ponder.

The visible catalog skips the operator tab and items, blocks and tabs tagged `c:hidden_from_recipe_viewers`. When a recipe viewer is ready, catalog items must also appear in its visible item list. Under **Advanced options**, **Export inventory only** renders your exact inventory stacks independently of the checked sections. **Export including hidden items** applies the checked sections to every non-empty default stack in the client registry.

WebCraft saves changes to the section checkboxes in `config/webcraft-export.toml` in the Minecraft client instance. The screen restores the selection the next time it opens. You can also edit the file directly. For a recipe-only export, set `sections = ["recipes"]`. A new configuration enables items, recipes, tooltips and designs.

The client calls Minecraft's ordinary GUI item renderer in the loaded world. This includes any renderer that NeoForge selects for the stack. It captures 64 × 64 RGBA at three pixels per GUI unit, with the item's inventory pose intact. In WebP mode, the archive worker encodes those captures as lossless WebP. The [bundled encoder](exporter/codec/README.md) runs on Java 21 across client platforms. If WebP encoding fails, the export stops and the screen offers PNG as a manual retry. Animation detection uses the model quads shared by the standard item renderer and Sodium's optimized item renderer. When the rendered model uses animated atlas sprites, the exporter samples the inventory render on successive client ticks for one combined loop. This includes game-interpolated frames and active resource pack overrides. Identical consecutive samples share one frame whose duration is the sum of their ticks.

During capture, RGB tints from Productive Bees advanced hives, expansion boxes and configurable honeycomb, Ender IO dyed clear glass, Nature's Aura Ancient Leaves and Mystical Agradditions Insanium Farmland are interpreted as opaque. NeoVitae's Spatial Rift uses the Structure Void marker when its inventory render is empty. Unconfigured Domum Ornamentum blocks use their declared default materials for a stable preview. Already configured stacks use the materials stored on the stack.

The experimental CUDA comparison defaults to `experimentalCuda = true` in `config/webcraft-export.toml`. When the client's OpenGL context can share resources with the NVIDIA CUDA driver, WebCraft compares inventory animation samples and refreshed Ponder text textures on the GPU. Identical samples skip full CPU pixel readback. First and changed samples use standard capture. If CUDA initialization or comparison fails, the exporter continues with standard readback. Set `experimentalCuda = false` to use standard readback throughout. The driver compiles the bundled kernel at runtime.

The exporter samples combined loops of up to 160 ticks (8 seconds), in batches of up to 24 stacks. Retries use batches of up to four. Longer combinations, motion driven by custom renderers outside the baked-quad path, and item models that change selection with world or player state may need dedicated adapters. The ordinary static export remains available for supported, visible renders. The shared enchantment glint recipe is independent of atlas animations.

Pack v1 contains `format.txt` with `webcraft-previews-v1/1.21.1` followed by a newline. The key is the SHA-256 of the UTF-8 text produced by Minecraft's sorted SNBT serialization of the exact stack, including count and data components. The dedicated server computes the key from the live Equipment stack and reads the pre-rendered image. Inventory stacks contribute only hashed images. Keep the combined archive out of Git and public static hosting, since those images can reveal private inventory appearances. To serve a standalone site, ingest the public manifests and only images referenced by the catalog or design manifests.

Importers should resolve each `previewKey` to `previews/<key>.webp`, `previews/<key>.png`, or the matching optional atlas tile in that order. For motion, prefer the optional `animations/<key>.webp` and fall back to `animations/<key>.png` (APNG). Both animation formats preserve loop and frame durations. Serve standalone WebP bytes as `image/webp`. Design textures stay at `textures/<hash>.png`.

### Static preview atlases

Set `previewAtlas = true` in a client's `config/webcraft-export.toml` to enable atlas packing by default for its WebP exports. WebCraft defaults to `false`. The setting applies when the export includes **Items and previews**. Use atlas mode with updated WebCraft servers and atlas-aware ZIP importers. The current WebCraft server extracts atlas tiles as 64 × 64 PNGs for its single-icon responses. Legacy exports remain supported.

Atlas exports include a UTF-8 JSON document at `previews/atlas-index.json`.

```json
{
  "version": 1,
  "tileSize": 64,
  "columns": 10,
  "rows": 10,
  "atlases": ["<sha256 of the exact sheet WebP bytes>"],
  "sprites": {"<existing previewKey>": [0, 12]}
}
```

Each `sprites` entry is a zero-based `[atlasIndex, tileIndex]` pair. Sheets are lossless 640 × 640 WebPs at `previews/atlases/<sha256>.webp`. Tiles run left to right, then top to bottom. Tile 12 starts at pixel `(128, 64)`. Unused pixels are transparent. Mapped keys have only their atlas tile. Exported animations and their static fallbacks are standalone files.

An atlas can contain private inventory tiles alongside public catalog, recipe and advancement tiles. Extract only the tiles referenced by the public manifests when publishing images from a combined pack.

Each export includes `export-diagnostics.json` at the root of the ZIP. Its `selectedSections` lists the requested data sections. The report lists missing images for attempted catalog items, variants and machine recipe materials with their IDs and reasons. An item whose captured quads use only Minecraft's missing-texture sprite is reported as `missing_texture`. When the exporter resolved a recipe sprite ID, the report includes it. The report also includes whether a recipe viewer was available, how many distinct fluids and chemicals were checked and how many machine recipe captures were skipped. Private inventory failures appear as a count. A completed export with missing images shows a warning and the report filename on the export screen.

To check an existing catalog ZIP without Minecraft, run `python modules/webcraft/exporter/check-previews.py /path/to/previews.zip` with Pillow installed. Append item IDs to check a subset. The checker prints a JSON report and exits with status 1 when it finds missing previews, fully transparent images or Minecraft's black-and-magenta placeholder.

### Run a CurseForge export from the command line

On Windows x64, point the launcher at an installed CurseForge instance with Minecraft 1.21.1 and NeoForge 21.1. It reads `manifest.json` or the installed CurseForge profile `minecraftinstance.json`. The instance path is the only required argument. The npm scripts require `python` on PATH (Python 3.9 or later), and the launcher uses Java 21. You can also run `py -3 -B modules/webcraft/exporter/launch-export.py "<instance path>"` with a registered Python 3.9 or later.

```powershell
npm.cmd run export -- "C:\Path\To\CurseForge\Instances\My Modpack"
```

The launcher assembles WebCraft with Gradle and installs the resulting JAR in `mods/` inside the isolated pack copy under `.local-artifacts/webcraft-instance-exports/`. It prints the installed JAR path and the build time as reported by the JAR's last modification time on disk. The launcher then starts a small hidden OpenGL window with sound muted, opens the dedicated flat world and starts the export automatically. It copies that world from the last successful export of the same pack version when available. It creates a fresh flat world for the first run. The client exits after writing the catalog ZIP, and the launcher prints its path after verifying it. The first startup can take several minutes on a large pack. The launcher prints the client log path before starting Minecraft and omits Crash Assistant from the copy to keep crash dialogs out of the foreground.

Each pack version and export configuration keeps its verified archive in latest/previews.zip and its working save in latest/world/ under the printed configuration directory. Image format, atlas mode, hidden items, section overrides and scene filters select independent outputs. The launcher serializes runs for the same configuration. After validation and client shutdown it replaces the previous pair and removes the temporary client. A failed or cancelled run retains the previous pair and writes the latest error and up to 64 KiB of client log to last-failure.log. The next successful export removes that log.

Use `npm.cmd run export:check -- "<instance path>"` to check Java and CurseForge and see the current JAR timestamp without building, copying the pack or starting the game. Use `--webcraft-jar` with the path to an existing WebCraft JAR to skip Gradle. Use `--include-hidden` to export all registered items, `--format png` for PNG, or `--heap-gb` to adjust the default 8 GiB heap. Use `--install` for a nonstandard CurseForge installation folder.

The launcher reads `previewAtlas` from the selected profile. Pass `--preview-atlas` or `--no-preview-atlas` to override it for one run. For ATM10 8.1, set `previewAtlas = true` in the installed profile's `config/webcraft-export.toml`, or run `npm.cmd run export -- "<ATM10 8.1 instance path>" --preview-atlas`.

Press Ctrl+C in the export terminal or run `npm.cmd run export:cancel -- "<Export run path>"` from a second terminal at the Universium root. Copy the path printed as `Export run`. The launcher asks the isolated client to close and terminates it if it remains running after 20 seconds. With an updated WebCraft JAR, the isolated client also shuts down on its next tick if Python exits unexpectedly. The isolated world and log stay with that run.

The launcher uses the modpack instance's `config/webcraft-export.toml` by default. Use `--sections recipes` to override the selection for a single run. For a complete Capycraft export, configure `items`, `recipes`, `tooltips`, `designs` and `ponder`.

Automatic background exports can use an experimental capture budget of 72 ms per render batch, up from 24 ms. Enable it for a single run with `--boost` or set `experimentalBackgroundBoost = true` in the instance's `config/webcraft-export.toml`. It defaults to `false`. Use `--no-boost` to override an enabled configuration for one launch.

```powershell
npm.cmd run export -- "C:\Path\To\CurseForge\Instances\My Modpack" --sections ponder --boost
```

The boost works independently of CUDA. It yields at phase boundaries and when the bounded archive queue fills. Ponder still captures every scene tick, and inventory animation still samples on Minecraft client ticks. A single capture operation can run past the batch budget.

The launcher prints an update every minute with the elapsed time, current phase and available item counts. The client log records elapsed time and capture time for each export phase, plus time spent processing archive work. Use the render and animation phases to compare exports with the same modpack, selected sections and image format. Capture can overlap archive work, so their measured times do not add up to the total.

## Export Ponder scenes

Select **Ponder** in the exporter or include `ponder` in the configured sections. Existing configurations using `ponder-compact` select the same format. To export only the Create mechanical pump example, set the following options in the client configuration.

```toml
sections = ["ponder"]
ponderScenes = ["create:mechanical_pump/speed"]
```

An empty `ponderScenes` list selects all schematics registered in the client. The selection uses schematic IDs so scenes can be filtered before they are compiled. This export works independently of catalog and recipe exports. Import the ZIP into the site that will publish the scenes. Ponder export runs in the Minecraft client and requires no Ponder server add-on or server configuration. Use a Minecraft 1.21.1 NeoForge client with Ponder. The reference build uses Ponder 1.0.82 as shipped with Create 6.0.10.

Set `ponderAtlasPageSize = 2048` in the client profile's `config/webcraft-export.toml` to configure the maximum width and height of block atlas regions. The default is 2048 pixels and the accepted range is 256 through 2048. The exporter keeps the original pixel resolution and reuses matching padded regions. This also covers moving model faces that cannot be matched to an individual sprite. A quad draw that samples distant regions may pack them together in one page and record how to remap its texture coordinates. This also applies to a bounding crop of at least 512 × 512 pixels in area when packing saves at least half the texels. The original vertices and sorted indices are preserved. The exporter reads regions directly on OpenGL 4.5 and through a read framebuffer on earlier supported drivers. Captures use the original atlas if the regions cannot be represented or read. The `blockAtlas` diagnostic reports the readback method, unique regions and packed pages, and counts of bounds and readback fallbacks.

Full primary atlases up to 2048 × 2048 can share animation frames in the same format as block atlas regions. This includes the ATM10 particle atlas. The player updates the animated rectangles in the original texture coordinate space.

Use `--ponder-scenes create:smart_pipe create:fluid_tank/storage` to select schematics for one launcher run. Supported caption windows are stored as localized text with their placement and timing. Other scene overlays preserve their separate draw order above the scene.

Add `--ponder-reference-ticks 60 150` to the filtered export to also render those ticks through Minecraft's Ponder scene renderer. The launcher keeps the PNGs in `latest/ponder-reference/` beside the ZIP. The filenames use the scene manifest hash and tick. These are scene renders without the dialog UI or captions and are intended for comparison with the browser canvas.

Ponder exports share baked block meshes and individual sprite images and record visible block placements, section poses, camera, and text captions. A separate pass records block entity renderers, entities, particles, and outlines. Supported Catnip buffers share a local mesh and record their moving pose separately. Entity draws from Catnip's default and shade-separating buffers can reuse the same local model across poses, with the normal transform selected by the renderer. Entity parts with varying vertex colors, light, or overlays share those attributes independently of the pose. The exporter retains the original captured draw when the shared geometry cannot reproduce it. Custom baked models, liquid surface slopes, and material behavior can differ from the game.

For Catnip moving block draws with stable sprite coordinates and shading, WebCraft can reuse the neutral model directly and record only the new pose. It captures a new neutral model when the tint, render layer, or diffuse lighting changes. The per-scene client log counts those direct reuses as `poseOnlyMovingParts`.

Static block models use the same position seed as Ponder's block renderer when selecting render layers and baked faces. Blocks in the same visible section use Minecraft's neighbor face culling. The faces at a hidden section boundary remain visible. Variant choices remain stable while the block moves with its section. Identical baked results share one model entry even when they came from different seeds. A model with a position offset, such as a flower, stores the offset on its placement. Block picking uses the game outline at the original block coordinates.

When captured geometry already shares its other vertex attributes, the packer can store exact positions as a palette of coordinate values or XYZ points. The importer checks the checksum of the reconstructed vertex buffer. The archive diagnostics count the affected meshes as `positionPaletteMeshes`.

Static block quads use model data from the scene position for details such as belt casings and shaft brackets. Tinted quads use the game's block color at that position and share the resulting mesh with placements that have the same colors. A block with an empty static layer can still be drawn by its block entity renderer.

For connected textures, the exporter resolves the atlas sprite from the baked quad's UV coordinates when they lie outside the declared sprite. The shared mesh then references the connected texture sheet and keeps the selected tile within it. Faces that cross sprite boundaries sample original-resolution atlas regions. When one region cannot cover all faces in a shared model draw, the exporter can split it into consecutive groups of whole quads. It retains the full atlas if a quad cannot fit in a single region or if the split would exceed the player's draw limit.

Moving parts with identical local positions, colors and texture coordinates reuse their prepared model and texture offsets. Scene diagnostics report these reuses even when the part pose changes.

The exporter reuses shared block placements while block states and section selections stay unchanged. It checks both while collecting picking shapes in the same pass through the section. Equal picking snapshots reuse the existing resource address. During a stock timeline delay with no active 3D effects, it can also reuse picking shapes as sections move or the camera rotates. It updates section poses and continues to capture captions and input overlays.

Capycraft draws the baseplate shadow from the exported baseplate size and position. Scenes that hide the shadow omit these parameters.

Particle batches store each quad's center and corner offsets, with shared sprite coordinates, color, and light. Version 3 derives a diagonal component from the opposite sign of the first diagonal when every quad in the batch shares that relationship. It stores rounding corrections only for the quads that need them. WebCraft selects version 3 when it is smaller and Capycraft restores the original vertex bytes and submission order.

For tiled faces submitted to Catnip's fluid layer, the exporter records the face direction and bounds, pose, sprite, tint, light and tiling parameters. Capycraft tessellates these faces when playing the scene. The packed archive stores each sprite sequence once for shared block models, moving parts and fluid faces. The player uses the declared sprite durations when playing or seeking. For interpolated sprites, it blends two consecutive frames and keeps the current frame's alpha. Unsupported fluid submissions continue through mesh capture.

Standalone liquid blocks share meshes by corner heights, visible faces and fluid materials. The exporter uses the section's neighboring blocks to calculate surface slopes and excludes internal faces between blocks of the same liquid. It uses the flow direction for the upper texture, crops the flowing texture on vertical faces and selects the fluid overlay where the neighboring block requests it. Exposed fluid faces are inset to reduce depth conflicts with glass and adjacent blocks. The shared model records each translucent face's center so Capycraft can sort it with Catnip's procedural fluid faces as the camera rotates. The player draws the shared glass shells after the liquid faces.

Waterlogged blocks export separate placements for the solid model and liquid at the same position. The host block's occlusion shape hides covered liquid faces.

For fluid faces submitted by Productive Metalworks, the exporter records the rectangular bounds, shared pose, corner order, atlas coordinates and material attributes. Capycraft reconstructs the full vertex buffer before using the original translucent draw indices. The exporter checks reconstructed positions and attributes against the original submission before selecting the compact representation.

Box face batch version 3 can store parametric fluid faces and unmodified entity vertices in the same draw. It keeps both in submission order so the original sorted index buffer can interleave the fluid and item faces during playback.

Version 4 also references shared rigid parts in that ordered buffer. The Productive Metalworks tint wrapper still applies the original cooling color and opacity during capture. The exporter uses its submitted attributes with the shared local model and retains a captured range when it cannot reproduce the original vertices exactly.

Create chain faces use the same rectangular bounds and corner order with the block vertex layout. Each face retains both endpoint lights and its submitted scrolling UV coordinates. Capycraft rebuilds the vertices in the original order and can apply the original quad sorting indices.

Encoded box faces and rigid parts compare the three submitted normal components and use zero for the unused alignment byte. Chain, shadow and item geometry can use the compact representation even when the game leaves different bytes in the padding. Mixed draws retain unmodified bytes in their captured ranges.

For compatible particle batches, WebCraft records each sprite's center, corner offsets, texture coordinates, color and light. It verifies the result against the original vertex buffer and can include small signed corrections to preserve the exact submitted coordinates. Mixed or custom batches that cannot be reconstructed exactly keep their captured mesh.

Particle batch version 4 shares the unscaled sprite orientation across particles in the same draw. Each particle records its center, size, and orientation and appearance references. The exporter selects this representation when it can reproduce the original buffer exactly and costs less than separate corner offsets.
When Sodium bypasses the vanilla particle hook, WebCraft tries the Ponder camera's facing axes against the submitted positions. Quads that need different parameters retain their verified corner offsets.

For compatible Catnip cuboid outlines, WebCraft records local bounds, the pose, color and lighting. Capycraft reconstructs the ordered faces. The exporter verifies the reconstructed fields against the original draw and retains the captured mesh if the batch contains other geometry.

Compatible Minecraft and Uranus model draws, including models used by Ice and Fire, share local part geometry. A draw records ordered part references, position and normal matrices, color, overlay and light. Rotated baked models can keep a palette of the exact submitted normals for the shared part. A draw can retain ranges without a recorded pose when most of its vertices already belong to reusable parts and the result is smaller. Each such range can share its local coordinates across translations when the position matrix reproduces the submitted vertices exactly. The exporter checks the rendered vertex fields before using these representations and keeps the original index buffer where present.

Rigid batch version 5 stores only fields that differ from the default or previous pose in the batch. The reader restores the original pose rows before assembling the vertices.

Timeline chunk version 9 can share the two repeated matrix coefficients of a planar rotation. The reader restores the original matrix values exactly, including in independently seekable chunks. Matrices that do not match the rotation pattern use the existing encoding.

Version 10 stores moving layout edits as a placement count followed by indexed edits. It shortens individual matrix, shared pose and shared texture offset updates. The packer selects this representation when it reduces the expanded section without increasing the compressed section.

A draw can combine shared rigid parts with a small captured detail. If a part's position or UVs differ from its shared model, the exporter checks whether it can keep the surrounding parts and encode just the differing range in its original draw order. The compressed representation must reconstruct the submitted geometry exactly and cost less than the captured draw.

For compatible Sodium cuboids, the exporter shares the local corner selections and UV coordinates. For draws wrapped in Minecraft's sprite coordinate expander, the shared model includes the UV conversion for that atlas sprite. The exporter records the transformed origin, three axis deltas and packed face normals. Capycraft applies the deltas in Sodium's float32 addition order to preserve the original corner positions.

When Create Hypertube is present, the exporter can share the local geometry of an unchanged tube curve across frames. It accounts for the renderer's normal directions in the Ponder screen. Tube sections retain their individual lighting and the original order of the translucent faces. The shared model is used only when the rebuilt vertices match the game buffer.

Compatible enchanted item draws share local geometry between the main item and its foil effect. The foil draw records only part references and position matrices. The player uses the original foil texture and render settings.

Create custom baked item parts can share the same model data across poses. The exporter records these parts before Create submits them directly as item quads. The chain conveyor's wrench body and gear retain their own transforms.

The exporter records rectangular entity shadows as local face bounds, poses and submitted texture coordinates. Capycraft reconstructs the faces and uses the original index order for blending.

The launcher packs the export before publishing the ZIP. It stores picking data as 64-tick chunks, groups small meshes by 16-tick section, and keeps shared assets separate. Larger captured meshes can share identical streams of vertex data. Capycraft assembles those streams into the original mesh bytes. Timeline chunks can record edits to individual draw and placement entries, down to the changed matrix and color values. Repeated matrices and matrix edits can share a palette within the chunk. Every chunk has its own baseline and resolves to the original timeline values. Registrations share identical compiled scene definitions. The packed export keeps playable scenes, captions, item associations, and the shared sprite sheets referenced by `atlasAnimation`. It excludes source schematics, private event records, unused assets, and draws that Capycraft cannot render. To convert an unpacked compact ZIP without launching the client, run `npm run export -- <input.zip> --pack-ponder <output.zip>`. The launcher summary reports the packed sizes and the per-scene diagnostics describe the original capture.

Version 4 timeline chunks can use positional fields for block placements and record absolute transforms as changes from the identity matrix. Capycraft restores the complete placements and matrices before rendering. These encodings retain the original numbers and use the existing chunk boundaries.

Small effect and pose records that are referenced by only one timeline section can share that section's geometry bundle. Version 5 timelines can identify a range in their own section with the codec, offset, length and Base64URL-encoded SHA-256 checksum. The importer verifies each range in place, and the browser reads the bundle on demand. Other sections can refer to the same range through its full address.

Version 6 timeline sections can share block placement lists and texture offsets across parts and ticks. The browser expands the section's palette references before composing the frame. Each section still contains its own baseline for seeking.

Version 8 timeline sections can also share repeated draw attributes, textures, matrix values and mesh references across scene and overlay draws. The importer and browser resolve those references before applying changes to the previous draw. The ZIP packer uses the palette when it reduces the expanded section size and fits within the original compressed size.

Version 7 can compact absolute matrix values and matrix edits as exact signed millionths in a variable-length byte sequence. Values that cannot be represented exactly retain their numeric representation.

The packed export records unsupported draw snapshots by scene and shader in `export-diagnostics.json`. The counts cover the draw lists written by the client, counting an unchanged list only when it appears in the input timeline.

Per-scene `missingStaticModels` diagnostics list the block states for which the exporter could not resolve static geometry. Animated and invisible render shapes do not require a static model. Their picking data comes from the same scene selection as other blocks.
The diagnostics also report the unique captured vertex buffers and their raw bytes per scene and shader before archive compaction. A buffer shared by two shaders or scenes appears in both groups.

### Remaining playback work

- Preserve the native directional shading of moving Create models. Shared mesh capture resets the pose and normal matrices while extracting local geometry. Carry the original vertex colors and shade groups with the shared model so the player can apply the placement's normal transform and reproduce the native shading. Compare moving shafts, cogs and pumps against native reference frames.
- Represent liquids, animated block entities and custom effects with reusable models and timed state where the renderer can reproduce their appearance. The compact format still carries geometry captured in Minecraft for draws that lack a state-based representation. Verify Create, KubeJS and other ATM10 scenes against native frames, including playback, seeking, text captions and block picking, before removing those draw records.
- Preserve the remaining animated atlas samples in `create:mechanical_arm_filtering` and `create:mechanical_roller_pave`. The ATM10 audit found still-image fallbacks on 62 and 78 ticks respectively, even though both scenes also contain supported atlas animations.

### Export legacy Ponder frames

Use `--sections ponder-legacy` or select **Ponder legacy frames** for the frame-based archive.

CUDA compares every RGBA byte of eligible refreshed text textures through a shared OpenGL buffer. WebCraft keeps up to eight previous textures of at most 4 MiB each. A matching texture reuses the previous PNG while the current draw's sampler settings are recorded. The client log summarizes CUDA comparisons, PNG reuses, full CPU readback bytes avoided, standard readbacks, and time spent on each capture path.

The ZIP stores `ponder/index.json` with version `1`, the capture locale, and the ID, title, schematic, item associations and manifest path for each scene. A scene manifest gives the reference viewport, tick rate, frame count, keyframe positions and diffuse lighting. Numbered `frames/<tick>.json` files start at tick zero. Each frame contains ordered scene and overlay draws, the scene-to-GUI matrix, captions, and a reference to block picking data. Picking boxes use block-local coordinates followed by their world section transform. This lets a player seek directly to a tick and identify blocks under the pointer.

Each draw identifies its shader, textures, projection, model-view and texture matrices, and depth, cull and blend state. The mesh paths reference little-endian vertex and optional index buffers. Each draw lists the vertex stride and attribute types and offsets. Draws with implicit quad indices use triangles `0,1,2` and `2,3,0` per quad. Matrices use column-major order. PNGs capture texture samplers. Where present, the texture animation document maps atlas rectangles to source image strips and ordered `[frameIndex, tickDuration]` pairs. Animated block renderers and particles contribute the geometry emitted by the running Minecraft client at each scene tick. Replaying these data in a browser requires equivalents for the captured render states and shaders.

### Export Ponder definitions

Select **Ponder definitions** or pass `--sections ponder-definitions` to the launcher. An empty `ponderScenes` filter includes registrations from every loaded mod and script. The client evaluates each compiled scene and exports changes to its draw state, camera, picking data, and localized captions with shared, content-addressed meshes and textures.

The ZIP includes `ponder/definitions.json` with version `1`, the locale, the tick rate and one entry per registration. Entries give the item, schematic, tags and ordering constraints. Available source schematics are stored as content-addressed compressed NBT in `ponder/schematics/`. Compiled scene metadata includes framing, localized text templates and the ordered instruction types, their blocking flags and declared tick durations where available. Text instructions also include resolved caption strings and placement information. A registration remains in the index if its schematic cannot be read or its script fails to compile.

The publisher converts the exported timelines to playback data without loading the modpack or running its scripts. Exports made with both **Ponder legacy frames** and **Ponder definitions** use the frame and metadata formats for compatibility with existing frame-based players.

During a registered delay, timeline export reuses the preceding geometry and picking data when the camera and visible sections are stable and the scene has no dynamic renderers or effects. Captions keep their tick timing and remain text. Instructions with callbacks or changing render state still run in the client renderer. The client log reports geometry captures and reused ticks for each scene.

Timeline chunks can include resolved block-state changes, section poses and membership, block-entity data saved by the game, and changes to entities, particles, and outlines. This applies to scenes registered by Java and KubeJS. The exporter can reuse captured opaque geometry for camera rotations and for visible sections that share a single rigid motion. Independently moving sections that share a draw batch, animated block entities, and effects with custom renderers still use the client renderer.

During timeline export, progress shows the distinct block states and running uncompressed sizes for captured meshes, textures, and timelines. The client log and `export-diagnostics.json` report new mesh files, mesh submissions, block changes including the initial layout, and reused geometry ticks for each scene. The launcher prints compressed and uncompressed archive sizes by category, with the largest mesh and texture entries and the source name for recognized atlases and animated sprites. A captured mesh can contain several block models.

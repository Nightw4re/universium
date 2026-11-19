# WebCraft

WebCraft exposes Minecraft server data over a shared HTTP API. Players can connect with their regular modpack client. The original bridge is available in the [WebCraft Origin archive](../../archive/webcraft-origin/README.md).

| Directory | Gradle project | NeoForge mod ID |
| --- | --- | --- |
| [Core](core) | `:webcraft-core` | `webcraft_core` |
| [Equipment](equipment) | `:webcraft-equipment` | `webcraft_equipment` |
| [Item Catalog](catalog) | `:webcraft-catalog` | `webcraft_catalog` |
| [Design Data](designs) | `:webcraft-designs` | `webcraft_designs` |
| [Preview Exporter](exporter) | `:webcraft-exporter` | `webcraft_exporter` |

The [reference frontend](frontend/README.md) is an independent web client.

## Install on a dedicated server

Build the Java 21 mods from the Universium root. The modules target Minecraft 1.21.1 and build and test against NeoForge 21.1.230, the minimum runtime version. The declared range also includes NeoForge 21.1.249.

```sh
gradle :webcraft-core:build :webcraft-equipment:build :webcraft-catalog:build :webcraft-designs:build
```

1. Put the core JAR from `core/build/libs/` into the server's `mods/` directory, plus the Equipment, Item Catalog or Design Data JARs for the modules you want to enable. The add-on JARs depend on the core.
2. Start the server once to generate `<level-name>/serverconfig/webcraft-server.toml`, then stop the server and edit the file. Use the UUID the server assigns to a player who has agreed to share their equipment with the token holder.
3. Generate a random 32-byte secret, for example with `openssl rand -hex 32`. Pass it to the server process as `WEBCRAFT_VIEWER_TOKEN`. The accepted format is 43 to 128 URL-safe ASCII characters. Share the token privately with its intended viewer.
4. Optionally install a [preview pack exported by a Minecraft client](#export-inventory-previews). For a basic vanilla fallback, put the official Minecraft 1.21.1 client JAR at `webcraft-assets/client.jar`, relative to the server working directory. Item details are available without either archive.
5. Restart the server after changing settings or the token. Join with the selected player, [start the reference frontend](frontend/README.md), and enter the token.

```toml
listenerEnabled = true
bindAddress = "127.0.0.1"
port = 8765
enabledModules = ["webcraft_equipment:equipment", "webcraft_catalog:items", "webcraft_designs:banners", "webcraft_designs:armor"]
viewerUuid = "00000000-0000-0000-0000-000000000000"
```

Replace the example UUID. The listener defaults to off, with an empty module list and no authorized viewer. Keep the loopback bind for a proxy on the same network namespace. With `listenerEnabled = false`, the embedded REST service is unavailable. An external HTTP adapter would need its own integration with the game server.

### Upgrade a previous preview installation

1. Stop the server and replace the former `webcraft-next-*.jar` files with the matching `webcraft-*.jar` files. Replace the preview exporter separately on any client that uses it.
2. Copy the previous world's `serverconfig/webcraft-next-server.toml` to `serverconfig/webcraft-server.toml` unless the destination already exists. In `enabledModules`, change the prefix `webcraft_next_` to `webcraft_` for WebCraft's built-in modules.
3. Rename `WEBCRAFT_NEXT_VIEWER_TOKEN` to `WEBCRAFT_VIEWER_TOKEN` in the server process environment, keeping the existing token value. Upgrade the frontend together with the server. Integrations can rediscover current routes through `GET /api/v1/modules`. Update any hard-coded module IDs and endpoint paths to the new namespaces.

Existing v1 export archives still work, including design manifests from the former namespace. The optional frontend stays off by default. Use the [development launch command](frontend/README.md) to start it locally.

## Export inventory previews

Build the optional exporter with `gradle :webcraft-exporter:build` and put its JAR from `exporter/build/libs/` in the Minecraft client's `mods/` directory. The exporter runs separately from the server mods and uses the installed modpack and active client resource packs.

1. Open a world in the same modpack. You can use a singleplayer world or join the server to export specific items that already exist there.
2. Run the client command `/webcraft export`. Choose **Export inventory** to capture the exact stacks currently in your inventory, or **Export item catalog and inventory** to also capture the catalog, banner patterns, and armor trim textures. The inventory and catalog renders are processed in small batches.
3. Take `webcraft-exports/previews.zip` from the client instance and install it as `webcraft-assets/previews.zip` in the server working directory. Keep the archive out of Git and the public static web directory. Each successful export replaces the previous client archive. Include all the inventory variants you want in a new export before replacing the server copy.

The client calls Minecraft's ordinary GUI item renderer in the loaded world. This includes any renderer that NeoForge selects for the stack. The export saves a transparent 64 × 64 PNG at three pixels per GUI unit, with the item's inventory pose intact. When the rendered model uses animated atlas sprites, the exporter samples the inventory render on successive client ticks for one combined loop. This includes game-interpolated frames and active resource pack overrides. Identical consecutive samples share one frame whose duration is the sum of their ticks.

The exporter samples combined loops of up to 160 ticks (8 seconds), in batches of up to four stacks. Longer combinations, motion driven by custom renderers outside the baked-quad path, and item models that change selection with world or player state may need dedicated adapters. The ordinary static export remains available for supported, visible renders. The shared enchantment glint recipe is independent of atlas animations.

Pack v1 contains `format.txt` with `webcraft-previews-v1/1.21.1` followed by a newline and `previews/<key>.png` entries. Optional `animations/<key>.png` entries are looping APNGs with explicit frame durations in 1/20-second ticks. Older packs with only static PNGs still work. The key is the SHA-256 of the UTF-8 text produced by Minecraft's sorted SNBT serialization of the exact stack, including count and data components. The dedicated server computes the key from the live stack and reads the pre-rendered image. The archive contains PNGs and hashed keys, so no serialized item data is distributed. Re-export after changing model or resource packs. Export a specific inventory stack when its appearance depends on its components.

## Wings and HTTPS

Assign a second TCP allocation to the Minecraft server in Pterodactyl Wings and set `port` to that allocation's port. Bind on the appropriate container interface or use `0.0.0.0` inside the container when the proxy reaches it through the published allocation. Restrict the allocation to the reverse proxy or a private network at the host or firewall. Use a private or otherwise protected connection for the HTTP hop carrying viewer tokens. [Wings allocation documentation](https://docs.pterodactyl.io/v1/wings/installing#node-allocations)

Serve the static frontend from the HTTPS web origin and forward `/api/v1/` from that origin to the WebCraft listener. Preserve the request path and the `Authorization` header, disable proxy and CDN caching for those routes, and keep credentials out of access logs. Apply request limits at the proxy. The frontend and game server can run on different machines. The proxy, DNS provider and certificate setup are independent of the mod.

Equipment uses single-viewer private access. Anyone who holds the token can read the configured player's currently equipped items, including custom item names. Removing the UUID or rotating the token and restarting revokes access. A public player-facing equipment rollout needs account authentication, a verified mapping from accounts to player UUIDs and server-side visibility and consent controls. The enabled-module list and Item Catalog expose public metadata only.

## REST v1

The API accepts GET on exact paths and sends `Cache-Control: no-store`. Discovery is public whenever the listener is on. Private routes and discovery reject query parameters.

### Server information

With the listener enabled, opt in to public server information in `webcraft-server.toml`. Set the display name and Minecraft connection address if you want to publish them. A blank value omits that field. The Minecraft address is what players enter to join, including a port if needed. The HTTP bind address and port are independent.

```toml
apiDocsEnabled = true

[serverInfo]
enabled = true
name = "Copycraft"
joinAddress = "play.example.com"
publishPlayersOnline = true

[serverInfo.modpack]
publishName = true
publishVersion = true
source = "AUTO"
```

Put `apiDocsEnabled` at the top level alongside `listenerEnabled`. The server-info route, Swagger UI and each field are off or blank by default. Restart the server after changing the settings or installing a modpack update. Add `apiDocsEnabled = true` to serve Swagger UI at `/api/v1/docs` and its OpenAPI document at `/api/v1/openapi.json`. The UI is self-hosted and shows the core routes and enabled module routes. Private routes still require the viewer token. The detailed module contracts are documented below.

`GET /api/v1/server-info` is public when enabled, accepts no query parameters, and returns the selected fields as JSON. `playersOnline` comes from the running Minecraft server and can be `0`. Hidden or unavailable fields are omitted, and `modpack` is omitted when neither of its fields can be published.

```json
{
  "serverName": "Copycraft",
  "joinAddress": "play.example.com",
  "playersOnline": 0,
  "modpack": { "name": "All the Mods 10", "version": "8.1" }
}
```

Modpack metadata is resolved once at server startup when either modpack field is enabled. `AUTO` tries the following sources in order and uses the first complete name and version. Versions are strings, so values such as `8.1` and `0.15.0-beta` retain their original formatting.

| Source | Metadata read |
| --- | --- |
| `BCC_RUNTIME` | The name and version already resolved by the running Better Compatibility Checker, when installed and compatible. |
| `BCC_TOML` | `config/bcc-common.toml`, used by ATM10 and Craftoria. If `general.useMetadata` is true, this source reads `config/metadata.json`. |
| `BCC_JSON` | `config/bcc-common.json` with `modpackName.value` and `modpackVersion.value`. If `useMetadata.value` is true, this source reads `config/metadata.json`. ATM11 is a sample of this format on a newer Minecraft version. |
| `METADATA_JSON` | `config/metadata.json` with `name` and `version.name`, as shipped by FTB Skies 2 and FTB StoneBlock 4. |
| `PACKWIZ` | A local packwiz `pack.toml` with `name` and `version`. The file must be present in the server installation. |

Set `source` to a specific identifier to use only that source. `CURSEFORGE_MANIFEST` reads a local `manifest.json` from a CurseForge export when selected explicitly. Use it if the server installer keeps that file and updates it with the installed version. A manually selected source that cannot be read leaves the modpack fields absent and reports the failure in the server log.

For a different layout, select `CUSTOM` in `[serverInfo.modpack]` and set `file`, `nameKey` and `versionKey`. Use a JSON or TOML file relative to the instance directory, with dot-separated paths to string values. For example, `file = "config/pack-info.json"`, `nameKey = "pack.name"` and `versionKey = "pack.version"` read the two properties from a nested `pack` object. If the installation has no suitable metadata, its build or installer can write this file on each upgrade.

### Module discovery and routes

```http
GET /api/v1/modules
```

```json
{
  "modules": [
    {
      "id": "webcraft_equipment:equipment",
      "routes": {
        "loadout": "/api/v1/modules/webcraft_equipment/equipment/loadout"
      }
    }
  ]
}
```

```http
GET /api/v1/modules/webcraft_equipment/equipment/loadout
Authorization: Bearer <viewer-token>
```

The response has a `player` name and a `slots` array containing `head`, `chest`, `legs`, `feet`, `mainhand` and `offhand`. Each entry has a `slot` and a nullable `item`. An occupied slot has the item `id`, display `name`, stack `count` and `preview`.

| Preview type | Fields and rendering |
| --- | --- |
| `sprite` | `png` contains a base64-encoded client-rendered icon or a single-layer vanilla item texture. Optional `animation` contains a base64-encoded APNG for the exact exported stack. |
| `layered` | `base` and `overlay` contain base64-encoded PNGs, and `tint` is the RGB integer applied only to the base. |
| `cube` | `faces.up`, `faces.north`, `faces.east` contain the visible face PNGs of an unrotated, opaque full cube with the standard inventory pose. |
| `missing` | `reason` reports unavailable assets, an unsupported model or unsupported stack state. Show the item label and an explicit missing preview. |

Exported exact-stack icons take precedence over the built-in vanilla fallback. The fallback supports single-layer icons, standard opaque cubes and untrimmed vanilla leather armor. When drawing leather armor, the web client tints the base with the stack's dye color and draws the static overlay on top. Other special or modded variants can use an exported icon. If an icon is unavailable, the item still appears with its name.

Error responses contain an `error` code. A missing or incorrect token returns `401 unauthorized`, absent viewer configuration returns `403 viewer_access_disabled`, a disabled or absent module returns `404 not_found`, and a disconnected viewer returns `404 player_offline`. A server thread that cannot respond in time returns `503 server_busy`.

With Item Catalog enabled, `GET /api/v1/modules/webcraft_catalog/items/items` lists the registered item IDs, display names and whether an optional default icon is indexed. `GET /api/v1/modules/webcraft_catalog/items/icon?id=minecraft%3Astone` serves an indexed PNG, or `404 preview_not_found`. Both catalog routes are public. Catalog names and icons come from `catalog.json` inside a full client export. The catalog lists registered items even when a preview is missing and reads the export index at server startup. Install an updated archive before restarting the server. Item data captured from individual inventories is available only through the private Equipment route.

Click an item to open its catalog detail. `GET /api/v1/modules/webcraft_catalog/items/detail?id=<item-id>` exposes the exported variants and selector options. Request an indexed variant with `&variant=<variant-id>` on the icon route. The full export covers the registered Easy Villagers biome types and professions and the vanilla dye palette for stacks tagged as dyeable, including Nano and Quantum Nano armor in Extended Industrialization. A complete set of sixteen separately registered color-prefixed items is linked as a color family, including any registered undyed default. Applied Energistics 2 cable families use the registered Fluix item as their default. Selecting a family member shows its own item ID and exported icon.

Append `&animated=1` after the item ID and optional variant to request the APNG when available. The route falls back to the static PNG and responds with `image/png` in both cases. The reference frontend plays animations in catalog tiles, item details and equipment when the browser allows motion. The catalog's optional glint simulation uses the static icon as its mask.

The Easy Villagers breeder detail offers separate **Biome type** and **Profession** selectors for each slot. Either slot can be empty. The exporter populates the same block entity data used by the mod's dropped item and captures each registered combination in each slot with the game's item renderer. The server combines the two slot renders into a single downloadable PNG. A complete breeder stack with additional data can also be rendered directly by exporting it from the player's inventory, for use in Equipment.

Use the breeder's `villager_1/<biome-id>/<profession-id>` or `villager_2/<biome-id>/<profession-id>` variant ID to request one occupied slot. For both, join the two indexed variant IDs in slot order with a URL-encoded plus (`%2B`) in the `variant` parameter. Omit `variant` for the empty breeder. For example, `/api/v1/modules/webcraft_catalog/items/icon?id=easy_villagers%3Abreeder&variant=villager_1%2Fminecraft%3Aplains%2Fminecraft%3Afarmer%2Bvillager_2%2Fminecraft%3Adesert%2Fminecraft%3Alibrarian` serves a complete PNG with both villagers.

`GET /api/v1/modules/webcraft_catalog/items/effects` serves shared visual metadata from `effects.json`. The client exports the active item glint texture and the standard glint transform, timing and blend settings once. The web detail animates the glint in screen space over the selected icon, using the icon's opacity as its mask. This reproduces the standard effect as a 2D preview. Exact placement on 3D surfaces and renderer-specific shader replacements depend on the item's original geometry or shader.

### Banner and armor design data

The Design Data JAR registers two independently configurable public modules. Each exposes GET `data` and `texture?hash=<sha256>` beneath its discovered route prefix. No player data is needed. The JSON contract has `version: 1`. Texture properties hold content hashes. A module can serve only PNGs listed in its own exported manifest. An enabled module without an applicable export returns `404 design_export_missing` on the data route.

- `webcraft_designs:banners` exposes the banner base and cloth textures, front-face UV rectangle, dye palette, six-layer loom limit, and registered loom patterns with usable sprites. Each pattern's `templates` array lists the item IDs accepted for that layer. An empty array means the loom accepts the pattern without a template.
- `webcraft_designs:armor` exposes supported vanilla armor items, base texture layers with an optional tint, trim patterns and materials, and texture profiles referenced by each piece. Profiles use the actual atlas sprite for each pattern and material, including armor-material overrides and the separate leggings sprites. Its separate GET `geometry` route serves baked classic and slim player models, their skin overlays, and the worn armor meshes for `head`, `chest`, `legs` and `feet` from `armor_geometry.json`. Mesh arrays contain consecutive quads with eight values per vertex in position, UV and normal order. The `data` projection remains available for front views when geometry or WebGL is unavailable. Custom armor models require separate export support.
- GET `skin?name=<Java-player-name>` on the armor module looks up the player's published skin using Minecraft's official profile, session and texture services. It returns a JSON object with `name`, `slim` and a base64 PNG. The server uses HTTPS for all requests. The route accepts Java Edition usernames and caches successful results for two minutes.

A full export stores the manifests under `designs/webcraft_designs/` and shares content-addressed PNGs under `textures/`. The client resolves the active resource pack and current world's registered patterns. An older pack remains valid for inventory and catalog previews. A new full export is needed to enable the design views.

The banner manifest can also include `slotPreviews.banner` and `slotPreviews.shield`. Each supplies 64 × 64 base renders keyed by dye ID and neutral pattern overlays keyed by pattern ID. These frames are captured in the inventory pose, with separate shield artwork and geometry. Tint each overlay with its selected dye and composite it in the original layer order. Shader blending and PNG quantization can produce small differences from a complete stack captured in one pass.

## Add-on API

An add-on listens for [`RegisterWebCraftModulesEvent`](core/src/main/java/com/universium/webcraft/api/RegisterWebCraftModulesEvent.java) on its mod event bus. It supplies a unique module ID and a map from route names to read handlers. The core publishes enabled routes under `/api/v1/modules/<namespace>/<module>/<route>`. Private handlers run on HTTP workers with the UUID already resolved by the core. Schedule live Minecraft reads on the game thread and serialize the captured data off that thread. Add-ons can explicitly register a `WebCraftReadRoute.PublicRoute` for public data such as the item catalog.

To check the REST contract and compare returned previews against the original textures, run the build with `-PwebcraftTestAssets=/path/to/1.21.1.jar`.

# Catalog and design data

[WebCraft index](README.md)

Use this reference for catalog and design routes and the ZIP documents consumed by the server and archive importers. See the [export guide](exports.md) to generate an archive.

## Item data feeds

The Item Catalog JAR registers three public modules. Enable each ID independently through `enabledModules`. Discover enabled route URLs through `GET /api/v1/modules`. The public data routes read the preview archive and do not access the Minecraft game thread.

| Module ID | GET path under `/api/v1/modules/webcraft_catalog/` | Query |
| --- | --- | --- |
| `webcraft_catalog:items` | `items/stack` | `id=<catalog-id>[&variant=<scoped-variant-id>]` |
| `webcraft_catalog:recipes` | `recipes/recipes` | `id=<result-item-id>` |
| `webcraft_catalog:tooltips` | `tooltips/tooltip` | `id=<catalog-id>[&variant=<scoped-variant-id>]` |

The stack and tooltip responses include `version`, `catalogId`, `itemId` and `variantId`. A default stack has a null variant ID. For a color family member with its own registry ID, `itemId` identifies the actual item and can be used to look up recipes. Each payload has version 1. For example, a selected leather helmet variant can return these stack fields alongside its other effective components.

```json
{
  "version": 1,
  "catalogId": "minecraft:leather_helmet",
  "itemId": "minecraft:leather_helmet",
  "variantId": "color/red",
  "count": 1,
  "components": {
    "minecraft:dyed_color": {"rgb": 11546150},
    "minecraft:max_stack_size": 1
  },
  "nbt": {},
  "omittedComponents": []
}
```

The `components` object maps Minecraft 1.21.1 component IDs to their codec JSON for the actual default or synthetic export stack. This includes effective item defaults and serializable modded components, such as fluid contents when a captured stack stores them in a persistent component. The `nbt` object carries exact SNBT strings for NBT-backed `CustomData` components, preserving types such as byte arrays and 64-bit integers for an SNBT parser. Component IDs without a successful JSON encoding appear in `omittedComponents`. Transient capabilities, arbitrary player stacks and variant states the exporter never constructs are outside this contract.

Recipe snapshots use the target world's resolved recipe collection. In Minecraft 1.21.1 the server sends that collection to the client on login and after datapack reload, independently of the player's unlocked recipe book. The catalog export captures the synchronized data when it begins. A shaped entry for an iron pickaxe looks like this. The result object is nested in a response with `version`, `itemId`, `source`, `coverage` and `recipes`.

```json
{
  "id": "minecraft:iron_pickaxe",
  "type": "minecraft:crafting",
  "serializer": "minecraft:crafting_shaped",
  "status": "supported",
  "station": "minecraft:crafting_table",
  "fitsPlayerGrid": false,
  "layout": {"kind": "grid", "width": 3, "height": 3},
  "inputs": [
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    null, {"match": "items", "exampleItems": ["minecraft:stick"]}, null,
    null, {"match": "items", "exampleItems": ["minecraft:stick"]}, null
  ],
  "result": {"itemId": "minecraft:iron_pickaxe", "count": 1}
}
```

Supported recipes use the standard shaped and shapeless crafting, smelting, blasting, smoking, campfire cooking, stonecutting, and smithing transform implementations. Pattern slots are row-major. Smithing uses named template, base and addition slots, and may copy base stack components. Cooking exposes ticks and experience. In the default response, ingredients carry up to 32 resolved distinct item IDs with `examplesTruncated` when more exist. A custom ingredient carries `match: "custom"` and its codec definition when available. The example IDs alone cannot describe a custom ingredient predicate.

An unsupported recipe with a reported item appears with `status: "unsupported"` and `reportedResult`. Treat it as a hint, since the actual output can depend on inputs or machine behavior. Mod recipes hidden from the vanilla recipe book can still appear when they report an item. The snapshot counts known dynamic recipes in `coverage.dynamic`. Examples include fireworks, map expansion, smithing trims, and custom crafting recipes without a fixed output. Coverage also counts other recipes with no indexable result and unreadable recipes in `unindexed` and `unreadable`. `source` is `world_recipe_sync`. An indexed result with an oversized export returns `truncated: true`. A successful empty `recipes` array means the snapshot has no matching indexed result.

Either catalog mode can also export machine recipes supplied by active JEI integrations and native EMI categories, including Modern Industrialization. When both viewers supply a category, the exporter uses JEI's version. The existing item recipe response adds an optional `machineRecipes` object with `version`, `itemId`, `source`, `skipped` and `recipes`. Its source is `jei` or `emi` for a single recipe viewer and `recipe_viewers` when both contribute. An oversized result can include `truncated: true`. Query the same route with `?fluid=<result-fluid-id>` or `?chemical=<result-chemical-id>` for the machine recipe object directly, with `fluidId` or `chemicalId` replacing `itemId`. The recipe viewers are optional client mods.

Each machine recipe carries its `id`, category ID, localized title, station item IDs and a `layout` with dimensions and slots. New exports identify the viewer that supplied each recipe with its `source`. A slot has an input, output or catalyst role, an optional name and optional `bounds` in the viewer's layout coordinates. Each alternative has an `id` and a `kind`. Items have a numeric `count`, fluids have a numeric `amountMb`, and Mekanism chemicals have `kind: "chemical"` and an `amount` in millibuckets encoded as a decimal string to preserve full 64-bit precision. Fluid and chemical alternatives may include a localized `name` and a `texture` containing the SHA-256 hash of the canonical tinted PNG or APNG generated from the client's active resource packs. The sprite uses the stationary fluid texture or chemical icon and preserves its frame order, durations and interpolation in a looping APNG or WebP. Loops beyond the capture limits use the first frame. A fluid sprite is stored only as WebP when the selected WebP mode produces an animation within the size limit. The hash remains the same across formats. Use the recipe module's `texture?hash=<texture>` route to retrieve the animated WebP when present or the PNG otherwise. Direct ZIP importers should try the two extensions in the same order.

The Thermopneumatic Processing Plant's yeast-culture layout includes this fluid input. Its bounds place the tank near the left edge of the 166 by 70 recipe area.

```json
{
  "role": "input",
  "bounds": {"x": 8, "y": 3, "width": 16, "height": 64},
  "alternatives": [{"kind": "fluid", "id": "minecraft:water", "amountMb": 1000}]
}
```

Tooltip text comes from Minecraft's normal client item tooltip and NeoForge's component gathering hook in the loaded world, with normal tooltip settings and no player passed to the item tooltip API. The export keeps ordered lines and resolved text spans. For example, the following abbreviated response describes a localized tooltip title.

```json
{
  "version": 1,
  "catalogId": "minecraft:iron_pickaxe",
  "itemId": "minecraft:iron_pickaxe",
  "variantId": null,
  "locale": "cs_cz",
  "hasGraphicElements": false,
  "lines": [
    [{"text": "Železný krumpáč", "color": 16777215, "italic": true}]
  ]
}
```

Spans can also carry `bold`, `underlined`, `strikethrough` and `obfuscated`. The `color` field holds the 24-bit RGB value Minecraft uses for the text. Treat `text` as plain text. The exporter captures text before pixel wrapping, excludes custom drawing and font assets, and flags known graphic components that were skipped. Player context and advanced mode can differ from the captured tooltip.

When the client tooltip changes in response to a supported held-key combination, the optional `alternatives` array includes its Minecraft key identifiers in `keys`, localized shortcut text in `label`, and the complete alternative `lines` and `hasGraphicElements`. Clients can use the label as a selector and display the alternative lines without listening for those keys on the web page. Interactive guide screens and input managed outside the supported keyboard queries remain Minecraft features.

Older packs still serve their previews and catalog details. Missing or invalid stack or tooltip data returns 404 with `stack_data_unavailable` or `tooltip_unavailable`. The same applies to a breeder composite with two occupied slots, since only the combined image is synthesized. An unknown item or single variant returns `item_not_found` or `variant_not_found`. A missing recipe snapshot returns `recipe_export_unavailable`. A missing or invalid indexed recipe file returns `recipes_unavailable`. Disabled modules are omitted from discovery and their routes return `not_found`.

For direct archive ingestion, use `catalog.json` for item and variant identities and their image keys. Either catalog mode can add the versioned JSON documents listed below under `catalog-data/`. The server resolves the same documents from standalone files and bundles, so REST clients use the existing routes and response formats.

When the client can identify the modpack, the archive also contains a root `metadata.json` with this shape.

```json
{"version": 1, "modpack": {"name": "Example Modpack", "version": "2.0"}}
```

The exporter reads the name and version from the CurseForge `manifest.json` in the client game directory. For other installations or an explicit override, put a `webcraft-modpack.json` containing string fields `name` and `version` in that directory. The exporter adds `metadata.json` when it finds both fields in one source. The server accepts up to 16 MiB of uncompressed `catalog.json` data.

| Entry | Contents |
| --- | --- |
| `stacks/<namespace>/<item-path>@<variant>.json` | Effective stack components, count and custom SNBT |
| `tooltips/<namespace>/<item-path>@<variant>.json` | Locale, structured tooltip text and captured shortcut alternatives |
| `recipes.json` | Snapshot source, coverage and `resultItems` index |
| `recipes/<namespace>/<item-path>.json` | Recipes grouped by result item ID, with named tag references in version 2 |
| `recipe-tags/<namespace>/<tag-path>.json` | Resolved item IDs for a named tag referenced by a recipe |
| `recipe-item-previews.json` | Optional object mapping ingredient item IDs outside the catalog to their default preview keys |
| `machine-recipes.json` | Optional recipe viewer snapshot metadata with `resultItems`, `resultFluids` and optional `resultChemicals` |
| `machine-recipes/items/<namespace>/<item-path>.json` | Machine recipe references for an item output |
| `machine-recipes/fluids/<namespace>/<fluid-path>.json` | Machine recipe references for a fluid output |
| `machine-recipes/chemicals/<namespace>/<chemical-path>.json` | Machine recipe references for a chemical output |
| `machine-recipes/textures/<hash>.png` | Static PNG or animated APNG for a fluid or chemical sprite |
| `machine-recipes/textures/<hash>.webp` | Animated sprite in WebP mode. A fluid sprite may have only this entry |
| `machine-recipes/groups/<number>.json` | A shared machine layout and the recipes that use it |

In the two attachment paths, keep the `@` and use an empty suffix for a default stack. Encode variant `:` as `%3A` and `/` as `%2F`. Read separately registered color family members under their own default stack path. A catalog entry with `hasPublicData: true` has at least one default attachment, even if rendering the item produced no image. The REST layer adds the selected catalog identity to stack and tooltip files, and the index coverage to recipe files. The archive does not record private inventory data in any of these JSON entries.

With both **Items and previews** and **Recipes** selected, the exporter attempts to render default icons for world recipe ingredients outside the catalog, including every member of referenced item tags. For an ingredient absent from `catalog.json`, direct archive importers can look up its ID in `catalog-data/recipe-item-previews.json` and resolve the key using the same preview and animation paths. Use those entries in recipe views and keep them out of the catalog listing. REST clients can request the default icon through the existing `items/icon?id=<item-id>` route. Older archives can omit the index.

### Bundled catalog documents

For a logical path such as `catalog-data/stacks/minecraft/stone@.json`, first look for the standalone ZIP entry. If it is absent, look up that full path in `catalog-data/bundles/v1/index/<shard>.json`. The shard is the low nine bits of Java's string hash code, computed by starting at zero and multiplying by 31 before adding each UTF-16 code unit. For bulk ingestion, enumerate all index entries instead of computing shards separately.

Each index maps logical paths to `[bundle, offset, length]`. Read `catalog-data/bundles/v1/<bundle>.json` and decode the byte range beginning at `offset` for `length` bytes as a standalone UTF-8 document. The bundle itself is a JSON array. Offsets count uncompressed bytes from the start of that array. Small stack, tooltip, recipe, recipe tag and machine recipe documents may be bundled. Larger documents, top-level indexes and images keep their standalone paths. Upgrade direct ZIP importers to resolve both layouts before installing a bundled export.

### Compact recipe documents

Append `&compact=1` to a recipe request to receive the compact archive representation. Each recipe document has its own `version`, including the nested `machineRecipes` object on item responses. Readers should accept version 1 and version 2. Requests without `compact=1` return version 1 with tag examples and individual machine recipes. An expanded machine result may return `truncated: true` at the response size limit.

In a version 2 crafting document an input such as `{"match":"items","tags":["minecraft:planks"],"items":["minecraft:bamboo_block"]}` accepts any member of the listed tags or any explicit ID in the optional `items` array. The recipe module's discovered `tag?id=<tag-id>` route serves the same tag document as the ZIP. It has `version: 1`, the tag's `id`, and a sorted `items` array of members resolved in the exporting client, including members of nested tags. Use the tag ID as the slot label and fetch its members when needed. Minecraft can expand a tag to item IDs before it synchronizes the recipe to the client. Those inputs and custom predicates use `exampleItems`.

A compact machine **HTTP response** has shared layouts in its `groups` array. Each group has a `template` with the usual recipe fields except `id`, plus a `variants` array. A variant carries its original `id` and can carry a `slots` object. Each key is the zero-based position in `template.layout.slots`, and its value replaces that slot's entire alternatives array. An omitted position inherits the template and an empty array clears the slot. Apply all replacements together so ingredient counts, output counts and byproducts stay attached to the selected variant. Recipe viewer alternatives preserve the viewer's item, fluid and chemical IDs and amounts.

For direct ZIP ingestion, a version 2 **machine output file** uses group references. For example, `{"version":2,"itemId":"minecraft:stone","groups":{"12":[0,4]}}` points to variants 0 and 4 in `catalog-data/machine-recipes/groups/12.json`. The shared file has `version: 1`, the `template` and the `variants` in the same shape as the compact HTTP group. The server resolves the references and only returns variants indexed for the requested output. Multiple outputs can reference the same variant, so an importer can store each shared variant once and index its outputs separately. Group numbers and variant positions are local to the archive.

Deploy support for both document versions in archive importers before replacing their installed ZIPs. The recipe indexes still use version 1 and the same paths.

## Banner and armor design data

The Design Data JAR registers two independently configurable public modules. Each exposes GET `data` and `texture?hash=<sha256>` beneath its discovered route prefix. No player data is needed. The JSON contract has `version: 1`. Texture properties hold content hashes. A module can serve only PNGs listed in its own exported manifest. An enabled module without an applicable export returns `404 design_export_missing` on the data route.

- `webcraft_designs:banners` exposes the banner base and cloth textures, front-face UV rectangle, dye palette, six-layer loom limit, and registered loom patterns with usable sprites. Each pattern's `templates` array lists the item IDs accepted for that layer. An empty array means the loom accepts the pattern without a template.
- `webcraft_designs:armor` exposes supported vanilla armor items, base texture layers with an optional tint, trim patterns and materials, and texture profiles referenced by each piece. Profiles use the actual atlas sprite for each pattern and material, including armor-material overrides and the separate leggings sprites. Its separate GET `geometry` route serves baked classic and slim player models, their skin overlays, and the worn armor meshes for `head`, `chest`, `legs` and `feet` from `armor_geometry.json`. Mesh arrays contain consecutive quads with eight values per vertex in position, UV and normal order. The `data` projection remains available for front views when geometry or WebGL is unavailable. Custom armor models require separate export support.
- GET `skin?name=<Java-player-name>` on the armor module looks up the player's published skin using Minecraft's official profile, session and texture services. It returns a JSON object with `name`, `slim` and a base64 PNG. The server uses HTTPS for all requests. The route accepts Java Edition usernames and caches successful results for two minutes.

A catalog export stores the manifests under `designs/webcraft_designs/` and shares content-addressed PNGs under `textures/`. The client resolves the active resource pack and current world's registered patterns. An older pack remains valid for inventory and catalog previews. Use a catalog export containing these manifests to enable the design views.

The banner manifest can also include `slotPreviews.banner` and `slotPreviews.shield`. Each supplies 64 × 64 base renders keyed by dye ID and neutral pattern overlays keyed by pattern ID. These frames are captured in the inventory pose, with separate shield artwork and geometry. Tint each overlay with its selected dye and composite it in the original layer order. Shader blending and PNG quantization can produce small differences from a complete stack captured in one pass.

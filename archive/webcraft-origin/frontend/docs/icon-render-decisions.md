# Icon Render Decisions

This note tracks the paths we tried for item and model rendering, and why they were dropped.

This document is the authoritative NeoForge 1.21.1 model/export contract for the frontend renderer work in this project.

## Tried and dropped

### Frontend snapshot upload
- Idea: let the frontend render a model, upload a PNG snapshot, and use that as a golden image.
- Why it was dropped: it added a debug-only pipeline that did not belong in the frontend runtime and created too much contract noise.
- Result: removed from the frontend. The frontend now only consumes server-provided icon URLs.

### Shield debug baseline is model-only

- The current shield debug route and snapshot harness are working tools, not the final visual contract.
- The first verification step for shield is intentionally **model only**:
  - no texture layer
  - no tilt / display transform overlay
  - no local shield-specific visual hacks
- The goal is to validate the raw model silhouette first, then add tilt as a separate step, and only after that add texture.
- If a shield screenshot still shows texture or a composed look, that is a sign that the baseline is still too high-level and should be reduced further before using it as a reference snapshot.
- The current snapshot is therefore a working checkpoint, not the final model-only baseline.

### URL-driven snapshot toggles
- Idea: enable snapshot capture through GET parameters.
- Why it was dropped: query flags are too easy to leak into normal usage and make the behavior implicit.
- Result: removed.

### Browser-side model composition as the main source of truth
- Idea: render shield or model variants entirely in the browser.
- Why it was dropped: this was the wrong layer for the long-term solution, especially for modpack coverage and server-owned icon caching.
- Result: the frontend now prefers `iconPath` / `iconUrl` and leaves materialization to the server side.

## Current direction

- Server owns icon materialization and cache storage where available.
- Frontend renders model-first when the payload includes geometry and texture data.
- Model payloads may still expose `iconPath`, but that is an optimization, not the only supported path.
- `shield` remains a special case because it is routed through builtin/special model metadata rather than a generic generated-item model.
- Public item payloads use `renderMode: "layered"` for every 2D icon and `renderMode: "model"` for model-backed renders.
- A single-texture item is still `layered`; it carries `textureLayers.layer0`.
- `texturePath` is kept as a compatibility shortcut for older frontend code, but `textureLayers` is the canonical 2D icon input.
- The backend normalizes older/internal `item` and `block` modes to `layered` and `model` before serializing item payloads.

## Scope and target version

- The primary implementation target is NeoForge on Minecraft 1.21.1.
- This is the main compatibility target because the biggest modpacks have moved there.
- The goal is to make the renderer work well for that environment first.
- This document should be read as a NeoForge 1.21.1 renderer spec, not as a vanilla-port spec.

## Asset/model separation for future exports

- Keep `/mc-assets` for server-owned runtime assets:
  - PNG icons
  - textures
  - cache outputs
  - server-generated files
- Keep `/mc-models` for client-exported model data:
  - canonical model metadata
  - geometry / mesh exports
  - override rules
  - display / pose transforms
  - builtin entity/item render exports
- The split avoids accidental overwrites between server runtime files and client-uploaded exports.
- It also makes cleanup, versioning, and debugging much simpler because asset cache and model exports are never mixed in the same path.
- The frontend should treat these as different source types, not interchangeable file stores.
- For the current NeoForge 1.21.1 workflow, the working export root is `run/config/webcraft-model-exports/`.
- At this phase, that directory can remain the live input for the renderer pipeline; no extra transfer step is required yet.

### Naming and versioning rules

- Use a canonical namespace-first path for served resource-pack assets and exported models.
  - Example texture asset path: `/mc-assets/textures/item/diamond_sword.png`
- Example model path: `/mc-models/exported/minecraft/item/shield/1.21.1/client-export.json`
- Never store client exports directly in a path that also serves server cache output.
- Include the source version in the model export path or metadata.
  - At minimum: Minecraft version
  - Preferably also modpack/build identifier when exporting from a specific environment
- Keep one logical file per canonical model/export entry.
  - If a model or entity changes across versions, write a new versioned path instead of overwriting the old export in place.
- Treat `default` as an explicit variant key only when the model truly has no additional state.
- When a model has multiple variants, encode them in the path or metadata instead of reusing the same filename.
- On upload, the server should reject writes that would collide with an existing immutable export unless the request explicitly targets an update workflow.
- Runtime cache files in `/mc-assets` may be overwritten by the server only if they are derived outputs, not source exports.
- Client exports in `/mc-models` should be considered source-of-truth inputs and should not be silently mutated by runtime cache logic.
- If a model export is regenerated, prefer writing a new versioned sibling file over in-place replacement.
- The frontend should derive its lookup keys from the canonical versioned export name, not from a mutable cache alias.
- For the current project phase, we do not need to overdesign upload security in this note.
- Treat `/mc-models` delivery as an administrative file transfer workflow, for example via FTP or equivalent operational tooling.
- The important part for the spec is that model exports stay separate from runtime assets and use canonical versioned paths.
- Do not optimize this spec for a future vanilla server port. If vanilla later matters, it should be a separate design pass.

## Research note

- Before porting to WebGL, we need to map the exact vanilla Minecraft item rendering steps.
- The client-side pieces involved are `ItemRenderer`, `BakedModel` / `BakedModelManager`, `ItemColors`, and `BuiltinModelItemRenderer`.
- I did not find a maintained public Java library that provides a drop-in, 1:1 vanilla item renderer for modern Minecraft without a client runtime.
- So the WebGL path must be our own implementation of the vanilla steps, not a wrapper around an existing renderer library.

## Vanilla item rendering model

Minecraft does not render every item the same way. The client resolves a baked model, then renders it according to the model type and display context.

### 1. Flat item icons

- Classic inventory icons usually come from `minecraft:item/generated`.
- These models use `textures.layer0`, `textures.layer1`, and `textures.layer2`.
- Fabric docs note that `<layerN>` only works when the parent is `item/generated`.
- This is the common path for simple items like sticks, ingots, tools with 2D icon models, and most mod items.

### 2. Handheld or perspective-tuned items

- Some items still use flat generation, but different display transforms.
- `display` entries define how the model appears in `gui`, `ground`, `fixed`, `firstperson_*`, and `thirdperson_*`.
- The renderer uses the baked model plus the current display context to choose the transform.

### 3. Block-style item models

- Items that are visually block-like use cube or element-based models.
- In those cases the model contains `elements`, `faces`, `uv`, and texture references rather than only `layerN`.
- `gui_light` can be `front` or `side`.
  - `front` makes the item look like a flat icon in GUI.
  - `side` makes it render more like a block.

### 3a. Layered items, dyeable armor, and shield banners are different mechanisms

- `layerN` in `item/generated` is a model-texture layering mechanism.
- Dyeable armor is not the same thing as `layerN`; its final color comes from item data / dye state and the renderer's tint handling.
- Shield banners are not just another `layerN` texture.
- Shield/banner visuals are driven by a special shield render path plus the banner pattern data stored on the item.
- For WebGL reconstruction, these cases should be treated separately:
  - flat layered item textures
  - dyeable/tinted armor
  - shield + banner composite rendering

### 3b. Enchanted item glint is a separate overlay effect

- Enchanted items can render with a shimmering glint effect.
- The glint is not part of the base model geometry.
- It is an additional render overlay / effect applied after the item model is drawn.
- For WebGL reconstruction, glint must be treated as a separate pass:
  - render the base item first
  - then apply the glint overlay if the item has enchantment glint enabled
- This effect can appear on many item types, so it should not be hardcoded only for one model class.

### 3c. Item overrides select the model before transform/render

- Vanilla item models can define overrides that choose a different baked model based on item state.
- `ItemOverrides` resolves using stack/runtime inputs such as:
  - `ItemStack`
  - `ClientLevel`
  - `LivingEntity`
  - seed / random input
- The override step happens before the selected model is rendered with its `display` transform.
- For WebGL reconstruction, this is a separate selection step:
  - first resolve the model from state
  - then apply the model's geometry and transforms

### 3d. Glint is rendered as its own effect layer

- The enchanted glint is not baked into the model JSON.
- It is a render effect applied on top of the item render.
- Vanilla treats glint as a separate visual layer, so WebGL should do the same rather than folding it into the base texture.
- The important implementation detail is that glint is an overlay pass, not an alternate model.

### 4. Special / built-in renderers

- Some items bypass the normal baked-model path and use a built-in renderer.
- The important example for us is `minecraft:item/shield`, which is handled as a special model path.
- In our data, that comes through `builtin/entity` / special model metadata and does not behave like a simple flat `item/generated` icon.
- The frontend should resolve the model payload first, then decide whether the render path is generic model geometry, a builtin/special branch, or a non-model fallback.

### 4a. Shield with banner is a real composite, not a plain texture swap

- A shield can carry banner pattern data.
- Vanilla treats that as a shield-specific composite render, not as a normal layered icon.
- The banner design on a shield is visibly different from a full banner, and the pattern resolution is smaller than the banner item itself.
- The relevant data for the shield icon is therefore:
  - the shield base
  - the banner pattern list
  - the banner base color
  - the shield-specific pattern atlas / shield render path
- For WebGL, the shield renderer must combine these inputs into one final image instead of trying to reuse the banner item icon directly.
- The builtin shield shape itself is not stored as a separate public item model JSON with geometry.
- The shield is rendered through a client-side builtin renderer path (`builtin/entity` + `BuiltinModelItemRenderer` + `ShieldEntityModel`), so the shape has to be reconstructed from client code or faithfully reimplemented.
- In the current WebCraft frontend, that means the shield path should consume the model payload contract directly and keep geometry resolution separate from the WebGL rasterizer.

### 4b. Other builtin / special item render paths

The vanilla special-renderer family includes more than shield. The renderer surface we need to account for includes:

- `minecraft:banner`
- `minecraft:bed`
- `minecraft:chest`
- `minecraft:conduit`
- `minecraft:shulker_box`
- `minecraft:standing_sign`
- `minecraft:hanging_sign`
- `minecraft:head`
- `minecraft:player_head`
- `minecraft:trident`
- `minecraft:shield`

These are not all identical in implementation, but they share the same important property for our renderer:
- they are not plain `item/generated` flat icons
- they depend on special renderer or special model handling
- some of them are block-entity style renders rather than simple item quads

For WebGL reconstruction, each of these must be categorized as either:
- a client-side special renderer path,
- a block-entity-like render path,
- or a model-driven item path with special transforms.

The safe assumption is that they must not be collapsed into one flat bitmap fallback.

### 5. What the vanilla renderer effectively does

- Resolve the item stack to a model.
- Apply model overrides and special handling.
- Bake the model into renderable geometry.
- Apply item colors / tint layers where needed.
- Render in the requested context using the model transforms.

### 5c. Model lookup and fallback chain

- An item does not usually store a single final icon bitmap.
- Vanilla resolves the visible result through a lookup chain:
  1. select the item model from resource data
  2. follow the model's `parent` chain if present
  3. resolve texture references from inherited model data
  4. apply `ItemOverrides` when the stack or runtime state requests a different model
  5. render the selected baked model in the current context
- The final icon is therefore a render result, not the primary item definition.
- For WebGL reconstruction, this means the pipeline must be model-first and fallback-aware, not bitmap-first.
- In practice, the frontend should keep the resolved model contract around long enough for the renderer to decide between geometry, builtin special handling, or an intentional empty result.

### 5d. Vanilla item render categories

Vanilla item rendering falls into a small number of practical categories:

1. **Flat layered models**
   - `item/generated`
   - `layer0`, `layer1`, `layer2`
   - used for simple icons and many mod items

2. **Handheld / perspective-tuned models**
   - usually built from flat or inherited models
   - use `display` transforms tuned for hand-held viewing
   - common for tools and weapons

3. **Block-style baked models**
   - use `elements`, `faces`, `uv`, and texture references
   - rendered with geometry instead of just layered quads
   - often used for block items or visually block-like items

4. **Tinted / dyeable items**
   - base model is still a model, but final color comes from item state and renderer tint logic
   - examples include dyeable armor

5. **Composite special cases**
   - combine multiple item states into one visible output
   - examples include shield + banner and enchanted glint

6. **Built-in / special renderers**
   - bypass the normal generic baked-model path
   - shield is the key example we care about

For WebGL implementation, each category should be mapped to a distinct code path or at least a distinct branch in the renderer pipeline. Treating everything as one generic icon path will lose vanilla behavior.

The current frontend classification is slightly more concrete because it distinguishes the resolved model contract from the rasterizer:

1. `layered`
   - every 2D inventory icon, including single-texture items
   - generated item model with `layer0`, `layer1`, etc.
   - flat 2D layers
   - tintable where needed

2. `model`
   - block/entity/item geometry resolved from `renderData`, `meshJson`, or `modelJson`
   - uses elements/quads, display transforms, UVs, alpha, lighting
   - this is the primary WebGL path for model-first items

The frontend may still use an internal `image` optimization when an already-rendered icon URL exists, but that is not a public backend `renderMode`.

### 5e. Formal contract for `display` and `ItemOverrides`

- `display` is a property of the model definition.
- It provides per-context transform data.
- It is not derived from the item stack.
- The renderer chooses one of the following contexts before drawing:
  - `gui`
  - `ground`
  - `fixed`
  - `firstperson_righthand`
  - `firstperson_lefthand`
  - `thirdperson_righthand`
  - `thirdperson_lefthand`
  - `head` when applicable in some model systems
- Each context may define:
  - `rotation`
  - `translation`
  - `scale`
- If a context is missing, resolve it through the parent chain first.
- If the context is still missing after parent resolution, use the neutral/default transform for that context instead of inventing a custom fallback.
- The renderer must not synthesize transform values from the item stack.
- `ItemOverrides` is the model selection layer before transform/render.
- It takes runtime state inputs and returns a potentially different baked model.
- The important runtime inputs are:
  - `ItemStack`
  - `ClientLevel`
  - `LivingEntity`
  - random seed / deterministic item render seed
- Override resolution happens before display transforms are applied.
- WebGL must therefore do:
  1. resolve model from stack/runtime state
  2. pick the display context
  3. apply the model's own transform for that context
  4. render the resulting baked model

### 5f. Builtin render path subtypes

Builtin and special paths are not one thing. For the WebGL plan, split them into:

1. **Client-side entity-model special renderers**
   - shield
   - trident
   - player head / skull variants

2. **Block-entity-like item renderers**
   - banner
   - bed
   - chest
   - shulker box
   - conduit
   - sign-like paths where the item render is a proxy for block/entity rendering

3. **Model-driven special items with overrides**
   - items that still use JSON model data but select special variants through override predicates

For WebGL, these subtypes need separate implementation branches because their inputs and geometry sources are different.

### 5g. NeoForge 1.21.1 priority list for model overrides

For the current target, the most important override-driven model families to cover first are:

**Phase 1**

1. **Bow**
   - uses pull/pulling-style state changes
   - the visible model changes as the item is drawn

2. **Crossbow**
   - uses charged / pulling-style state changes
   - the visible model changes depending on charge state

3. **Compass**
   - uses state-driven model selection rather than a single fixed bitmap

4. **Clock**
   - similar to compass in that the visible state changes dynamically

5. **Fishing rod / similar stateful held items**
   - visible representation can change based on item state

6. **Damage- or variant-driven tools**
   - items whose look changes by state rather than by geometry alone

**Phase 2**

- Additional predicate-driven item families from modpacks that use custom model overrides beyond the core vanilla set.

The reason for this priority order is practical:
- these are common in modded packs
- they exercise the override pipeline early
- they validate that the renderer can select the correct baked model before applying `display` transforms

Shield is still a special case, but these override-driven families are the best early test set for the general model resolution pipeline.

### 5h. NeoForge 1.21.1 priority list for builtin / special render paths

For special render paths, the best first targets are:

**Phase 1**

1. **Shield**
   - canonical special item renderer path
   - exercises builtin model handling and composite banner state

2. **Player head / skull**
   - common special render path with client-side model/render behavior

3. **Trident**
   - useful built-in special item renderer case

4. **Banner**
   - exercises pattern/color compositing and block-entity-like visual behavior

5. **Chest / shulker box / bed / conduit / sign-like item previews**
   - these validate block-entity-like or hybrid render paths

**Phase 2**

### 5i. Shield UV contract

Shield texture mapping is not a bbox-to-texture approximation.
It follows the vanilla cuboid UV contract used by `ModelPart.Cube` for the shield model parts.

Confirmed vanilla source:
- `ShieldModel.createLayer()`
- `CubeListBuilder.texOffs(...)`
- `CubeDefinition.bake(...)`
- `ModelPart.Cube` face UV layout

Shield part layout:
- `plate`: `texOffs(0, 0)`, size `12 x 22 x 1`
- `handle`: `texOffs(26, 0)`, size `2 x 6 x 6`

The cuboid face UV layout is the standard vanilla one:
- `DOWN`, `UP`, `WEST`, `NORTH`, `EAST`, `SOUTH`
- each face receives its own UV rectangle from the cuboid's `texOffs`
- the renderer must sample the texture per face, not stretch a single bitmap over the projected outline

This is the contract the frontend must use for shield rendering.

- Any additional client-side builtin renderers discovered in the modpack export workflow.
- Additional entity-preview style render paths used by mods in inventory or UI slots.

Priority rationale:
- shield is the most important composite special item for us right now
- heads and trident are simpler follow-ups for client-side special rendering
- banner/chest/shulker/bed/conduit/sign-like items are useful for proving the renderer can handle non-flat, non-simple model cases

### 5e. Technical WebGL plan

To implement the vanilla item renderer in WebGL, the renderer should be split into these steps:

1. **Model resolution**
   - Input: `modelId`, stack/runtime state, context
   - Output: resolved model definition
   - Responsibilities:
     - fetch model JSON
     - follow `parent`
     - resolve textures from inherited parents
     - apply `ItemOverrides` when the stack changes the visual model

2. **Model classification**
   - Determine which render category the resolved model belongs to:
     - flat layered
     - handheld
     - block-style
     - tinted/dyeable
     - composite special case
     - built-in/special renderer
   - This is a routing step, not rendering yet.

3. **Geometry bake**
   - Convert the resolved model into renderable geometry.
   - For flat layered models, this means quads from layered textures.
   - For block-style models, this means elements/faces/uv baked into faces.
   - For special cases, this may produce custom geometry or a custom render path.

4. **Transform application**
   - Apply the model's `display` transform for the active context.
   - Use the model's own per-context values for rotation/translation/scale.
   - This is separate from model selection and separate from geometry bake.

5. **Color and tint pass**
   - Apply tint data from the renderer state.
   - This covers dyeable armor and any other item-color driven variants.

6. **Overlay pass**
   - Render glint if enabled.
   - Render any other effect overlays that behave like a post-pass.

7. **Special composite path**
   - Handle shield + banner as a dedicated composite step.
   - This path combines base shield data with banner pattern data before final output.

### 5f. Data structures the WebGL renderer needs

The renderer should work with explicit intermediate objects, not ad hoc strings:

- `ResolvedItemModel`
  - `modelId`
  - `parentModelId`
  - `display`
  - `guiLight`
  - `textures`
  - `elements`
  - `overrides`
  - `renderCategory`

- `RenderContext`
  - current view context
  - item stack state
  - seed / runtime inputs used by overrides
  - tint/color flags
  - glint flag

- `BakedItemGeometry`
  - quads or faces ready for rendering
  - texture references bound to the baked geometry
  - any special composite metadata

- `RenderedItemFrame`
  - final pixels or a WebGL target
  - no model logic here anymore

### 5g. Suggested implementation order

1. Flat layered items
2. Handheld items
3. Block-style baked models
4. Tint / dye application
5. Glint overlay
6. Shield + banner composite
7. Remaining built-in special renderers

This order keeps the renderer useful early while still leaving room for the difficult special cases.

### 5a. Where rotation and scale come from

- The rotation, translation, and scale are not taken from the item stack itself.
- They come from the model JSON `display` section.
- Each display context can define its own transform block, for example:
  - `gui`
  - `ground`
  - `fixed`
  - `firstperson_righthand`
  - `firstperson_lefthand`
  - `thirdperson_righthand`
  - `thirdperson_lefthand`
- The transform is a per-context override, not a per-item runtime value.
- In practice this means:
  - the same item can look flat in GUI and angled in hand
  - the same baked model can be reused with different transforms
- Item stack state does not define the transform block, but it can still affect which model is selected before rendering via model overrides.
- Vanilla model overrides are resolved from stack/runtime state, then the selected baked model is rendered with the model's own `display` transforms.
- For WebGL reconstruction, the important inputs are:
  - baked model geometry
  - override resolution from `ItemStack`, level, entity, and seed when applicable
  - context-specific display transform
  - `gui_light`
  - tint / color state

### 5b. Concrete vanilla examples

- Vanilla `minecraft:item/shield` defines explicit `display` transforms in the model JSON.
- In the Minecraft 1.21.1 vanilla asset, shield uses:
  - `gui_light: front`
  - `thirdperson_righthand`: rotation `[0, 90, 0]`, translation `[10, 6, -4]`, scale `[1, 1, 1]`
  - `thirdperson_lefthand`: rotation `[0, 90, 0]`, translation `[10, 6, 12]`, scale `[1, 1, 1]`
  - `firstperson_righthand`: rotation `[0, 180, 5]`, translation `[-10, 2, -10]`, scale `[1.25, 1.25, 1.25]`
  - `firstperson_lefthand`: rotation `[0, 180, 5]`, translation `[10, 0, -10]`, scale `[1.25, 1.25, 1.25]`
  - `gui`: rotation `[15, -25, -5]`, translation `[2, 3, 0]`, scale `[0.65, 0.65, 0.65]`
  - `fixed`: rotation `[0, 180, 0]`, translation `[-4.5, 4.5, -5]`, scale `[0.55, 0.55, 0.55]`
  - `ground`: rotation `[0, 0, 0]`, translation `[2, 4, 2]`, scale `[0.25, 0.25, 0.25]`
- The vanilla `item/handheld` model used by tools and swords defines:
  - `thirdperson_righthand`: rotation `[0, -90, 55]`, translation `[0, 4.0, 0.5]`, scale `[0.85, 0.85, 0.85]`
  - `thirdperson_lefthand`: rotation `[0, 90, -55]`, translation `[0, 4.0, 0.5]`, scale `[0.85, 0.85, 0.85]`
  - `firstperson_righthand`: rotation `[0, -90, 25]`, translation `[1.13, 3.2, 1.13]`, scale `[0.68, 0.68, 0.68]`
  - `firstperson_lefthand`: rotation `[0, 90, -25]`, translation `[1.13, 3.2, 1.13]`, scale `[0.68, 0.68, 0.68]`
- These values are concrete examples of how the renderer applies different transforms per context. They are not item-stack runtime values.

### 6. What this means for our WebGL port

- We cannot treat every item as a single PNG.
- We need support for at least:
  - flat layered icons
  - block-style cube / element models
  - special built-in paths like shield
- The shield path should be treated as a special case, not as a generic flat icon.
- The WebGL renderer should mirror the same model resolution and transform flow the client uses, not a texture-only fallback.

## Why this matters

- It keeps the frontend Java-free and simpler.
- It avoids coupling debug tooling to the production UI.
- It leaves room for a dedicated server-side renderer without reintroducing browser-only behavior.

## Implementation checklist

### Phase A: Backend and mod export contract

- [x] Lock the canonical `/mc-models` export schema for NeoForge 1.21.1.
- [x] Define the minimal export fields for flat items, handheld items, block-style models, and builtin/special renderers.
- [x] Add versioned export naming rules for `modelId`, `variant`, and source version.
- [x] Add a client-side export command or tool path that can write model exports into `/mc-models`.
- [x] Define the upload/import workflow so an admin can move client exports onto the server without mixing them with `/mc-assets`.
- [x] Add the backend transport endpoints for `/mc-models` and `/api/mc-models/import`.
- [ ] Identify the first bottleneck models to export from the modpack:
  - [ ] shield
  - [ ] bow
  - [ ] crossbow
  - [ ] compass
  - [ ] clock
  - [ ] player head / skull
  - [ ] trident
- [x] Verify that exported model data contains enough information for transforms, overrides, and builtin/special branches.

#### Phase A export performance target

The export step should be treated as a cacheable normalization pipeline, not as a full rebuild every run.

Target behavior:

- export only items that do not already have a stable, server-usable `iconPath` / `iconUrl`
- skip entries whose resolved export hash did not change
- cache resolved parent chains so the same inherited model tree is not rebuilt for every item
- reuse texture and animation metadata across exports when the source asset is identical
- write only changed files to disk
- keep full rebuilds as an explicit maintenance command, not the default workflow

Recommended export phases:

1. discovery
   - enumerate candidate items and model ids
   - separate `image`, `layered`, `model`, and `special` candidates early

2. resolution
   - resolve parent chain
   - merge textures
   - collect display transforms
   - collect overrides
   - resolve builtin/special metadata

3. normalization
   - produce a canonical export object
   - compute a stable content hash over the normalized object
   - mark unchanged exports as cache hits

4. materialization
   - write only changed exports
   - emit or refresh any derived asset only when the normalized contract changed

Suggested cache key:

```text
cacheKey = modelId + sourceVersion + sourcePackId + sourceHash + variant + rendererType
```

Invalidation should happen when any of these change:

- source model JSON
- parent chain target
- override definitions
- texture alias map
- texture file content hash
- animation metadata
- builtin/special metadata
- source version or pack id

The export should not be invalidated by frontend-only concerns such as debug overlays or visual snapshots.

#### Phase A profiling questions

Before changing the implementation, measure how much time goes to each bucket:

- candidate discovery
- parent resolution
- texture resolution
- JSON serialization
- disk writes
- image processing

If most of the time is in parent/texture resolution, caching matters more than threading.
If most of the time is in disk writes, batching and change detection matter more than CPU concurrency.
Only if the hot path is still CPU-bound after caching should worker threads be considered.

#### Phase A export schema

The export must be model-first and versioned. A single export entry should look like:

```json
{
  "modelId": "minecraft:item/shield",
  "namespace": "minecraft",
  "kind": "builtin",
  "rendererType": "special",
  "variant": "default",
  "sourceVersion": "1.21.1",
  "sourcePackId": "neoforge-1.21.1",
  "parentModelId": "builtin/entity",
  "guiLight": "front",
  "display": {
    "gui": { "rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65] }
  },
  "textures": {
    "particle": "block/dark_oak_planks"
  },
  "overrides": [
    {
      "predicate": { "blocking": 1 },
      "modelId": "minecraft:item/shield_blocking"
    }
  ],
  "geometry": null,
  "special": {
    "composite": true,
    "bannerCarrier": true
  },
  "notes": [
    "Builtin shield renderer path is client-side, not a public JSON geometry."
  ]
}
```

#### Phase A validation rules

- `modelId` must be canonical and namespaced.
- `sourceVersion` must be explicit and match the target NeoForge/Minecraft version family.
- `kind` must distinguish flat, handheld, block-style, builtin, or entity-exported data.
- `rendererType` must say whether the frontend should treat the entry as generic model, special builtin, or entity-style export.
- `display` must contain only authoritative model transforms, not derived runtime transforms.
- `overrides` must be model-specific and preserve predicate definitions exactly as exported.
- `geometry` may be `null` for builtin/special renderer entries that reconstruct on the client export side.
- `special` flags must be explicit for composite cases such as shield/banner.

#### Phase A acceptance criteria

- [ ] The export is stable for the same source version and pack id.
- [ ] The export path is canonical and versioned.
- [ ] The export contains enough data for the frontend to classify the render path.
- [ ] Builtin/special items are clearly marked as such.
- [ ] Shield exports do not pretend to be flat icons.
- [ ] The contract can be consumed by the frontend without guessing missing transforms.

#### Phase A.1 client export command/tool target

- [x] Add a client-side command or developer tool that can emit a `webcraftMcModelsContract` payload.
- [x] Make the export tool serialize the exact same fields that the backend import endpoint validates.
- [x] Keep the export output versioned and namespaced so it can be moved directly into `/mc-models`.
- [ ] Add the first exporter targets:
  - [ ] shield
  - [ ] bow
  - [ ] crossbow
  - [ ] compass
  - [ ] clock
  - [ ] player head / skull
  - [ ] trident

### Phase B: Frontend model consumer

- [ ] Add a strict loader for `/mc-models` exports.
- [ ] Resolve model `parent` chains.
- [ ] Apply `display` transforms per render context.
- [ ] Apply `ItemOverrides` before render.
- [ ] Route builtin/special paths into their own branches.
- [ ] Keep `shield` as a separate composite path.

### Phase C: WebGL renderer core

- [ ] Implement flat layered model rendering.
- [ ] Implement handheld model rendering.
- [ ] Implement block-style baked model rendering.
- [ ] Implement tint / dye application.
- [ ] Implement glint overlay.
- [ ] Implement shield + banner composite rendering.
- [ ] Implement the first builtin/special renderers from the priority list.

### Phase D: Expanded coverage

- [ ] Add the remaining builtin/special render paths.
- [ ] Add more predicate-driven overrides from modpack exports.
- [ ] Add entity preview exports if the modpack needs them in inventory/UI slots.

### Execution order

1. Backend/model export contract
2. Client export command or export tool
3. Export the bottleneck models
4. Frontend loader and resolver
5. WebGL renderer core
6. Special-case expansion

This order is intentional: the current bottleneck is not the renderer code alone, but getting correct model data out of the mod/client side first.

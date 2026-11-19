# Minecraft Inventory Item Rendering Notes

These notes describe how Minecraft renders item icons in inventory-like UI and what WebCraft needs to reproduce in a WebGL renderer.

The goal is not to clone every renderer detail immediately. The goal is to document the pipeline clearly enough to rebuild the current Canvas2D/model renderer as a model-first WebGL renderer.

## Source References

Observed from local Minecraft/NeoForge 1.21.1 sources:

- `net.minecraft.client.gui.GuiGraphics`
- `net.minecraft.client.renderer.entity.ItemRenderer`
- `net.minecraft.client.renderer.block.model.ItemTransform`
- `net.minecraft.client.renderer.block.model.ItemTransforms`
- `net.minecraft.client.renderer.block.model.BlockModel`
- `net.minecraft.client.renderer.block.model.BlockElement`
- `net.minecraft.client.renderer.block.model.BlockElementFace`
- `net.minecraft.client.renderer.block.model.BlockFaceUV`
- `net.minecraft.client.renderer.FaceInfo`
- `net.minecraft.client.renderer.block.model.FaceBakery`

Relevant vanilla assets for model data:

- `assets/minecraft/models/item/*.json`
- `assets/minecraft/models/block/*.json`
- `assets/minecraft/blockstates/*.json`
- `assets/minecraft/textures/**/*.png`
- `assets/minecraft/textures/**/*.png.mcmeta`

## High-Level Pipeline

Inventory item rendering follows this order:

1. Resolve the `ItemStack` model.
2. Resolve item model overrides.
3. Enter GUI rendering context.
4. Move origin to the center of the 16x16 slot.
5. Scale model-space to icon pixels.
6. Apply the model's `display.gui` transform.
7. Move baked model geometry from centered unit space.
8. Render baked quads using atlas textures, alpha, depth, normals, and lighting.
9. Render decorations after the item: count text, durability bar, cooldown overlay, enchant glint.

For WebCraft's current inventory icon scope, the important part is steps 1-8. Decorations can remain a separate UI layer.

## GUI Slot Transform

Minecraft renders an inventory item around the slot center:

- Slot size is `16x16`.
- GUI item center is `(x + 8, y + 8)`.
- Z is lifted around `150`, with additional z for GUI-3D models.
- Pose is scaled by `(16, -16, 16)`.

The negative Y scale flips model space into screen space.

For WebGL, a practical equivalent is:

```text
slot_center = vec3(8, 8, z)
screen_scale = vec3(16, -16, 16)
```

Then project into a 16x16 framebuffer or into a larger canvas with the same logical coordinate system.

Important: this means the renderer should not crop a larger render to 16x16. It should render into a 16x16 logical item viewport.

## Item Display Context

Inventory icons use `ItemDisplayContext.GUI`.

The selected transform comes from the model's `display.gui` field after parent inheritance is resolved.

Example common block transform inherited from `block/block`:

```json
{
  "rotation": [30, 225, 0],
  "translation": [0, 0, 0],
  "scale": [0.625, 0.625, 0.625]
}
```

Minecraft deserializes `translation` in model JSON as sixteenths:

```text
translation_json / 16
```

Rotation order matters. `ItemTransform.apply` uses JOML `rotationXYZ`, which builds an XYZ quaternion. For column-vector math this means the effective point transform is:

```text
R = Rx * Ry * Rz
point' = R * point
```

So when transforming a point manually, Z is applied first, then Y, then X.

For left-hand transforms Minecraft flips Y/Z rotation signs and X translation. Inventory GUI is not left-hand.

## Model Coordinate Space

Block/item element JSON uses coordinates in a 16-unit cube:

```text
from: [x1, y1, z1]
to:   [x2, y2, z2]
```

`FaceBakery` converts those to normalized coordinates:

```text
normalized = value / 16
```

Model rotation is applied around the block center:

```text
center = [0.5, 0.5, 0.5]
```

Element rotation is applied before model/display transform. Element rotation uses:

- `origin` from JSON, also in 16-unit model coordinates.
- `axis`: `x`, `y`, or `z`.
- `angle`: usually `-45`, `-22.5`, `0`, `22.5`, `45`.
- optional `rescale`.

Implementation order:

```text
1. build vertices from from/to
2. apply element.rotation around element.rotation.origin
3. normalize or keep consistent 16-unit space
4. apply display.gui transform around model center
5. apply GUI slot transform and projection
```

WebCraft can keep everything in 16-unit space if the transforms consistently use `[8, 8, 8]` as model center instead of `[0.5, 0.5, 0.5]`.

## Face Vertex Order

Minecraft uses `FaceInfo` to map a face direction to four corner vertices.

Equivalent vertex order in 16-unit box corner indices:

```ts
const faceVertices = {
  down:  [4, 0, 1, 5],
  up:    [3, 7, 6, 2],
  north: [2, 1, 0, 3],
  south: [7, 4, 5, 6],
  west:  [3, 0, 4, 7],
  east:  [6, 5, 1, 2],
};
```

This order matters for:

- UV mapping.
- face normals.
- backface culling.
- depth results for flat faces.

## UV Mapping

Block face UV is `[u1, v1, u2, v2]` in 16x16 texture-space units.

`BlockFaceUV` maps vertex index to U/V and supports `rotation` values `0`, `90`, `180`, `270`.

Base mapping without UV rotation:

```ts
const uv = [
  [u1, v1],
  [u1, v2],
  [u2, v2],
  [u2, v1],
];
```

Then rotate the UV index by `rotation / 90`.

Texture coordinates are relative to the selected texture sprite. For standalone PNG rendering, normalize using the sampled image size:

```text
sample_x = u / 16 * frame_width
sample_y = v / 16 * frame_height
```

For animated textures, use the current frame dimensions, not the full atlas dimensions.

## Texture Resolution

Model face texture values are usually references:

```json
"texture": "#side"
```

The renderer must resolve aliases through the merged `textures` object:

```json
{
  "side": "minecraft:block/sculk_sensor_side"
}
```

Parent model texture inheritance is required. Child models may override only one texture and inherit geometry from the parent.

Example:

```json
// item/sculk_sensor.json
{ "parent": "minecraft:block/sculk_sensor_inactive" }

// block/sculk_sensor_inactive.json
{
  "parent": "minecraft:block/sculk_sensor",
  "textures": {
    "tendrils": "minecraft:block/sculk_sensor_tendril_inactive"
  }
}
```

The final renderable model must contain:

- element geometry from `block/sculk_sensor`
- texture alias override from `block/sculk_sensor_inactive`
- display transform inherited from `block/block`

## Animated Textures

Minecraft texture animation is declared in `.png.mcmeta`.

Example:

```json
{
  "animation": {
    "frametime": 2
  }
}
```

Many animated block textures are vertical atlases:

```text
frame_width = image_width
frame_height = image_width
frame_count = image_height / image_width
```

For a static inventory snapshot, use frame `0`.

For WebGL later:

```text
frame_index = floor(time / frametime) % frame_count
v_offset = frame_index * frame_height
```

If we render static snapshots, the backend should still expose the animation metadata so the FE can crop the first frame correctly.

## Alpha And Cutout

Minecraft block/item textures often rely on alpha cutout. This is critical for:

- plants
- glass-like details
- sculk sensor tendrils
- layered armor/item icons

For sculk sensor, the tendrils are not rectangular opaque quads in the final visual. They are flat rectangular planes whose PNG contains transparent pixels.

If the result looks like plain rectangles, one of these is wrong:

- alpha channel was lost when loading/sampling the texture
- the full animated atlas was sampled instead of a single frame
- UVs point to the wrong region
- premultiplied alpha or blending is wrong
- the fallback/debug texture is being used instead of the real tendril texture

Recommended WebGL state for cutout-like model textures:

```js
gl.enable(gl.BLEND);
gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
gl.disable(gl.CULL_FACE); // at least for zero-thickness/non-cullface faces
```

In fragment shader:

```glsl
vec4 color = texture2D(u_texture, v_uv);
if (color.a < 0.1) discard;
gl_FragColor = color * lighting;
```

Use discard for cutout faces to avoid transparent pixels writing depth.

## Culling

Minecraft model faces can include `cullface`, but inventory rendering is not the same as world neighbor culling.

For item icon rendering:

- Do not remove faces just because `cullface` exists.
- Backface culling must be handled carefully.
- Zero-thickness faces need both sides or no culling.

Sculk tendrils are flat planes with only `north` and `south` faces. They are designed to be visible from both relevant directions. If culling removes one side incorrectly, tendrils disappear or look wrong.

## Lighting

Minecraft chooses lighting mode based on the baked model:

- If model does not use block light, GUI switches to flat item lighting.
- Otherwise it uses 3D/block-style lighting.

Model JSON can define:

```json
"gui_light": "front"
```

or rely on inherited/default behavior.

Practical WebCraft target:

- `gui_light: front`: flatter item-like lighting.
- `gui_light: side`: directional block-like lighting.
- Use normals from transformed quads.
- Apply a small directional shade per face.

Known target for stairs/blocks: one visible side should be darker than the top/front, similar to Minecraft's inventory cube lighting.

## Render Types

Minecraft chooses render passes and render types from the baked model.

The backend item payload exposes two public render modes:

1. `layered`
   - every 2D inventory icon
   - single-texture items use `textureLayers.layer0`
   - multi-layer items use `layer0`, `layer1`, and future layers

2. `model`
   - block/entity/item model geometry
   - uses elements/quads, display transforms, UVs, alpha, lighting

The current goal is to make all model-like items use the same WebGL path as shield/block models.
`texturePath` can still appear as a compatibility shortcut for `textureLayers.layer0`, but new code should prefer `textureLayers`.
The backend should not expose `renderMode: "item"` or `renderMode: "block"` in item payloads; those are normalized to `layered` and `model`.

## WebGL Implementation Shape

The current frontend implementation is already split into two responsibilities:

1. model resolution
   - fetch and merge the model payload
   - resolve `renderData`, `meshJson`, `modelJson`, and `displayJson`
   - resolve inherited texture aliases and animation metadata
   - choose the correct geometry source for the item type

2. rasterization
   - project the resolved geometry into the item viewport
   - render the model with depth-aware triangle filling
   - apply alpha discard and basic face shading

For WebCraft, the WebGL path should stay model-first:

- geometry comes from the resolved model payload, not from a hardcoded miniature fallback shape
- if a special model does not provide geometry, the renderer should stay empty rather than inventing a stand-in mesh
- shield is a special builtin path backed by `builtin/entity`-style data, not a generic item cube
- WebGL should consume the same resolved model contract the current renderer uses, including `displayJson`, `meshJson`, `modelJson`, `texturePaths`, and `textureLayers`

The important invariant is that geometry resolution and rendering are separable. That lets WebCraft keep the model contract stable while changing the backend rasterizer from Canvas2D to WebGL.

## Special Models

Some items do not behave like normal JSON block models:

- shield uses `builtin/entity`
- trident/spyglass use special GUI/in-hand model routing
- compass/clock have animated property overrides
- enchanted items add glint render pass
- leather armor uses layered/tinted item textures

Do not fake geometry for special models. If the backend does not provide geometry/render data, the frontend should render empty or fall back to the correct non-model path, not invent shapes.

For the shield path specifically:

- `minecraft:shield` resolves through `modelId === "builtin/entity"` on the frontend side
- the model feed should preserve the shield base texture and builtin geometry metadata
- the renderer should not substitute a fake shield box if the builtin geometry is missing
- the GUI transform and shield-specific orientation logic must remain separate from the generic item-model path

## Sculk Sensor Specific Notes

Vanilla sculk sensor item model:

```json
{
  "parent": "minecraft:block/sculk_sensor_inactive"
}
```

Block state variants:

```text
sculk_sensor_phase=active   -> minecraft:block/sculk_sensor_active
sculk_sensor_phase=cooldown -> minecraft:block/sculk_sensor_active
sculk_sensor_phase=inactive -> minecraft:block/sculk_sensor_inactive
```

Base geometry:

- lower base cuboid: `[0,0,0]` to `[16,8,16]`
- four tendril planes above the base
- tendril planes are zero-thickness or near-zero-thickness faces
- tendril element rotations are around Y with `45` and `-45` degrees

Texture behavior:

- inactive model overrides `#tendrils` to `block/sculk_sensor_tendril_inactive`
- active model overrides `#tendrils` to `block/sculk_sensor_tendril_active`
- both tendril textures are animated via `.png.mcmeta`
- static icon should sample frame `0`

If sculk sensor renders as plain rectangles instead of tendrils, debug in this order:

1. Confirm the active FE payload is `minecraft:item/sculk_sensor` or `minecraft:block/sculk_sensor_inactive`, not `minecraft:block/sculk_sensor`.
2. Confirm merged `modelJson` contains both `elements` and overridden `textures.tendrils`.
3. Confirm `textureAnimations.tendrils` exists.
4. Confirm the loaded tendril PNG preserves alpha.
5. Confirm the renderer crops first animation frame before sampling.
6. Confirm transparent pixels are discarded before depth write.
7. Confirm culling is disabled for flat tendril faces.
8. Confirm UVs use `[4,8,12,16]` / `[12,8,4,16]` exactly from model JSON.

## WebGL Reconstruction Checklist

Minimum model renderer:

- Parse merged model JSON.
- Resolve parent-inherited `display.gui`.
- Resolve texture aliases.
- Carry through `renderData`, `meshJson`, and `texturePaths` as the authoritative model payload fields.
- Load texture images with alpha.
- Crop animated textures to frame `0` for static snapshots.
- Build vertices from each element's `from`/`to`.
- Apply element rotation around its origin.
- Build faces using Minecraft `FaceInfo` order.
- Apply UV mapping and UV rotation.
- Apply display GUI transform.
- Apply centered 16x16 GUI transform.
- Keep special builtin paths, such as shield, isolated from generic item geometry.
- Rasterize/render textured triangles with depth.
- Discard low-alpha fragments before depth write.
- Disable culling for flat/no-cull faces.
- Apply basic Minecraft-like directional lighting.

Tests should assert behavior by model feature, not just by snapshot:

- parent-only child model inherits geometry
- child texture override replaces parent texture alias
- animated texture crops to first frame
- zero-thickness alpha faces are not rendered as opaque rectangles
- `display.gui.rotation` order matches Minecraft
- 360-degree rotation matches front snapshot
- back/side/debug views do not mutate model state

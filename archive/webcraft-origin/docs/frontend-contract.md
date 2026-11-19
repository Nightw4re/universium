# WebCraft Origin frontend contract

Historical reference for the retired WebCraft Origin module. See the [active WebCraft contract](../../../modules/webcraft/README.md#rest-v1).

WebCraft Origin exposed backend data for the [legacy frontend](../frontend/README.md). In the original Gradle layout, `gradle :webcraft:syncFrontendWebroot` built that frontend and prepared its local webroot.

For item rendering, the frontend should treat the backend payload as the source of truth:

- `renderMode: "layered"` means an inventory slot icon assembled from one or more explicit layers; a normal single-image item is just one layer
- `renderMode: "model"` means a reusable model-driven render path identified by `modelId`
- `texturePath` may still appear as a compatibility shortcut for `textureLayers.layer0`, but new frontend code should prefer `textureLayers`
- public item payloads should not expose `renderMode: "item"` or `renderMode: "block"`; those are normalized by the backend to `layered` and `model`
- `unsupported` and `exportRequired` are explicit diagnostic states for missing render contracts, not successful render modes
- `/mc-assets/generated-icons` is not a render contract; if Minecraft generates the item appearance, the backend exposes an exported layered composition or model contract instead of a synthetic icon URL

Asset ownership is explicit:

- direct item images are served live, unchanged, from the Minecraft/web asset store
- layered items expose direct images, exported slot images, tint metadata, masks, transforms, and ordered overlay layers as needed
- model-rendered items must expose model JSON, texture references, UV/mesh data when available, and item state so any frontend can render them itself
- the dedicated server must not render item previews, because it may not have a Minecraft client render engine
- builtin/special renderer data that only exists client-side must be produced by `webcraft export models`; administrators upload those exported files to the server
- static generated items may be deployed as finished PNGs and returned as `layered`
- variant-heavy items such as banners and shields should use exported base/mask assets plus item-specific layered state

When the frontend fetches `GET /api/model/{modelId}`, it should:

1. Parse `modelJson` as the raw vanilla model JSON.
2. Use `parentModelId` to resolve inheritance if a texture slot is missing locally.
3. Resolve the `textures` object like Minecraft does:
   - use direct texture references as asset paths
   - resolve `#slot` references against the current model first
   - then walk the parent chain if the slot is still missing
4. Use `displayJson` as the pose / transform input for the active render context.
5. Never infer texture URLs from item names when the model already provides texture bindings.
6. Never infer geometry, colors, or texture paths from item ids when the backend provides a model contract.

For special renderer items:

- shulker boxes should be exported as finished inventory slot PNGs per color and returned as `layered`
- banners should be returned as `layered` composition contracts with exported pole/base imagery plus pattern texture layers, tint colors, ordering, masks, and transforms
- shields should be returned as `layered` composition contracts with an exported empty shield image plus pattern texture layers, tint colors, ordering, masks, and transforms
- if a modded or builtin item cannot be represented as `layered` or `model`, the backend marks it as `exportRequired` or `unsupported`

If the frontend render does not match vanilla, the fix belongs in the frontend renderer unless the backend contract explicitly lacks one of the fields above.

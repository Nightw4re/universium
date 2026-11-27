# WebCraft reference frontend

This static site discovers the WebCraft API over [REST](../api.md#rest-v1) or [WebSocket](../api.md#websocket-v1). It uses REST where available and follows discovery for WebSocket-only data and images. The Equipment view sends the viewer token in the HTTP authorization header or on the WebSocket request and keeps it in the current page only. The Items view at `/#items` browses the public catalog from the server's installed export when the module is enabled. It filters by game or mod and by name or ID, and loads icons as cards come into view.

Item detail shows [exported stack components, recipes and in-game tooltip text](../catalog.md#item-data-feeds) when the corresponding routes are discovered. Recipes follow the selected registry item and link to catalog ingredients and crafting stations. Machine recipes can include item, fluid and Mekanism chemical slots captured from a recipe viewer, with tinted sprites when the export supplies them. Tooltips and components follow the selected variant. A tooltip view selector shows captured shortcut alternatives. Catalog icons display an independent in-game bar when the export supplies one. Older packs with a catalog still show their catalog and previews.

The optional design views use the [banner and armor data modules](../catalog.md#banner-and-armor-design-data). At `/#banners`, choose a cloth color and add, recolor, reorder or remove loom layers. The view shows the required template items and downloadable previews of the cloth, the banner in its inventory pose, and a shield decorated with the same design.

At `/#armor`, each suit has a table with an armor material, trim material and pattern for each of its four slots. Drag either 3D preview in any direction, use its arrow keys, or jump to the front or back. Load a published skin by Java Edition player name, or choose a local 64 × 64 or legacy 64 × 32 PNG. Local skins stay in the browser. **Copy look link** saves both suits, the view angle and the selected public profile in the URL. Each view can be downloaded as PNG. Older exports and browsers without WebGL use the front view. Both design views work independently of the Items and Equipment views.

For local use, enable the Minecraft server's WebCraft listener and run this from the Universium root with Node.js 20 or later.

```sh
node modules/webcraft/frontend/dev.mjs
```

Open `http://127.0.0.1:4173/`. The development command enables the preview by default. It serves the static files, forwards public-facing API requests to `http://127.0.0.1:8765`, and forwards WebSocket upgrades at `/api/v1/ws`. Override those ports with `WEBCRAFT_DEV_PORT` and `WEBCRAFT_API_PORT`.

The preview service (`local.mjs`) is off by default. Set `WEBCRAFT_FRONTEND_ENABLED=true` in its environment to enable it. Set it to `false` to disable the preview even when using the development command. The disabled service exits without opening its HTTP port. Restart the preview process to apply the change. The Minecraft HTTP listener and configured modules keep serving their API routes, including images, at the API origin.

For independent hosting, use the static release ZIP or run `gradle bundleWebCraftFrontend` from the repository root to create `build/distributions/frontend-dist.zip`. It contains the HTML, CSS and browser JavaScript. Configure the same HTTPS origin to forward the REST requests and public upgrade path WebCraft needs. Register that HTTPS origin under `websocketOrigins` when enabling WebSocket for the frontend. See the [deployment notes](../server.md#wings-and-https). Serve `.mjs` files with a JavaScript MIME type. To disable a preview hosted this way, disable static file delivery in the web host configuration and keep the API forwarding rules active. The site has no frontend build step or third-party browser dependencies.

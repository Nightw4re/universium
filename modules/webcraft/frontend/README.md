# WebCraft reference frontend

This static site discovers the [WebCraft REST API](../README.md#rest-v1). The Equipment view sends the viewer token in the request header and keeps it in the current page only. The Items view at `/#items` browses the public registry catalog when the module is enabled. It filters by game or mod and by name or ID, and loads icons as cards come into view.

The optional design views use the [banner and armor data modules](../README.md#banner-and-armor-design-data). At `/#banners`, choose a cloth color and add, recolor, reorder or remove loom layers. The view shows the required template items and downloadable previews of the cloth, the banner in its inventory pose, and a shield decorated with the same design.

At `/#armor`, each suit has a table with an armor material, trim material and pattern for each of its four slots. Drag either 3D preview in any direction, use its arrow keys, or jump to the front or back. Load a published skin by Java Edition player name, or choose a local 64 × 64 or legacy 64 × 32 PNG. Local skins stay in the browser. **Copy look link** saves both suits, the view angle and the selected public profile in the URL. Each view can be downloaded as PNG. Older exports and browsers without WebGL use the front view. Both design views work independently of the Items and Equipment views.

For local use, enable the Minecraft server's WebCraft listener and run this from the Universium root with Node.js 20 or later.

```sh
node modules/webcraft/frontend/dev.mjs
```

Open `http://127.0.0.1:4173/`. The development command enables the preview by default. It serves the static files and forwards `/api/v1/` to `http://127.0.0.1:8765`. Override those ports with `WEBCRAFT_DEV_PORT` and `WEBCRAFT_API_PORT`.

The preview service (`local.mjs`) is off by default. Set `WEBCRAFT_FRONTEND_ENABLED=true` in its environment to enable it. Set it to `false` to disable the preview even when using the development command. The disabled service exits without opening its HTTP port. Restart the preview process to apply the change. The Minecraft HTTP listener and configured modules keep serving their API routes, including images, at the API origin.

For independent hosting, use the static release ZIP or run `gradle bundleWebCraftFrontend` from the repository root to create `build/distributions/frontend-dist.zip`. It contains the HTML, CSS and browser JavaScript. Configure the same HTTPS origin to forward `/api/v1/` to WebCraft as described in the [deployment notes](../README.md#wings-and-https). Serve `.mjs` files with a JavaScript MIME type. To disable a preview hosted this way, disable static file delivery in the web host configuration and keep the API forwarding rule active. The site has no frontend build step or third-party browser dependencies.

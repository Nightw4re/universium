# WebCraft

WebCraft exposes Minecraft server data over HTTP and WebSocket on the same port. WebCraft core works on the server with standard Minecraft 1.21.1 clients. Other server mods can impose their own client requirements. Install WebCraft and choose the optional add-ons you need. Each add-on is a separate NeoForge mod JAR that depends on WebCraft and registers one or more API modules.

| Mod | Purpose | Gradle project | NeoForge mod ID | Entries for `enabledModules` |
| --- | --- | --- | --- | --- |
| [WebCraft](core) | HTTP and WebSocket API, built-in server and player feeds including [advancements](api.md#advancements), [account linking](api.md#account-linking), and the [client preview exporter](exports.md#export-inventory-previews) | `:webcraft-core` | `webcraft_core` | Built-in features use their own settings |
| [Equipment](equipment) | Authenticated view of a selected player's equipment and inventory | `:webcraft-equipment` | `webcraft_equipment` | `webcraft_equipment:equipment` |
| [Item Catalog](catalog) | Exported item icons, stack details, recipes and tooltips | `:webcraft-catalog` | `webcraft_catalog` | `webcraft_catalog:items`<br>`webcraft_catalog:recipes`<br>`webcraft_catalog:tooltips` |
| [Design Data](designs) | Exported banner patterns and armor trim data for design previews | `:webcraft-designs` | `webcraft_designs` | `webcraft_designs:banners`<br>`webcraft_designs:armor` |
| [FTB Quests](api.md#ftb-quests) | Internal feed for visible quests and a selected player's FTB team progress. Requires FTB Quests on the server. | `:webcraft-quests` | `webcraft_quests` | `webcraft_quests:quests` |

The [reference frontend](frontend/README.md) is an independent web client.

## Documentation

### [Server setup and operation](server.md)

- <a name="install-on-a-dedicated-server"></a>[Install on a dedicated server](server.md#install-on-a-dedicated-server)
  - <a name="reload-webcraft-on-a-running-server"></a>[Reload WebCraft on a running server](server.md#reload-webcraft-on-a-running-server)
- <a name="enable-http-in-singleplayer"></a>[Enable HTTP in singleplayer](server.md#enable-http-in-singleplayer)
- <a name="upgrade-a-previous-preview-installation"></a>[Upgrade a previous preview installation](server.md#upgrade-a-previous-preview-installation)
- <a name="wings-and-https"></a>[Wings and HTTPS](server.md#wings-and-https)
- <a name="api-transports"></a>[API transports](server.md#api-transports)

### [WebCraft API contracts](api.md)

- <a name="account-linking"></a>[Account linking](api.md#account-linking)
  - <a name="capycraft-backend-integration"></a>[CapyCraft backend integration](api.md#capycraft-backend-integration)
- <a name="websocket-v1"></a>[WebSocket v1](api.md#websocket-v1)
  - <a name="delta-updates"></a>[Delta updates](api.md#delta-updates)
- <a name="rest-v1"></a>[REST v1](api.md#rest-v1)
  - <a name="server-information"></a>[Server information](api.md#server-information)
  - <a name="server-status-and-polling"></a>[Server status and polling](api.md#server-status-and-polling)
  - <a name="player-history"></a>[Player history](api.md#player-history)
  - <a name="online-players-and-player-roster"></a>[Online players and player roster](api.md#online-players-and-player-roster)
  - <a name="advancements"></a>[Advancements](api.md#advancements)
  - <a name="ftb-quests"></a>[FTB Quests](api.md#ftb-quests)
  - <a name="module-discovery-and-routes"></a>[Module discovery and routes](api.md#module-discovery-and-routes)
- <a name="add-on-api"></a>[Add-on API](api.md#add-on-api)

### [Preview and Ponder exports](exports.md)

- <a name="export-inventory-previews"></a>[Export inventory previews](exports.md#export-inventory-previews)
  - <a name="static-preview-atlases"></a>[Static preview atlases](exports.md#static-preview-atlases)
  - <a name="run-a-curseforge-export-from-the-command-line"></a>[Run a CurseForge export from the command line](exports.md#run-a-curseforge-export-from-the-command-line)
- <a name="export-ponder-scenes"></a>[Export Ponder scenes](exports.md#export-ponder-scenes)
  - <a name="export-legacy-ponder-frames"></a>[Export legacy Ponder frames](exports.md#export-legacy-ponder-frames)
  - <a name="export-ponder-definitions"></a>[Export Ponder definitions](exports.md#export-ponder-definitions)
- <a name="ponder-scene-api"></a>For integrations that used the former Ponder server API, [import the client-exported ZIP](exports.md#export-ponder-scenes).

### [Catalog and design data](catalog.md)

- <a name="item-data-feeds"></a>[Item data feeds](catalog.md#item-data-feeds)
  - <a name="bundled-catalog-documents"></a>[Bundled catalog documents](catalog.md#bundled-catalog-documents)
  - <a name="compact-recipe-documents"></a>[Compact recipe documents](catalog.md#compact-recipe-documents)
- <a name="banner-and-armor-design-data"></a>[Banner and armor design data](catalog.md#banner-and-armor-design-data)

The [repository development guide](../../AI.md) covers shared build and verification workflows. The [WebCraft requirements](AGENTS.md) cover API boundaries and export retention.

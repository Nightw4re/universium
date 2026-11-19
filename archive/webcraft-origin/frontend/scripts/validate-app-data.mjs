import { APP_CONTRACT_REQUESTS } from "../app-contract.config.mjs";

const isRecord = (value) => typeof value === "object" && value !== null;
const isString = (value) => typeof value === "string";
const isBoolean = (value) => typeof value === "boolean";
const isRoute = (value) => value === "home" || value === "players";

const isPlayerSummary = (value) =>
  isRecord(value) && isString(value.name) && isBoolean(value.online) && isString(value.meta);

const isCombatStat = (value) =>
  isRecord(value) && isString(value.label) && isString(value.value);

const isInventoryCell = (value) =>
  isRecord(value) &&
  isString(value.icon) &&
  isString(value.label) &&
  (value.kind === undefined || value.kind === "block" || value.kind === "item");

const isServerPageData = (value) => {
  if (!isRecord(value)) return false;
  if (!Array.isArray(value.topPlayers) || !value.topPlayers.every(isPlayerSummary)) return false;
  if (!Array.isArray(value.serverInfo)) return false;
  if (!value.serverInfo.every((item) => isRecord(item) && isString(item.label) && isString(item.value))) return false;
  if (!isRecord(value.detailHero) || !isString(value.detailHero.name) || !isString(value.detailHero.playedTime)) return false;
  if (!Array.isArray(value.detailHero.combat) || !value.detailHero.combat.every(isCombatStat)) return false;
  if (!isRecord(value.inventory)) return false;
  if (!Array.isArray(value.inventory.rows) || !value.inventory.rows.every((row) => Array.isArray(row) && row.every(isInventoryCell))) return false;
  if (!Array.isArray(value.inventory.hotbar) || !value.inventory.hotbar.every(isInventoryCell)) return false;
  if (!isRecord(value.playerSearch)) return false;
  if (!isString(value.playerSearch.query) || !isString(value.playerSearch.filter) || !isString(value.playerSearch.sort)) return false;
  if (!Array.isArray(value.playerSearch.players) || !value.playerSearch.players.every(isPlayerSummary)) return false;
  if (!isRecord(value.tooltip)) return false;
  if (!isString(value.tooltip.title) || !isString(value.tooltip.description) || !isString(value.tooltip.footer)) return false;
  if (!Array.isArray(value.tooltip.attributes) || !value.tooltip.attributes.every(isCombatStat)) return false;
  if (!Array.isArray(value.tooltip.enchantments) || !value.tooltip.enchantments.every(isString)) return false;
  if (!Array.isArray(value.tooltip.hidden) || !value.tooltip.hidden.every(isCombatStat)) return false;
  return true;
};

const isAppData = (value) => {
  if (!isRecord(value)) return false;
  if (!isRecord(value.navigation)) return false;
  if (!isRoute(value.navigation.activeId)) return false;
  if (!isRecord(value.navigation.brand)) return false;
  if (!isString(value.navigation.brand.mark) || !isString(value.navigation.brand.title)) return false;
  if (value.navigation.brand.subtitle !== undefined && !isString(value.navigation.brand.subtitle)) return false;
  if (value.navigation.brand.href !== undefined && !isString(value.navigation.brand.href)) return false;
  if (!Array.isArray(value.navigation.items)) return false;
  if (!Array.isArray(value.feeds)) return false;
  if (!isRecord(value.pages)) return false;
  if (!isServerPageData(value.pages.home) || !isServerPageData(value.pages.players)) return false;
  return true;
};

const validateRequest = async ({ name, method, url }) => {
  if (method !== "GET") {
    console.error(`[FAIL] ${name} -> unsupported method ${method}`);
    return false;
  }

  try {
    const response = await fetch(url);
    if (!response.ok) {
      console.error(`[FAIL] ${name} -> unavailable (${response.status} ${response.statusText}) at ${url}`);
      return false;
    }

    const payload = await response.json();
    if (!isAppData(payload)) {
      console.error(`[FAIL] ${name} -> available, but payload shape mismatch at ${url}`);
      return false;
    }

    console.log(`[OK] ${name} -> available, format ok (${url})`);
    return true;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[FAIL] ${name} -> unavailable (${message}) at ${url}`);
    return false;
  }
};

const run = async () => {
  let exitCode = 0;
  const ok = await validateRequest({ name: "appData", ...APP_CONTRACT_REQUESTS.appData });
  if (!ok) exitCode = 1;

  process.exitCode = exitCode;
};

await run();

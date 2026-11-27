import { renderPreview } from "./preview.mjs";
import { showItems } from "./catalog.mjs";
import { showDesign } from "./designs.mjs";
import { getJson } from "./api.mjs";

const labels = {
  head: "Head",
  chest: "Chest",
  legs: "Legs",
  feet: "Feet",
  mainhand: "Main hand",
  offhand: "Off hand",
};
const equipmentModule = "webcraft_equipment:equipment";
const equipmentRoute = "/api/v1/modules/webcraft_equipment/equipment/loadout";
const form = document.querySelector("#access");
const button = form.querySelector("button");
const input = document.querySelector("#token");
const title = document.querySelector("#character");
const status = document.querySelector("#status");
const slots = document.querySelector("#slots");

function message(text, error = false) {
  status.textContent = text;
  status.toggleAttribute("data-error", error);
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function placeholder(frame, text, symbol) {
  const icon = element("span", "preview-symbol", symbol);
  icon.setAttribute("aria-hidden", "true");
  frame.replaceChildren(icon, element("span", "", text));
}

function showEquipment(data) {
  title.textContent = data?.player ? data.player + "’s loadout" : "Character loadout";
  slots.replaceChildren(...Object.entries(labels).map(([id, label]) => {
    const item = data?.slots.find(entry => entry.slot === id)?.item;
    const card = element("li", "slot");
    card.dataset.slot = id;
    const frame = element("div", "preview");
    card.append(element("h3", "", label), frame);
    if (!item) {
      placeholder(frame, data ? "Empty slot" : "—", "◇");
      return card;
    }
    const name = element("p", "item-name", item.name);
    if (item.count > 1) name.append(element("span", "", " ×" + item.count));
    card.append(name, element("p", "item-id", item.id));

    placeholder(frame, "Preview unavailable", "▧");
    renderPreview(item.preview).then(preview => {
      if (preview) frame.replaceChildren(preview);
    }).catch(() => {});
    return card;
  }));
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  const token = input.value.trim();
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(token)) {
    message("Enter a valid viewer token.", true);
    return;
  }
  button.disabled = true;
  showEquipment();
  message("Loading equipment…");
  try {
    const discovery = await getJson("/api/v1/modules");
    const module = discovery.modules.find(entry => entry.id === equipmentModule);
    if (module?.routes?.loadout !== equipmentRoute) {
      throw new Error("equipment_disabled");
    }
    const data = await getJson(module.routes.loadout, token);
    showEquipment(data);
    message("Equipment loaded.");
  } catch (error) {
    const messages = {
      unauthorized: "This token was rejected.",
      viewer_access_disabled: "Viewer access is disabled on this server.",
      player_offline: "Your character is offline. Join the server and try again.",
      inventory_unavailable: "Your saved equipment could not be loaded.",
      server_busy: "The Minecraft server is busy. Try again shortly.",
      equipment_disabled: "The equipment module is disabled on this server.",
    };
    message(messages[error.message] ?? "The WebCraft API could not be reached.", true);
  } finally {
    button.disabled = false;
  }
});

showEquipment();

function showActiveView() {
  const detail = location.hash.startsWith("#items/") ? location.hash.slice(7) : null;
  const catalog = location.hash === "#items" || detail !== null;
  const hash = location.hash.slice(1).split("?")[0];
  const selected = catalog ? "catalog" : ["banners", "armor"].includes(hash) ? hash : "equipment";
  for (const view of ["equipment", "catalog", "banners", "armor"]) {
    const active = view === selected;
    document.querySelector("#" + view + "-view").hidden = !active;
    const link = document.querySelector("#" + view + "-link");
    if (active) {
      link.setAttribute("aria-current", "page");
      document.title = link.textContent + " · WebCraft";
    } else {
      link.removeAttribute("aria-current");
    }
  }
  if (catalog) showItems(getJson, detail);
  if (selected === "banners" || selected === "armor") showDesign(selected, getJson);
}

window.addEventListener("hashchange", showActiveView);
showActiveView();

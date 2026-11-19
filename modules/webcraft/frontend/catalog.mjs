import { showGlint } from "./glint.mjs";
import { motionPicture } from "./motion-picture.mjs";

const moduleId = "webcraft_catalog:items";
const routes = "/api/v1/modules/webcraft_catalog/items";
const pageSize = 96;
const modNames = {
  minecraft: "Minecraft",
  modern_industrialization: "Modern Industrialization",
  mekanism: "Mekanism",
  easy_villagers: "Easy Villagers",
  extended_industrialization: "Extended Industrialization",
  ae2: "Applied Energistics 2",
  guideme: "GuideME",
};
const search = document.querySelector("#item-search");
const mods = document.querySelector("#item-mod");
const status = document.querySelector("#catalog-status");
const grid = document.querySelector("#item-grid");
const more = document.querySelector("#more-items");
const index = document.querySelector("#catalog-index");
const detail = document.querySelector("#item-detail");
const detailBody = document.querySelector("#detail-body");
const detailStatus = document.querySelector("#detail-status");
const detailPicture = document.querySelector("#detail-picture");
const detailName = document.querySelector("#detail-name");
const detailId = document.querySelector("#detail-id");
const controls = document.querySelector("#variant-controls");
const number = new Intl.NumberFormat();
let loading = false;
let items;
let matched = [];
let visible = 0;
let effects;
let detailRequest = 0;

function itemCard(item) {
  const card = document.createElement("li");
  card.dataset.itemId = item.id;
  const link = document.createElement("a");
  link.href = "#items/" + encodeURIComponent(item.id);
  const preview = document.createElement("div");
  preview.className = "item-picture";
  const name = document.createElement("p");
  name.className = "item-name";
  name.textContent = item.name;
  if (item.defaultColor) {
    const label = document.createElement("span");
    label.textContent = " (default)";
    name.append(label);
  }
  const id = document.createElement("p");
  id.className = "item-id";
  id.textContent = item.id;
  showImage(preview, item, item.id, true);
  link.append(preview, name, id);
  card.append(link);
  return card;
}

function showImage(frame, item, catalogId, lazy = false, animate = true) {
  if (!item.hasPreview) {
    frame.textContent = "No preview";
    return;
  }
  const image = document.createElement("img");
  image.alt = "";
  image.width = 64;
  image.height = 64;
  image.loading = lazy ? "lazy" : "eager";
  image.decoding = "async";
  const parameters = new URLSearchParams({ id: catalogId });
  if (item.variantId) parameters.set("variant", item.variantId);
  image.src = routes + "/icon?" + parameters;
  const preview = animate ? motionPicture(image, image.src + "&animated=1") : image;
  image.addEventListener("error", () => {
    if (preview.parentNode === frame) frame.textContent = "No preview";
  }, { once: true });
  frame.replaceChildren(preview);
  return image;
}

function showBatch() {
  const next = Math.min(visible + pageSize, matched.length);
  grid.append(...matched.slice(visible, next).map(itemCard));
  visible = next;
  more.hidden = visible >= matched.length;
  status.textContent = matched.length
    ? `Showing ${number.format(visible)} of ${number.format(matched.length)} items`
    : "No items match this search.";
}

function filter() {
  const term = search.value.trim().toLowerCase();
  const words = term.split(/\s+/).filter(Boolean);
  const namespace = mods.value;
  matched = items.filter(item => {
    if (namespace && !item.id.startsWith(namespace + ":")) return false;
    const searchText = `${item.id} ${item.name.toLowerCase()}${item.defaultColor ? " default uncolored undyed" : ""}`;
    return words.every(word => searchText.includes(word));
  });
  if (term) {
    const priority = item => item.id === term ? 2 : Number(Boolean(item.defaultColor));
    matched.sort((first, second) => priority(second) - priority(first));
  }
  visible = 0;
  grid.replaceChildren();
  showBatch();
}

async function loadCatalog(getJson) {
  if (items || loading) return;
  loading = true;
  status.textContent = "Loading items…";
  try {
    const discovery = await getJson("/api/v1/modules");
    const module = discovery.modules.find(entry => entry.id === moduleId);
    if (module?.routes?.items !== routes + "/items" || module.routes.icon !== routes + "/icon") {
      status.textContent = "The item catalog module is disabled on this server.";
      return;
    }
    const data = await getJson(module.routes.items);
    const namespaces = [...new Set(data.items.map(item => item.id.split(":", 1)[0]))].sort();
    for (const namespace of namespaces) {
      mods.add(new Option(modNames[namespace] ?? namespace.replaceAll("_", " "), namespace));
    }
    items = data.items;
    search.disabled = false;
    mods.disabled = false;
    search.addEventListener("input", filter);
    mods.addEventListener("change", filter);
    more.addEventListener("click", showBatch);
    filter();
  } catch {
    status.textContent = "The item catalog could not be loaded.";
  } finally {
    loading = false;
  }
}

function variantSelect(label, values) {
  const field = document.createElement("label");
  field.textContent = label;
  const select = document.createElement("select");
  select.setAttribute("aria-label", label);
  for (const [value, caption] of values) select.add(new Option(caption, value));
  field.append(select);
  return { field, select };
}

function renderBreeder(item, slots) {
  controls.replaceChildren();
  const download = document.createElement("a");
  download.className = "catalog-download";
  download.textContent = "Download PNG";
  download.download = "easy-villagers-breeder.png";

  const selections = slots.map((variants, index) => {
    const biomes = new Map();
    for (const variant of variants) {
      const biome = variant.options.find(option => option.axis === "biome");
      const profession = variant.options.find(option => option.axis === "profession");
      if (!biome || !profession) continue;
      if (!biomes.has(biome.value)) biomes.set(biome.value, { label: biome.label, professions: new Map() });
      biomes.get(biome.value).professions.set(profession.value, { label: profession.label, id: variant.variantId });
    }

    const group = document.createElement("fieldset");
    group.className = "catalog-slot";
    const heading = document.createElement("legend");
    heading.textContent = "Villager " + (index + 1);
    const biomeOptions = [["", "Empty slot"], ...Array.from(biomes, ([id, biome]) => [id, biome.label])];
    const biome = variantSelect("Biome type", biomeOptions);
    const profession = variantSelect("Profession", []);
    profession.select.disabled = true;
    group.append(heading, biome.field, profession.field);
    controls.append(group);

    biome.select.addEventListener("change", () => {
      const previous = profession.select.value;
      const choices = biomes.get(biome.select.value)?.professions;
      profession.select.replaceChildren();
      for (const [value, choice] of choices ?? []) profession.select.add(new Option(choice.label, value));
      profession.select.disabled = !choices?.size;
      if (choices?.has(previous)) profession.select.value = previous;
      else if (choices?.has("minecraft:none")) profession.select.value = "minecraft:none";
      updateImage();
    });
    profession.select.addEventListener("change", updateImage);
    return () => biomes.get(biome.select.value)?.professions.get(profession.select.value)?.id;
  });
  controls.append(download);

  function updateImage() {
    const variantId = selections.map(selection => selection()).filter(Boolean).join("+");
    const image = showImage(detailPicture, { ...item, variantId }, item.id, false, false);
    download.hidden = !image;
    if (image) {
      download.href = image.src;
      image.addEventListener("error", () => {
        if (download.href === image.src) download.hidden = true;
      }, { once: true });
    }
  }

  detailName.textContent = item.name;
  detailId.textContent = item.id;
  updateImage();
}

function renderDetail(item, getJson) {
  if (item.id === "easy_villagers:breeder") {
    const slots = [1, 2].map(slot => item.variants.filter(variant => variant.variantId.startsWith("villager_" + slot + "/")));
    if (slots.every(variants => variants.length > 0)) {
      renderBreeder(item, slots);
      return;
    }
  }
  const axes = new Map();
  for (const variant of item.variants) {
    for (const { axis, value, label } of variant.options) {
      if (!axes.has(axis)) axes.set(axis, new Map());
      axes.get(axis).set(value, label);
    }
  }
  const names = { biome: "Biome type", profession: "Profession", color: "Color", occupants: "Occupants" };
  const selectors = new Map();
  controls.replaceChildren();
  for (const [axis, values] of axes) {
    const label = names[axis] ?? axis.replaceAll("_", " ");
    const { field, select } = variantSelect(label, values);
    field.dataset.axis = axis;
    controls.append(field);
    selectors.set(axis, select);
  }

  let selectedItem = item;
  const glint = document.createElement("input");
  glint.type = "checkbox";
  if (item.canGlint) {
    const field = document.createElement("label");
    field.className = "glint-toggle";
    field.append(glint, document.createTextNode("Enchanted glint"));
    controls.append(field);
    glint.addEventListener("change", () => showSelection(selectedItem));
  }

  function showSelection(selected) {
    selectedItem = selected;
    detailName.textContent = selected.name;
    detailId.textContent = selected.id;
    for (const { axis, value } of selected.options ?? []) selectors.get(axis).value = value;
    const image = showImage(detailPicture, selected, item.id, false, !glint.checked);
    if (image && glint.checked) {
      effects ??= getJson(routes + "/effects").catch(error => { effects = undefined; throw error; });
      effects.then(recipe => showGlint(detailPicture, image, recipe.glint)).catch(() => {
        if (image.parentNode === detailPicture) glint.checked = false;
      });
    }
  }

  for (const [axis, select] of selectors) {
    select.addEventListener("change", () => {
      const candidates = item.variants.filter(variant => variant.options.some(option => option.axis === axis && option.value === select.value));
      const variant = candidates.find(candidate => candidate.options.every(option => selectors.get(option.axis).value === option.value));
      showSelection(variant ?? candidates[0]);
    });
  }
  const initial = item.variants.find(variant => variant.variantId === "minecraft:plains/minecraft:none")
    ?? item.variants.find(variant => variant.id === item.id) ?? item.variants[0] ?? item;
  showSelection(initial);
}

async function loadDetail(getJson, encodedId) {
  const request = ++detailRequest;
  const requestHash = location.hash;
  detailBody.hidden = true;
  detailStatus.textContent = "Loading item…";
  try {
    const id = decodeURIComponent(encodedId);
    const item = await getJson(routes + "/detail?id=" + encodeURIComponent(id));
    if (request !== detailRequest || location.hash !== requestHash) return;
    renderDetail(item, getJson);
    detailStatus.textContent = "";
    detailBody.hidden = false;
    detailName.focus({ preventScroll: true });
  } catch {
    if (request === detailRequest && location.hash === requestHash) detailStatus.textContent = "This item could not be loaded.";
  }
}

export function showItems(getJson, encodedId) {
  index.hidden = encodedId !== null;
  detail.hidden = encodedId === null;
  if (encodedId === null) {
    loadCatalog(getJson);
  } else {
    window.scrollTo(0, 0);
    loadDetail(getJson, encodedId);
  }
}

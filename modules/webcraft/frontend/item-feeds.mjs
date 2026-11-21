import { apiImage } from "./api.mjs";

const prefix = "/api/v1/modules/webcraft_catalog/";
const feedRoutes = {
  stack: ["items", "stack"],
  recipes: ["recipes", "recipes"],
  tooltip: ["tooltips", "tooltip"],
};

export function itemFeedRequests(modules, catalogId, itemId, variantId) {
  const stackQuery = new URLSearchParams({ id: catalogId });
  if (variantId) stackQuery.set("variant", variantId);
  const resultQuery = new URLSearchParams({ id: itemId });
  return Object.fromEntries(Object.entries(feedRoutes).flatMap(([feed, [module, route]]) => {
    const path = prefix + module + "/" + route;
    const published = modules.find(entry => entry.id === "webcraft_catalog:" + module)?.routes?.[route];
    return published === path ? [[feed, path + "?" + (feed === "recipes" ? resultQuery : stackQuery)]] : [];
  }));
}

function element(tag, className, label) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (label !== undefined) node.textContent = label;
  return node;
}

function itemLabel(id, items) {
  return items.get(id)?.name ?? id.split(":")[1].split(/[\/_-]+/).map(word =>
    word.charAt(0).toUpperCase() + word.slice(1)).join(" ");
}

function itemLink(id, items, icon = false, showLabel = false) {
  const label = itemLabel(id, items);
  const listed = items.has(id);
  const className = icon ? (showLabel ? "recipe-option" : "recipe-icon") : "";
  const link = element(listed ? "a" : "span", className);
  if (listed) link.href = "#items/" + encodeURIComponent(id);
  if (!icon) {
    link.textContent = label;
    return link;
  }
  link.title = label;
  const image = element("img");
  image.loading = "lazy";
  image.alt = showLabel ? "" : label;
  image.width = showLabel ? 20 : 32;
  image.height = image.width;
  image.addEventListener("error", () => {
    if (showLabel) image.remove();
    else link.textContent = label;
  }, { once: true });
  apiImage(image, prefix + "items/icon?" + new URLSearchParams({ id, animated: "1" }));
  link.append(image);
  if (showLabel) link.append(label);
  return link;
}

function renderTooltip(data) {
  if (!data.alternatives?.length) return renderTooltipText(data.locale, data);
  const result = document.createDocumentFragment();
  const mode = element("label", "item-tooltip-mode", "Tooltip view ");
  const chooser = element("select");
  const snapshots = [data, ...data.alternatives];
  for (const [index, snapshot] of snapshots.entries()) {
    const option = element("option", "", index === 0 ? "Default" : snapshot.label);
    option.value = String(index);
    chooser.append(option);
  }
  const body = element("div");
  body.append(renderTooltipText(data.locale, data));
  chooser.addEventListener("change", () => {
    body.replaceChildren(renderTooltipText(data.locale, snapshots[Number(chooser.value)]));
  });
  mode.append(chooser);
  result.append(mode, body);
  return result;
}

function renderTooltipText(locale, data) {
  const result = document.createDocumentFragment();
  const tooltip = element("div", "item-tooltip");
  tooltip.lang = locale.replaceAll("_", "-");
  for (const line of data.lines) {
    const row = element("p");
    for (const style of line) {
      const span = element("span", "", style.text);
      if (Number.isInteger(style.color) && style.color >= 0 && style.color <= 0xffffff) {
        span.style.color = "#" + style.color.toString(16).padStart(6, "0");
      }
      if (style.bold) span.style.fontWeight = "700";
      if (style.italic) span.style.fontStyle = "italic";
      const decorations = [];
      if (style.underlined) decorations.push("underline");
      if (style.strikethrough) decorations.push("line-through");
      if (decorations.length) span.style.textDecoration = decorations.join(" ");
      row.append(span);
    }
    tooltip.append(row);
  }
  if (data.lines.length) result.append(tooltip);
  else result.append(element("p", "item-feed-note", "This stack has no tooltip text."));
  const note = data.hasGraphicElements ? locale + " · In-game graphics omitted" : locale;
  result.append(element("p", "item-feed-note", note));
  return result;
}

function renderStack(data) {
  const details = element("details");
  details.append(element("summary", "", "Stack components"));
  const { count, components, nbt, omittedComponents } = data;
  details.append(element("pre", "item-stack-data", JSON.stringify({ count, components, nbt, omittedComponents }, null, 2)));
  return details;
}

function ingredientCaption(input, items, slot) {
  const row = element("li");
  if (slot) row.append(element("strong", "", slot + " · "));
  const alternatives = input.exampleItems;
  if (alternatives.length) {
    alternatives.forEach((id, index) => {
      if (index) row.append(document.createTextNode(" / "));
      row.append(itemLink(id, items, true, true));
    });
  } else {
    row.append(document.createTextNode("No resolved items"));
  }
  if (input.tags?.length) row.append(element("span", "item-feed-note", " · " + input.tags.map(tag => "#" + tag).join(", ")));
  if (input.examplesTruncated) row.append(element("span", "item-feed-note", " · More alternatives in game"));
  if (input.match === "custom") row.append(element("span", "item-feed-note", " · Requires a custom match in game"));
  return row;
}

function recipeCard(recipe, items) {
  const card = element("li", "item-recipe");
  if (recipe.status === "unsupported") {
    card.append(element("h3", "", recipe.id), element("p", "item-feed-note", "Instructions unavailable for " + recipe.type));
    return card;
  }
  const { inputs, layout, result } = recipe;
  const station = element("h3");
  station.append(itemLink(recipe.station, items));
  if (recipe.fitsPlayerGrid) station.append(document.createTextNode(" or inventory crafting"));
  card.append(station, element("p", "item-feed-note", recipe.id));
  if (layout.kind === "grid") {
    const grid = element("div", "recipe-grid");
    grid.style.gridTemplateColumns = `repeat(${layout.width}, 2.7rem)`;
    grid.setAttribute("aria-label", `${layout.width} by ${layout.height} crafting pattern`);
    for (const input of inputs) {
      const cell = element("div", "recipe-cell");
      const id = input?.exampleItems[0];
      if (id) cell.append(itemLink(id, items, true));
      else if (input) cell.textContent = "?";
      grid.append(cell);
    }
    card.append(grid);
  }
  const ingredients = element("ul", "recipe-ingredients");
  const seen = new Set();
  inputs.forEach((input, index) => {
    if (!input) return;
    if (layout.kind === "grid") {
      const key = JSON.stringify(input);
      if (seen.has(key)) return;
      seen.add(key);
    }
    ingredients.append(ingredientCaption(input, items, layout.slots?.[index]));
  });
  card.append(ingredients);
  const output = element("p");
  output.append(document.createTextNode(`Makes ${result.count} × `), itemLink(result.itemId, items));
  card.append(output);
  if (recipe.cookingTicks !== undefined) card.append(element("p", "item-feed-note", `${recipe.cookingTicks / 20} s at 20 ticks per second`));
  return card;
}

function machineIngredientText(ingredient, items) {
  if (ingredient.kind === "item") return ingredient.count + " × " + itemLabel(ingredient.id, items);
  return machineMaterialAmount(ingredient) + " " + (ingredient.name ?? itemLabel(ingredient.id, items));
}

function machineMaterialAmount(ingredient) {
  return ingredient.kind === "fluid" ? ingredient.amountMb + " mB" : ingredient.amount + " mB";
}

function machineRecipeLayout(layout, items) {
  const view = element("div", "machine-recipe-layout");
  view.setAttribute("aria-hidden", "true");
  view.style.width = Math.min(750, layout.width * 2) + "px";
  view.style.aspectRatio = `${layout.width} / ${layout.height}`;
  for (const { bounds, role, alternatives } of layout.slots) {
    if (!bounds) continue;
    const ingredient = alternatives[0];
    const slot = element("div", "machine-recipe-slot " + role);
    slot.style.left = `${100 * bounds.x / layout.width}%`;
    slot.style.top = `${100 * bounds.y / layout.height}%`;
    slot.style.width = `${100 * bounds.width / layout.width}%`;
    slot.style.height = `${100 * bounds.height / layout.height}%`;
    if (ingredient) {
      slot.title = machineIngredientText(ingredient, items);
      if (ingredient.kind !== "item") {
        slot.classList.add("material");
        if (ingredient.texture) {
          const icon = element("img", "machine-material-texture");
          icon.alt = "";
          icon.loading = "lazy";
          icon.addEventListener("error", () => icon.remove(), { once: true });
          apiImage(icon, prefix + "recipes/texture?" + new URLSearchParams({ hash: ingredient.texture }));
          slot.append(icon);
        }
        slot.append(element("span", "machine-material-amount", machineMaterialAmount(ingredient)));
      } else if (items.get(ingredient.id)?.hasPreview) {
        const icon = element("img");
        icon.alt = "";
        icon.loading = "lazy";
        icon.addEventListener("error", () => { slot.textContent = itemLabel(ingredient.id, items); }, { once: true });
        apiImage(icon, prefix + "items/icon?" + new URLSearchParams({ id: ingredient.id }));
        slot.append(icon);
      } else {
        slot.textContent = itemLabel(ingredient.id, items);
      }
    }
    view.append(slot);
  }
  return view;
}

function machineRecipeCard(recipe, items) {
  const card = element("li", "item-recipe");
  card.append(element("h3", "", recipe.title), element("p", "item-feed-note", recipe.id));
  if (recipe.stations.length) {
    const stations = element("p", "item-feed-note");
    recipe.stations.forEach((id, index) => {
      if (index) stations.append(document.createTextNode(" / "));
      stations.append(itemLink(id, items));
    });
    card.append(stations);
  }
  if (recipe.layout.slots.some(slot => slot.bounds)) card.append(machineRecipeLayout(recipe.layout, items));
  for (const [role, label] of [["input", "Inputs"], ["output", "Outputs"], ["catalyst", "Catalysts"]]) {
    const slots = recipe.layout.slots.filter(slot => slot.role === role && (slot.alternatives.length || slot.otherIngredients));
    if (!slots.length) continue;
    const ingredients = element("ul", "recipe-ingredients");
    for (const slot of slots) {
      const entry = element("li");
      if (slot.name) entry.append(element("strong", "", slot.name + " · "));
      slot.alternatives.forEach((ingredient, index) => {
        if (index) entry.append(document.createTextNode(" / "));
        if (ingredient.kind !== "item") {
          entry.append(document.createTextNode(machineIngredientText(ingredient, items)));
        } else {
          entry.append(document.createTextNode(`${ingredient.count} × `), itemLink(ingredient.id, items));
        }
      });
      if (slot.otherIngredients) entry.append(element("span", "item-feed-note", " · Other ingredients shown in game"));
      if (slot.alternativesTruncated) entry.append(element("span", "item-feed-note", " · More alternatives in game"));
      ingredients.append(entry);
    }
    card.append(element("h4", "", label), ingredients);
  }
  return card;
}

function renderRecipes(data, items) {
  const machineRecipes = data.machineRecipes?.recipes ?? [];
  const machineIds = new Set(machineRecipes.map(recipe => recipe.id));
  const recipes = data.recipes.filter(recipe => recipe.status !== "unsupported" || !machineIds.has(recipe.id));
  const result = document.createDocumentFragment();
  if (data.truncated) result.append(element("p", "item-feed-note", "This item’s world recipe export exceeds the size limit."));
  if (data.machineRecipes?.truncated) result.append(element("p", "item-feed-note", "This item’s recipe viewer export exceeds the size limit."));
  if (!recipes.length && !machineRecipes.length && !result.childNodes.length) {
    return element("p", "item-feed-note", "No recipes in the export list this item as a result.");
  }
  const list = element("ul", "item-recipes");
  for (const recipe of recipes) list.append(recipeCard(recipe, items));
  for (const recipe of machineRecipes) list.append(machineRecipeCard(recipe, items));
  if (list.childNodes.length) result.append(list);
  return result;
}

export function createItemFeeds(getJson, modules, feeds, items, isCurrent) {
  let selection = 0;
  let previous;
  const fetches = new Map();
  function load(path) {
    if (!fetches.has(path)) {
      fetches.set(path, getJson(path).catch(error => { fetches.delete(path); throw error; }));
    }
    return fetches.get(path);
  }
  const unavailable = {
    stack: "Stack components are unavailable for this selection.",
    tooltip: "No exported tooltip is available for this selection.",
    recipes: "Recipe data is unavailable for this item.",
  };
  return async (catalogId, itemId, variantId) => {
    const key = JSON.stringify([catalogId, itemId, variantId]);
    if (key === previous) return;
    previous = key;
    const request = ++selection;
    const current = () => request === selection && isCurrent();
    for (const section of Object.values(feeds)) {
      section.hidden = true;
      section.lastElementChild.replaceChildren();
    }
    const paths = itemFeedRequests(modules, catalogId, itemId, variantId);
    const renderers = { stack: renderStack, recipes: renderRecipes, tooltip: renderTooltip };
    await Promise.all(Object.entries(paths).map(async ([feed, path]) => {
      const section = feeds[feed];
      const body = section.lastElementChild;
      section.hidden = false;
      body.textContent = "Loading…";
      try {
        const data = await load(path);
        if (current()) body.replaceChildren(renderers[feed](data, items));
      } catch {
        if (current()) body.textContent = unavailable[feed];
      }
    }));
  };
}

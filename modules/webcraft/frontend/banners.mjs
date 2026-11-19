import { action, downloadLink, drawTexture, element, selectField, surface, updateDownload } from "./design-preview.mjs";

export function bannerEditor(root, data, texture, report) {
  const patterns = new Map(data.patterns.map(pattern => [pattern.id, pattern]));
  const colors = new Map(data.colors.map(color => [color.id, color]));
  const layers = [
    { pattern: "minecraft:stripe_center", color: "light_blue" },
    { pattern: "minecraft:border", color: "white" },
  ].filter(layer => patterns.has(layer.pattern) && colors.has(layer.color));
  const base = selectField("Base color", data.colors, "blue");
  const canvas = surface(data.face[2] * 8, data.face[3] * 8, "Banner cloth preview");
  const download = downloadLink("webcraft-banner.png");
  download.setAttribute("aria-label", "Download cloth PNG");
  const preview = element("div", "", "design-preview banner-preview");
  const cloth = element("div", "", "banner-cloth");
  cloth.append(canvas, download);
  const slotPreviews = bannerSlots(data.slotPreviews, texture);
  preview.append(cloth, slotPreviews.view);

  const controls = element("div", "", "design-panel");
  const layersHeading = element("div", "", "design-heading");
  const layerCount = element("span", "", "design-hint");
  const layerList = element("ol", "", "banner-layers");
  const add = action("Add layer");
  const steps = element("ol", "", "banner-steps");
  const baseStep = element("p", "", "design-hint");
  layersHeading.append(element("h2", "Layers"), layerCount);
  controls.append(base.field, layersHeading, layerList, add, element("h2", "Loom steps", "loom-heading"), baseStep, steps);
  const layout = element("div", "", "banner-editor");
  layout.append(preview, controls);
  root.replaceChildren(layout);

  let revision = 0;
  async function render() {
    const request = ++revision;
    const baseColor = colors.get(base.control.value);
    const selected = layers.map(layer => ({ pattern: patterns.get(layer.pattern), color: colors.get(layer.color) }));
    baseStep.textContent = "Start with a " + baseColor.name.toLowerCase() + " banner.";
    steps.replaceChildren(...selected.map(({ pattern, color }) => {
      const step = element("li", pattern.name + " · " + color.name + " dye");
      if (pattern.templates.length > 0) {
        const templates = pattern.templates.map(template => template.id);
        step.append(element("code", templates.join(" / ")));
      }
      return step;
    }));
    download.hidden = true;
    slotPreviews.render(baseColor, selected);
    try {
      const [baseImage, clothImage, ...masks] = await Promise.all(
        [data.base, data.cloth, ...selected.map(layer => layer.pattern.texture)].map(texture));
      if (request !== revision) return;
      const ctx = canvas.getContext("2d");
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const crop = image => data.face.map((value, index) => value * (index % 2 ? image.height : image.width) / 64);
      drawTexture(ctx, baseImage, undefined, crop(baseImage));
      drawTexture(ctx, clothImage, baseColor.rgb, crop(clothImage));
      masks.forEach((mask, index) => drawTexture(ctx, mask, selected[index].color.rgb, crop(mask)));
      updateDownload(download, canvas);
      report("");
    } catch {
      if (request === revision) report("A texture for this banner could not be loaded.");
    }
  }

  function rows(focusIndex) {
    layerCount.textContent = layers.length + " / " + data.maxLayers;
    add.disabled = layers.length >= data.maxLayers || patterns.size === 0;
    layerList.replaceChildren(...layers.map((layer, index) => {
      const row = element("li", "", "banner-layer");
      const group = element("fieldset");
      group.append(element("legend", "Layer " + (index + 1)));
      const pattern = selectField("Pattern", data.patterns, layer.pattern);
      const color = selectField("Dye", data.colors, layer.color);
      pattern.control.addEventListener("change", () => {
        layer.pattern = pattern.control.value;
        render();
      });
      color.control.addEventListener("change", () => {
        layer.color = color.control.value;
        render();
      });
      const moveUp = action("Up", "Move layer " + (index + 1) + " up");
      const moveDown = action("Down", "Move layer " + (index + 1) + " down");
      const remove = action("Remove", "Remove layer " + (index + 1));
      moveUp.disabled = index === 0;
      moveDown.disabled = index === layers.length - 1;
      function move(to) {
        [layers[index], layers[to]] = [layers[to], layers[index]];
        rows(to);
        render();
      }
      moveUp.addEventListener("click", () => move(index - 1));
      moveDown.addEventListener("click", () => move(index + 1));
      remove.addEventListener("click", () => {
        layers.splice(index, 1);
        rows(Math.min(index, layers.length - 1));
        if (layers.length === 0) add.focus();
        render();
      });
      const actions = element("div", "", "layer-actions");
      actions.append(moveUp, moveDown, remove);
      group.append(pattern.field, color.field, actions);
      row.append(group);
      return row;
    }));
    if (focusIndex >= 0) layerList.children[focusIndex].querySelector("select").focus();
  }

  add.addEventListener("click", () => {
    if (layers.length >= data.maxLayers || patterns.size === 0) return;
    layers.push({ pattern: data.patterns[0].id, color: colors.has("white") ? "white" : data.colors[0].id });
    rows(layers.length - 1);
    render();
  });
  base.control.addEventListener("change", render);
  rows();
  render();
}

function bannerSlots(data, texture) {
  const view = element("div", "", "banner-slots");
  if (!data) {
    view.append(element("p", "Run a new full export to enable inventory previews.", "design-hint"));
    return { view, render() {} };
  }

  const definitions = [
    { id: "banner", label: "Inventory banner", filename: "webcraft-banner-item.png" },
    { id: "shield", label: "Decorated shield", filename: "webcraft-decorated-shield.png" },
  ];
  const slots = definitions.map(({ id, label, filename }) => {
    const panel = element("section", "", "banner-slot");
    panel.setAttribute("aria-label", label);
    const canvas = surface(64, 64, label + " preview");
    const download = downloadLink(filename);
    download.setAttribute("aria-label", "Download " + label.toLowerCase() + " PNG");
    const status = element("p", "", "design-hint");
    status.setAttribute("role", "status");
    panel.append(element("h2", label), canvas, download, status);
    view.append(panel);
    return { id, canvas, download, status };
  });

  let revision = 0;
  function render(color, layers) {
    const request = ++revision;
    for (const slot of slots) {
      slot.download.hidden = true;
      const model = data[slot.id];
      const refs = [model?.bases?.[color.id], ...layers.map(layer => model?.patterns?.[layer.pattern.id])];
      if (refs.some(hash => !hash)) {
        clear(slot, "An inventory layer is missing from this export.");
        continue;
      }
      Promise.all(refs.map(texture)).then(([base, ...overlays]) => {
        if (request !== revision) return;
        const ctx = slot.canvas.getContext("2d");
        ctx.clearRect(0, 0, 64, 64);
        drawTexture(ctx, base);
        overlays.forEach((image, index) => drawTexture(ctx, image, layers[index].color.rgb));
        slot.status.textContent = "";
        updateDownload(slot.download, slot.canvas);
      }).catch(() => {
        if (request === revision) clear(slot, "An inventory texture could not be loaded.");
      });
    }
  }

  function clear(slot, message) {
    slot.canvas.getContext("2d").clearRect(0, 0, 64, 64);
    slot.status.textContent = message;
  }

  return { view, render };
}

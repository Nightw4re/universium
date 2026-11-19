import { action, downloadLink, drawTexture, element, setChoices, surface, updateDownload } from "./design-preview.mjs";
import { createArmorScene } from "./armor-geometry.mjs";
import { armorCatalog, armorHash, armorSlots, pitch, yaw } from "./armor-look.mjs";
import { skinControls } from "./armor-skin.mjs";

const frontScale = 8;
const drawOrder = ["legs", "feet", "chest", "head"];

export function armorComparison(root, data, texture, report, geometry, lookupSkin) {
  const catalog = armorCatalog(data);
  const trimMaterials = data.materials.map(choice => ({ ...choice, name: choice.name.replace(/ Material$/, "") }));
  let state = catalog.restore(location.hash);
  let currentHash = location.hash;
  let revision = 0;
  let frame;
  const comparison = element("div", "", "trim-comparison");
  const panels = ["Set A", "Set B"].map((label, index) => {
    const panel = element("section", "", "trim-panel");
    const heading = element("h2", label);
    heading.id = "trim-heading-" + index;
    panel.setAttribute("aria-labelledby", heading.id);

    const { table, rows } = armorTable(label, catalog);
    const preview = element("div", "", "trim-preview");
    const previewLabel = label + " worn preview";
    const canvas = surface(272, 340, previewLabel);
    let scene;
    try {
      scene = createArmorScene(canvas, geometry);
    } catch {
      // The front view is also available when WebGL cannot initialize.
    }
    const download = downloadLink("webcraft-trim-" + (index === 0 ? "a" : "b") + ".png");
    panel.append(heading, preview, download, table);
    comparison.append(panel);
    return { rows, canvas, preview, previewLabel, scene, download };
  });
  const useGeometry = panels.every(panel => panel.scene);
  const toolbar = element("div", "", "armor-toolbar");
  if (useGeometry) toolbar.append(orbitControls(panels, () => state, turn));
  toolbar.append(shareControls(syncHash));

  const skin = useGeometry ? skinControls(lookupSkin, (selected, profile, notify) => {
    for (const panel of panels) panel.scene.setSkin(selected);
    if (notify) {
      state.profile = profile;
      syncHash();
    }
    drawScenes();
  }) : null;

  for (const panel of panels) {
    if (!useGeometry) {
      panel.canvas = surface(data.projection.width * frontScale, data.projection.height * frontScale, panel.previewLabel);
    }
    panel.preview.append(panel.canvas);
    panel.download.addEventListener("click", () => updateDownload(panel.download, panel.canvas));
  }
  root.replaceChildren(toolbar, ...(skin ? [skin.panel] : []), comparison);

  function syncHash() {
    if (location.hash.split("?")[0] !== "#armor") return;
    currentHash = armorHash(state);
    history.replaceState(history.state, "", currentHash);
  }

  function drawScenes() {
    if (useGeometry) for (const panel of panels) panel.scene.draw(state.yaw, state.pitch);
  }

  function turn(angle, tilt, commit) {
    state.yaw = yaw(angle);
    state.pitch = pitch(tilt);
    if (frame === undefined) {
      frame = requestAnimationFrame(() => {
        frame = undefined;
        drawScenes();
      });
    }
    if (commit) syncHash();
  }

  function fields() {
    for (const [index, panel] of panels.entries()) {
      for (const row of panel.rows) renderRow(row, state.sets[index][row.slot]);
    }
  }

  function renderRow(row, style) {
    const { piece, material, pattern } = row;
    piece.value = style.id;
    const trims = catalog.trims(style.id);
    const available = new Set(trims.map(trim => trim.material));
    const materials = trimMaterials.filter(choice => available.has(choice.id));
    setChoices(material, materials, style.material || "minecraft:gold");
    const patterns = new Set(trims.filter(trim => trim.material === material.value).map(trim => trim.pattern));
    setChoices(pattern, [{ id: "", name: "No trim" }, ...data.patterns.filter(choice => patterns.has(choice.id))], style.pattern);
    material.disabled = materials.length === 0;
    pattern.disabled = patterns.size === 0;
  }

  function updateStyle(index, row) {
    const style = catalog.style(row.slot, [row.piece.value, row.material.value, row.pattern.value]);
    state.sets[index][row.slot] = style;
    renderRow(row, style);
    syncHash();
    render();
  }

  async function atlas(style) {
    const piece = catalog.pieces.get(style.id);
    if (!piece) return null;
    const trim = catalog.trims(style.id).find(row => row.material === style.material && row.pattern === style.pattern);
    const [base, detail] = await Promise.all([
      Promise.all(piece.layers.map(layer => texture(layer.texture))),
      trim ? texture(trim.texture) : null,
    ]);
    return armorAtlas(piece, base, detail, trim?.decal);
  }

  async function render() {
    const request = ++revision;
    panels.forEach(panel => { panel.download.hidden = true; });
    try {
      const equipped = await Promise.all(state.sets.map(set => Promise.all(
        drawOrder.map(async slot => [slot, await atlas(set[slot])]))));
      if (request !== revision) return;
      for (const [index, panel] of panels.entries()) {
        const layers = equipped[index].filter(([, image]) => image);
        if (useGeometry) panel.scene.equip(layers);
        else drawArmorFront(panel.canvas, data.projection, layers);
      }
      drawScenes();
      for (const panel of panels) updateDownload(panel.download, panel.canvas);
      report(useGeometry ? "" : "3D preview unavailable. Showing the front view.");
    } catch {
      if (request === revision) report("A texture for this comparison could not be loaded.");
    }
  }

  for (const [index, panel] of panels.entries()) {
    for (const row of panel.rows) {
      row.piece.addEventListener("change", () => updateStyle(index, row));
      row.material.addEventListener("change", () => updateStyle(index, row));
      row.pattern.addEventListener("change", () => updateStyle(index, row));
    }
  }
  fields();
  skin?.restore(state.profile);
  render();

  return () => {
    if (location.hash === currentHash) return;
    currentHash = location.hash;
    state = catalog.restore(currentHash);
    skin?.restore(state.profile);
    fields();
    render();
  };
}

function armorTable(label, catalog) {
  const table = element("table", "", "armor-table");
  table.setAttribute("aria-label", label + " armor slots");
  const columns = ["Armor material", "Trim material", "Pattern"];
  const header = element("thead");
  const headings = element("tr");
  for (const name of ["Slot", ...columns]) {
    const cell = element("th", name);
    cell.scope = "col";
    headings.append(cell);
  }
  header.append(headings);
  const body = element("tbody");
  const rows = armorSlots.map(({ id, name }) => {
    const row = element("tr");
    const title = element("th", name);
    title.scope = "row";
    row.append(title);
    const [piece, material, pattern] = columns.map(column => {
      const cell = element("td");
      const field = element("label", "", "design-field");
      const control = element("select");
      control.setAttribute("aria-label", name + " " + column.toLowerCase());
      field.append(element("span", column, "armor-cell-label"), control);
      cell.append(field);
      row.append(cell);
      return control;
    });
    setChoices(piece, [{ id: "", name: "Empty" }, ...catalog.forSlot(id).map(item => ({
      id: item.id,
      name: armorMaterialName(item.profile),
    }))]);
    body.append(row);
    return { slot: id, piece, material, pattern };
  });
  table.append(header, body);
  return { table, rows };
}

function armorMaterialName(profile) {
  const name = profile.replace(/\/(inner|outer)$/, "").split(":").at(-1);
  return name.split(/[/_-]+/).map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(" ");
}

function orbitControls(panels, current, turn) {
  const controls = element("div", "", "armor-orbit");
  const front = action("Front");
  const back = action("Back");
  controls.append(front, back, element("span", "Drag to rotate and tilt both views.", "design-hint"));
  front.addEventListener("click", () => turn(0, 0, true));
  back.addEventListener("click", () => turn(180, 0, true));

  const keys = new Map([["ArrowLeft", [-10, 0]], ["ArrowRight", [10, 0]], ["ArrowUp", [0, 10]], ["ArrowDown", [0, -10]]]);
  for (const { canvas } of panels) {
    canvas.classList.add("armor-3d-canvas");
    canvas.title = "Drag or use arrow keys to rotate and tilt";
    canvas.tabIndex = 0;
    let drag;
    canvas.addEventListener("pointerdown", event => {
      if (event.button !== 0 || drag) return;
      drag = { id: event.pointerId, x: event.clientX, y: event.clientY, yaw: current().yaw, pitch: current().pitch };
      canvas.setPointerCapture(event.pointerId);
    });
    canvas.addEventListener("pointermove", event => {
      if (drag?.id === event.pointerId) {
        turn(drag.yaw + (event.clientX - drag.x) * 1.25, drag.pitch - (event.clientY - drag.y) * 1.1, false);
      }
    });
    canvas.addEventListener("lostpointercapture", () => {
      if (drag) turn(current().yaw, current().pitch, true);
      drag = undefined;
    });
    canvas.addEventListener("keydown", event => {
      const delta = keys.get(event.key);
      if (!delta) return;
      event.preventDefault();
      turn(current().yaw + delta[0], current().pitch + delta[1], true);
    });
  }
  return controls;
}

function shareControls(syncHash) {
  const panel = element("div", "", "armor-share");
  const copy = action("Copy look link");
  const field = element("input");
  field.type = "url";
  field.readOnly = true;
  field.hidden = true;
  field.setAttribute("aria-label", "Shareable armor link");
  const feedback = element("span", "", "design-hint");
  feedback.setAttribute("role", "status");
  panel.append(copy, field, feedback);
  copy.addEventListener("click", async () => {
    syncHash();
    try {
      await navigator.clipboard.writeText(location.href);
      field.hidden = true;
      feedback.textContent = "Link copied";
    } catch {
      field.value = location.href;
      field.hidden = false;
      field.select();
      feedback.textContent = "";
    }
  });
  return panel;
}

function armorAtlas(piece, base, trim, decal) {
  const width = Math.max(...base.map(image => image.width), trim?.width ?? 0);
  const height = Math.max(...base.map(image => image.height), trim?.height ?? 0);
  const atlas = surface(width, height).getContext("2d");
  base.forEach((image, index) => drawTexture(atlas, image, piece.layers[index].tint));
  if (trim) {
    atlas.globalCompositeOperation = decal ? "source-atop" : "source-over";
    drawTexture(atlas, trim);
  }
  return atlas.canvas;
}

function drawArmorFront(canvas, projection, layers) {
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#a8b8ac";
  for (const [, , width, height, x, y] of projection.mannequin) {
    ctx.fillRect(x * frontScale, y * frontScale, width * frontScale, height * frontScale);
  }
  ctx.imageSmoothingEnabled = false;
  for (const [slot, atlas] of layers) {
    const uScale = atlas.width / 64;
    const vScale = atlas.height / 32;
    for (const [u, v, w, h, x, y, mirror] of projection.slots[slot]) {
      ctx.save();
      ctx.translate((x + (mirror ? w : 0)) * frontScale, y * frontScale);
      if (mirror) ctx.scale(-1, 1);
      ctx.drawImage(atlas, u * uScale, v * vScale, w * uScale, h * vScale, 0, 0, w * frontScale, h * frontScale);
      ctx.restore();
    }
  }
}

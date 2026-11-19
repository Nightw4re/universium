import { bannerEditor } from "./banners.mjs";
import { armorComparison } from "./armor.mjs";

const namespace = "webcraft_designs";
const views = { banners: bannerEditor, armor: armorComparison };
const loaded = new Set();
const restoreViews = new Map();

export async function showDesign(name, getJson) {
  if (loaded.has(name)) {
    restoreViews.get(name)?.();
    return;
  }
  loaded.add(name);
  const section = document.querySelector("#" + name + "-view");
  const status = section.querySelector(".design-status");
  const content = section.querySelector(".design-content");
  status.textContent = "Loading exported design data…";

  try {
    const api = "/api/v1/modules/" + namespace + "/" + name;
    const discovery = await getJson("/api/v1/modules");
    const module = discovery.modules.find(entry => entry.id === namespace + ":" + name);
    if (module?.routes?.data !== api + "/data" || module.routes.texture !== api + "/texture") {
      throw new Error("module_disabled");
    }
    const geometry = name === "armor" && module.routes.geometry === api + "/geometry"
      ? getJson(module.routes.geometry).catch(() => null)
      : null;
    const data = await getJson(module.routes.data);
    if (data.version !== 1) throw new Error("unsupported_design_version");
    const lookupSkin = name === "armor" && module.routes.skin === api + "/skin"
      ? name => getJson(module.routes.skin + "?" + new URLSearchParams({ name }))
      : undefined;
    const restore = views[name](content, data, textureLoader(module.routes.texture),
      message => { status.textContent = message; }, await geometry, lookupSkin);
    if (restore) restoreViews.set(name, restore);
    status.textContent = "";
  } catch (error) {
    loaded.delete(name);
    const messages = {
      module_disabled: "This data module is disabled.",
      design_export_missing: "Export the item catalog with /webcraft export and install the preview pack.",
      unsupported_design_version: "This export version is unsupported.",
    };
    status.textContent = messages[error.message] ?? "The design data could not be loaded.";
  }
}

function textureLoader(route) {
  const pending = new Map();
  return hash => {
    if (!pending.has(hash)) {
      const image = new Image();
      image.src = route + "?" + new URLSearchParams({ hash });
      pending.set(hash, image.decode().then(() => image).catch(error => {
        pending.delete(hash);
        throw error;
      }));
    }
    return pending.get(hash);
  };
}

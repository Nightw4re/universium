import { action, element, surface } from "./design-preview.mjs";
import { validProfileName } from "./armor-look.mjs";

export function skinControls(lookup, applied) {
  const panel = element("div", "", "armor-skin");
  const file = element("input");
  file.type = "file";
  file.accept = ".png,image/png";
  const fileField = element("label", "Skin PNG", "design-field");
  fileField.append(file);
  panel.append(fileField);

  const profile = element("input");
  const profileField = element("label", "Java profile", "design-field");
  profile.type = "text";
  profile.maxLength = 16;
  profile.autocomplete = "off";
  profile.spellcheck = false;
  profileField.append(profile);
  const load = action("Load skin");
  if (lookup) panel.append(profileField, load);
  const clear = action("Clear skin");
  clear.disabled = true;
  const slimField = element("label", "Slim arms", "skin-slim design-field");
  const slim = element("input");
  slim.type = "checkbox";
  slimField.prepend(slim);
  slimField.hidden = true;
  const status = element("p", "", "design-hint");
  status.setAttribute("role", "status");
  panel.append(clear, slimField, status);

  let revision = 0;
  let local;
  function reset() {
    local = undefined;
    slimField.hidden = true;
    slim.checked = false;
    file.value = "";
  }

  async function fromProfile(name, notify = true) {
    if (!lookup || !validProfileName(name)) {
      status.textContent = "Enter a Java Edition player name.";
      return;
    }
    const request = ++revision;
    status.textContent = "Loading profile skin…";
    try {
      const result = await lookup(name);
      const png = await decodeSkin("data:image/png;base64," + result.png);
      if (request !== revision) return;
      reset();
      profile.value = result.name;
      status.textContent = result.name + "’s skin";
      clear.disabled = false;
      applied({ image: png, slim: result.slim }, result.name, notify);
    } catch (error) {
      if (request !== revision) return;
      status.textContent = error.message === "profile_skin_not_found"
        ? "This Java profile has no published skin." : "The profile skin could not be loaded.";
    }
  }

  async function fromFile(selected) {
    const request = ++revision;
    try {
      if (selected.size > 65_536) throw new Error("Invalid skin");
      const header = new DataView(await selected.slice(0, 24).arrayBuffer());
      if (header.getUint32(0) !== 0x89504e47 || header.getUint32(4) !== 0x0d0a1a0a
          || header.getUint32(16) !== 64 || ![32, 64].includes(header.getUint32(20))) throw new Error("Invalid skin");
      const png = await decodeSkin(await readFile(selected));
      if (request !== revision) return;
      local = png;
      profile.value = "";
      slimField.hidden = false;
      clear.disabled = false;
      status.textContent = "Local skin";
      applied({ image: png, slim: slim.checked }, null, true);
    } catch {
      if (request === revision) status.textContent = "Choose a 64 × 64 or 64 × 32 Minecraft skin PNG.";
    }
  }

  function clearSkin(notify) {
    revision++;
    reset();
    profile.value = "";
    clear.disabled = true;
    status.textContent = "";
    applied(null, null, notify);
  }

  file.addEventListener("change", () => { if (file.files[0]) fromFile(file.files[0]); });
  slim.addEventListener("change", () => { if (local) applied({ image: local, slim: slim.checked }, null, true); });
  load.addEventListener("click", () => fromProfile(profile.value.trim()));
  profile.addEventListener("keydown", event => {
    if (event.key === "Enter") fromProfile(profile.value.trim());
  });
  clear.addEventListener("click", () => clearSkin(true));
  return {
    panel,
    restore(name) {
      clearSkin(false);
      if (name) fromProfile(name, false);
    },
  };
}

function readFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

async function decodeSkin(source) {
  const image = new Image();
  image.src = source;
  await image.decode();
  if (image.width !== 64 || ![32, 64].includes(image.height)) throw new Error("Invalid skin");
  const skin = surface(64, 64);
  const ctx = skin.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(image, 0, 0);
  if (image.height === 32) {
    // Fill the independent left limbs by mirroring the legacy right-limb faces.
    for (const [x, y, toX, width, height] of [
      [4, 16, 20, 4, 4], [8, 16, 24, 4, 4], [0, 20, 24, 4, 12], [4, 20, 20, 4, 12],
      [8, 20, 16, 4, 12], [12, 20, 28, 4, 12], [44, 16, 36, 4, 4], [48, 16, 40, 4, 4],
      [40, 20, 40, 4, 12], [44, 20, 36, 4, 12], [48, 20, 32, 4, 12], [52, 20, 44, 4, 12],
    ]) {
      ctx.save();
      ctx.translate(toX + width, y + 32);
      ctx.scale(-1, 1);
      ctx.drawImage(image, x, y, width, height, 0, 0, width, height);
      ctx.restore();
    }
  }
  for (const [x, y, width, height] of [[0, 0, 32, 16], [0, 16, 64, 16], [16, 48, 32, 16]]) {
    const face = ctx.getImageData(x, y, width, height);
    for (let alpha = 3; alpha < face.data.length; alpha += 4) face.data[alpha] = 255;
    ctx.putImageData(face, x, y);
  }
  return skin;
}

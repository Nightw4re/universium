export const armorSlots = [
  { id: "head", name: "Helmet", diamond: "minecraft:diamond_helmet" },
  { id: "chest", name: "Chestplate", diamond: "minecraft:diamond_chestplate" },
  { id: "legs", name: "Leggings", diamond: "minecraft:diamond_leggings" },
  { id: "feet", name: "Boots", diamond: "minecraft:diamond_boots" },
];

export function armorCatalog(data) {
  const pieces = new Map(data.pieces.map(piece => [piece.id, piece]));
  const profiles = new Map(Object.entries(data.profiles));
  const forSlot = slot => data.pieces.filter(piece => piece.slot === slot);
  const trims = id => profiles.get(pieces.get(id)?.profile) ?? [];

  function style(slot, [id = "", material = "", pattern = ""] = []) {
    if (pieces.get(id)?.slot !== slot) return { id: "", material: "", pattern: "" };
    const valid = trims(id);
    return {
      id,
      material: valid.some(trim => trim.material === material) ? material : "",
      pattern: valid.some(trim => trim.material === material && trim.pattern === pattern) ? pattern : "",
    };
  }

  function defaultSet(side) {
    return Object.fromEntries(armorSlots.map(slot => [
      slot.id,
      style(slot.id, [
        pieces.has(slot.diamond) ? slot.diamond : forSlot(slot.id)[0]?.id,
        "minecraft:gold",
        side === 0 ? "minecraft:sentry" : "minecraft:silence",
      ]),
    ]));
  }

  function restore(hash) {
    const params = new URLSearchParams(hash.split("?")[1] ?? "");
    const sets = ["a", "b"].map((key, side) => {
      if (!params.has(key)) return defaultSet(side);
      const items = params.get(key).split(";");
      return Object.fromEntries(armorSlots.map(({ id }, index) => [id, style(id, items[index]?.split("~"))]));
    });
    const profile = params.get("skin");
    return {
      sets,
      yaw: yaw(number(params.get("turn"), 22)),
      pitch: pitch(number(params.get("tilt"), 8)),
      profile: validProfileName(profile) ? profile : null,
    };
  }

  function number(value, fallback) {
    return /^-?\d{1,4}$/.test(value ?? "") ? Number(value) : fallback;
  }

  return { pieces, forSlot, trims, style, restore };
}

export function yaw(value) {
  return ((Math.round(value) % 360) + 360) % 360;
}

export function pitch(value) {
  return Math.min(90, Math.max(-90, Math.round(value)));
}

export function validProfileName(name) {
  return typeof name === "string" && /^[a-zA-Z0-9_]{3,16}$/.test(name);
}

export function armorHash(state) {
  const params = new URLSearchParams({ turn: state.yaw, tilt: state.pitch });
  for (const [index, set] of state.sets.entries()) {
    params.set(index ? "b" : "a", armorSlots.map(({ id }) => {
      const style = set[id];
      return style.id ? [style.id, style.material, style.pattern].join("~") : "";
    }).join(";"));
  }
  if (state.profile) params.set("skin", state.profile);
  return "#armor?" + params;
}

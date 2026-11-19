import assert from "node:assert/strict";
import { test } from "node:test";
import { armorCatalog, armorHash } from "../armor-look.mjs";

const catalog = armorCatalog({
  pieces: [
    { id: "minecraft:diamond_helmet", slot: "head", profile: "diamond" },
    { id: "minecraft:diamond_chestplate", slot: "chest", profile: "diamond" },
  ],
  profiles: {
    diamond: [
      { material: "minecraft:gold", pattern: "minecraft:sentry" },
      { material: "minecraft:gold", pattern: "minecraft:silence" },
    ],
  },
});

test("round-trips both independent outfits and an official profile", () => {
  const state = catalog.restore("#armor");
  state.sets[1].chest = catalog.style("chest", ["minecraft:diamond_chestplate", "minecraft:gold", ""]);
  state.sets[0].head = catalog.style("head");
  state.yaw = 219;
  state.pitch = -54;
  state.profile = "Viewer_";
  assert.deepEqual(catalog.restore(armorHash(state)), state);
});

test("bounds angles and discards cross-slot items and unknown trims", () => {
  const state = catalog.restore("#armor?turn=-1&tilt=950&skin=not+a+name"
    + "&a=minecraft:diamond_chestplate~minecraft:gold~minecraft:sentry"
    + ";minecraft:diamond_chestplate~minecraft:gold~missing:trim");
  assert.equal(state.yaw, 359);
  assert.equal(state.pitch, 90);
  assert.equal(state.profile, null);
  assert.equal(state.sets[0].head.id, "");
  assert.equal(state.sets[0].chest.pattern, "");
});

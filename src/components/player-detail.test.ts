import { describe, expect, it } from "vitest";
import { isEmptyItem, normalizeHotbarSlots, normalizeInventoryRows } from "./player-detail";

describe("player-detail", () => {
  it("treats minecraft air as empty", () => {
    expect(isEmptyItem({ id: "minecraft:air", count: 0, displayName: "" })).toBe(true);
    expect(isEmptyItem({ id: "minecraft:void_air", count: 0, displayName: "" })).toBe(true);
    expect(isEmptyItem({ id: "minecraft:cave_air", count: 0, displayName: "" })).toBe(true);
    expect(isEmptyItem({ id: "minecraft:stone", count: 1, displayName: "Stone" })).toBe(false);
  });

  it("rebuilds missing inventory slots from nbt slot indexes", () => {
    const rows = normalizeInventoryRows([
      {
        id: "minecraft:sand",
        count: 64,
        displayName: "Sand",
        nbt: { Slot: "9b" }
      },
      {
        id: "minecraft:dirt",
        count: 32,
        displayName: "Dirt",
        nbt: { Slot: "11b" }
      }
    ]);

    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveLength(9);
    expect(rows[0][0]?.displayName).toBe("Sand");
    expect(rows[0][1]).toBeUndefined();
    expect(rows[0][2]?.displayName).toBe("Dirt");
  });

  it("rebuilds missing hotbar slots from nbt slot indexes", () => {
    const hotbar = normalizeHotbarSlots([
      {
        id: "minecraft:stick",
        count: 3,
        displayName: "Stick",
        nbt: { Slot: "1b" }
      },
      {
        id: "minecraft:boat",
        count: 1,
        displayName: "Boat",
        nbt: { Slot: "3b" }
      }
    ]);

    expect(hotbar).toHaveLength(9);
    expect(hotbar[0]).toBeUndefined();
    expect(hotbar[1]?.displayName).toBe("Stick");
    expect(hotbar[2]).toBeUndefined();
    expect(hotbar[3]?.displayName).toBe("Boat");
  });
});

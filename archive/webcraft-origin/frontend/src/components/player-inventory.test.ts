import { describe, expect, it } from "vitest";
import { renderInventoryItem } from "./inventory-item";
import { bindPlayerInventoryHover, renderPlayerInventory } from "./player-inventory";

describe("renderPlayerInventory", () => {
  it("renders three inventory rows and one hotbar row without text labels", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        Array.from({ length: 9 }, (_, index) => ({
          icon: `<span data-slot="${index}"></span>`,
          tooltip: { title: `Item ${index + 1}` }
        })),
        Array.from({ length: 9 }, (_, index) => ({
          icon: `<span data-slot="row2-${index}"></span>`,
          tooltip: { title: `Item ${index + 10}` }
        })),
        Array.from({ length: 9 }, (_, index) => ({
          icon: `<span data-slot="row3-${index}"></span>`,
          tooltip: { title: `Item ${index + 19}` }
        }))
      ],
      hotbar: Array.from({ length: 9 }, (_, index) => ({
        icon: `<span data-hotbar-slot="${index}"></span>`,
        tooltip: { title: `Hotbar ${index + 1}` }
      }))
    });

    expect(html).not.toContain("inventory-item__name");
    expect(html.match(/player-inventory__row/g)?.length).toBe(4);
    expect(html.match(/inventory-item--slot/g)?.length).toBe(36);
    expect(html).toContain("player-inventory__row--hotbar");
    expect(html.match(/data-slot-group/g)?.length).toBe(2);
  });

  it("renders a hotbar label when provided", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      hotbarLabel: "Hotbar",
      rows: [Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })), Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })), Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
    });

    expect(html).toContain("player-inventory__hotbar-label");
    expect(html).toContain("Hotbar");
  });

  it("keeps empty slots present and tooltip-capable slots tagged", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "", empty: true },
          { icon: "<span></span>", tooltip: { title: "Item 1" } },
          { icon: "", empty: true },
          { icon: "<span></span>", tooltip: { title: "Item 2" } },
          { icon: "", empty: true },
          { icon: "<span></span>", tooltip: { title: "Item 3" } },
          { icon: "", empty: true },
          { icon: "<span></span>", tooltip: { title: "Item 4" } },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, (_, index) => ({
          icon: `<span data-row2="${index}"></span>`,
          tooltip: { title: `Item ${index + 5}` }
        })),
        Array.from({ length: 9 }, (_, index) => ({
          icon: `<span data-row3="${index}"></span>`,
          tooltip: { title: `Item ${index + 14}` }
        }))
      ],
      hotbar: Array.from({ length: 9 }, (_, index) => ({
        icon: index % 2 === 0 ? "" : `<span data-hotbar="${index}"></span>`,
        empty: index % 2 === 0,
        tooltip: index % 2 === 0 ? undefined : { title: `Hotbar ${index + 1}` }
      }))
    });

    expect(html).toContain("inventory-item--empty");
    expect(html).toContain("inventory-item--empty");
    expect(html).toContain("inventory-item--slot");
  });

  it("renders inventory groups in loading state before hydration", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
    });

    expect(html).toContain("item-slot-group");
    expect(html.match(/data-slot-group/g)?.length).toBe(2);
  });

  it("renders inventory item counts when the slot content comes from InventoryItem", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 1", count: 64 }), tooltip: { title: "Item 1" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 2" }), tooltip: { title: "Item 2" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 3", count: "12" }), tooltip: { title: "Item 3" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 4", count: 3 }), tooltip: { title: "Item 4" } },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, (_, index) =>
        index === 0
          ? { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar Item", count: 2 }), tooltip: { title: "Hotbar Item" } }
          : { icon: "<span></span>", empty: true }
      )
    });

    expect(html).toContain("inventory-item__count");
    expect(html).toContain(">64<");
    expect(html).toContain(">12<");
    expect(html).toContain(">3<");
    expect(html).toContain(">2<");
    expect(html).not.toContain("undefined");
  });

  it("does not render inventory item counts when the inventory item omits them", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 1" }), tooltip: { title: "Item 1" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 2" }), tooltip: { title: "Item 2" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 3" }), tooltip: { title: "Item 3" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 4" }), tooltip: { title: "Item 4" } },
          { icon: "", empty: true },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 5" }), tooltip: { title: "Item 5" } },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 6" }), tooltip: { title: "Item 6" } },
          { icon: "", empty: true },
          { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Item 7" }), tooltip: { title: "Item 7" } }
        ],
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
      ],
      hotbarLabel: "Hotbar",
      hotbar: [
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 1" }), tooltip: { title: "Hotbar 1" } },
        { icon: "", empty: true },
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 2" }), tooltip: { title: "Hotbar 2" } },
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 3" }), tooltip: { title: "Hotbar 3" } },
        { icon: "", empty: true },
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 4" }), tooltip: { title: "Hotbar 4" } },
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 5" }), tooltip: { title: "Hotbar 5" } },
        { icon: "", empty: true },
        { icon: renderInventoryItem({ kind: "item", icon: "<span></span>", label: "Hotbar 6" }), tooltip: { title: "Hotbar 6" } }
      ]
    });

    expect(html).not.toContain("item-slot__count");
    expect(html).not.toContain("inventory-item__count");
  });

  it("uses a fixed nine-slot row layout with tight spacing", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })), Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })), Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
    });

    expect(html).toContain("player-inventory__row");
    expect(html).toContain("player-inventory__row--hotbar");
    expect(html).not.toContain("repeat(9, minmax(0, 1fr))");
  });

  it("adds and removes hovered state on inventory slots", () => {
    document.body.innerHTML = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "<span data-slot='0'></span>", tooltip: { title: "Item 1" } },
          { icon: "<span data-slot='1'></span>", tooltip: { title: "Item 2" } },
          { icon: "<span data-slot='2'></span>", tooltip: { title: "Item 3" } },
          { icon: "<span data-slot='3'></span>", tooltip: { title: "Item 4" } },
          { icon: "<span data-slot='4'></span>", tooltip: { title: "Item 5" } },
          { icon: "<span data-slot='5'></span>", tooltip: { title: "Item 6" } },
          { icon: "<span data-slot='6'></span>", tooltip: { title: "Item 7" } },
          { icon: "<span data-slot='7'></span>", tooltip: { title: "Item 8" } },
          { icon: "<span data-slot='8'></span>", tooltip: { title: "Item 9" } }
        ],
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "<span></span>", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, (_, index) => ({
        icon: `<span data-hotbar="${index}"></span>`,
        tooltip: { title: `Hotbar ${index + 1}` }
      }))
    });

    const root = document.querySelector<HTMLElement>(".player-inventory");
    const slot = root?.querySelector<HTMLElement>("[data-player-inventory-slot]");
    expect(root).toBeTruthy();
    expect(slot).toBeTruthy();
    if (!root || !slot) return;

    bindPlayerInventoryHover(root);

    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(false);
    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(true);
    slot.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(false);
  });

  it("adds and removes hovered state on empty inventory slots too", () => {
    document.body.innerHTML = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, () => ({ icon: "", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
    });

    const root = document.querySelector<HTMLElement>(".player-inventory");
    const slot = root?.querySelectorAll<HTMLElement>("[data-player-inventory-slot]")[0];
    expect(root).toBeTruthy();
    expect(slot).toBeTruthy();
    if (!root || !slot) return;

    bindPlayerInventoryHover(root);

    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(false);
    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(true);
    slot.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    expect(slot.querySelector(".inventory-item")?.classList.contains("inventory-item--hovered")).toBe(false);
  });

  it("keeps empty inventory slots at a fixed slot size", () => {
    const html = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, () => ({ icon: "", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
    });

    expect(html).toContain("inventory-item--slot");
    expect(html).toContain("inventory-item--empty");
    expect(html).not.toContain("width: 0");
  });

  it("keeps empty inventory slots at a measurable slot size in computed styles", () => {
    document.body.innerHTML = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, () => ({ icon: "", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
    });

    const slot = document.querySelector<HTMLElement>("[data-player-inventory-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    const inventoryItem = slot.querySelector<HTMLElement>(".inventory-item");
    expect(inventoryItem).not.toBeNull();
    if (!inventoryItem) return;

    const computed = window.getComputedStyle(inventoryItem);
    expect(Number.parseFloat(computed.minWidth)).toBeGreaterThanOrEqual(54);
    expect(Number.parseFloat(computed.minHeight)).toBeGreaterThanOrEqual(54);
  });

  it("keeps tooltip markup shared and does not duplicate it during hover rerenders", () => {
    document.body.innerHTML = renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          { icon: "<span data-slot='0'></span>", tooltip: { title: "Item 1", footer: "minecraft" } },
          { icon: "<span data-slot='1'></span>", tooltip: { title: "Item 2", footer: "minecraft" } },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true },
          { icon: "", empty: true }
        ],
        Array.from({ length: 9 }, () => ({ icon: "", empty: true })),
        Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
      ],
      hotbar: Array.from({ length: 9 }, () => ({ icon: "", empty: true }))
    });

    const root = document.querySelector<HTMLElement>(".player-inventory");
    const slot = root?.querySelector<HTMLElement>("[data-player-inventory-slot]");
    expect(root).toBeTruthy();
    expect(slot).toBeTruthy();
    if (!root || !slot) return;

    bindPlayerInventoryHover(root);

    const before = document.querySelectorAll("[data-tooltip]").length;
    expect(before).toBe(2);

    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    slot.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));

    const after = document.querySelectorAll("[data-tooltip]").length;
    expect(after).toBe(before);
    expect(document.querySelectorAll("[data-tooltip]").length).toBe(2);
  });

  it("renders an ender chest layout in the story data shape", () => {
    const html = renderPlayerInventory({
      title: "Ender Chest",
      rows: [
        [
          { icon: "<span data-ender='0'></span>", tooltip: { title: "Slot 1" } },
          { icon: "<span data-ender='1'></span>", tooltip: { title: "Slot 2" } },
          { icon: "<span data-ender='2'></span>", tooltip: { title: "Slot 3" } },
          { icon: "<span data-ender='3'></span>", tooltip: { title: "Slot 4" } },
          { icon: "<span data-ender='4'></span>", tooltip: { title: "Slot 5" } },
          { icon: "<span data-ender='5'></span>", tooltip: { title: "Slot 6" } },
          { icon: "<span data-ender='6'></span>", tooltip: { title: "Slot 7" } },
          { icon: "<span data-ender='7'></span>", tooltip: { title: "Slot 8" } },
          { icon: "<span data-ender='8'></span>", tooltip: { title: "Slot 9" } }
        ],
        [
          { icon: "<span data-ender='9'></span>", tooltip: { title: "Slot 10" } },
          { icon: "<span data-ender='10'></span>", tooltip: { title: "Slot 11" } },
          { icon: "<span data-ender='11'></span>", tooltip: { title: "Slot 12" } },
          { icon: "<span data-ender='12'></span>", tooltip: { title: "Slot 13" } },
          { icon: "<span data-ender='13'></span>", tooltip: { title: "Slot 14" } },
          { icon: "<span data-ender='14'></span>", tooltip: { title: "Slot 15" } },
          { icon: "<span data-ender='15'></span>", tooltip: { title: "Slot 16" } },
          { icon: "<span data-ender='16'></span>", tooltip: { title: "Slot 17" } },
          { icon: "<span data-ender='17'></span>", tooltip: { title: "Slot 18" } }
        ],
        [
          { icon: "<span data-ender='18'></span>", tooltip: { title: "Slot 19" } },
          { icon: "<span data-ender='19'></span>", tooltip: { title: "Slot 20" } },
          { icon: "<span data-ender='20'></span>", tooltip: { title: "Slot 21" } },
          { icon: "<span data-ender='21'></span>", tooltip: { title: "Slot 22" } },
          { icon: "<span data-ender='22'></span>", tooltip: { title: "Slot 23" } },
          { icon: "<span data-ender='23'></span>", tooltip: { title: "Slot 24" } },
          { icon: "<span data-ender='24'></span>", tooltip: { title: "Slot 25" } },
          { icon: "<span data-ender='25'></span>", tooltip: { title: "Slot 26" } },
          { icon: "<span data-ender='26'></span>", tooltip: { title: "Slot 27" } }
        ]
      ],
    });

    expect(html).toContain("Ender Chest");
    expect(html.match(/player-inventory__row/g)?.length).toBe(3);
    expect(html).not.toContain("player-inventory__row--hotbar");
  });
});

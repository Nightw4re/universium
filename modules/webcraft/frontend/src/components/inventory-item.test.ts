import { describe, expect, it } from "vitest";
import { renderInventoryItem } from "./inventory-item";

describe("renderInventoryItem", () => {
  it("renders a count badge when count is provided", () => {
    const html = renderInventoryItem({
      kind: "item",
      icon: "<span></span>",
      label: "Golden Apple",
      count: 64
    });

    expect(html).toContain("inventory-item__count");
    expect(html).toContain(">64<");
  });

  it("does not render a count badge when count is omitted", () => {
    const html = renderInventoryItem({
      kind: "block",
      icon: "<span></span>",
      label: "Reinforced Deepslate"
    });

    expect(html).not.toContain("inventory-item__count");
  });

  it("renders the hover overlay inside the icon box so it sits above the item artwork", () => {
    const html = renderInventoryItem({
      kind: "item",
      icon: "<span class='item-art'></span>",
      label: "Hover Test",
      hovered: true
    });

    expect(html).toContain("<div class=\"inventory-item__icon\">");
    expect(html).toContain("<div class=\"inventory-item__hover-overlay\" aria-hidden=\"true\"></div>");
    expect(html.indexOf("inventory-item__hover-overlay")).toBeGreaterThan(html.indexOf("item-art"));
  });

  it("renders hovered empty items without dimming away the hover state", () => {
    const html = renderInventoryItem({
      kind: "block",
      icon: "<span class='empty-art'></span>",
      label: "Empty Hover",
      empty: true,
      hovered: true
    });

    expect(html).toContain("inventory-item--empty");
    expect(html).toContain("inventory-item--hovered");
    expect(html).toContain("inventory-item__hover-overlay");
    expect(html).toContain("inventory-item__icon");
  });
});

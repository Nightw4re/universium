import { describe, expect, it } from "vitest";
import { renderEquipment } from "./equipment";

describe("renderEquipment", () => {
  it("renders all equipment slots", () => {
    const html = renderEquipment({
      helmet: { icon: '<img src="/storybook-assets/previews/item/golden_helmet.png" alt="" />' },
      chestplate: { icon: '<img src="/storybook-assets/previews/item/shield_base.png" alt="" />' },
      legs: { icon: '<img src="/storybook-assets/previews/item/reinforced_deepslate.png" alt="" />' },
      boots: { icon: '<img src="/storybook-assets/previews/item/poppy.png" alt="" />' },
      offhand: { icon: '<img src="/storybook-assets/previews/item/shield.png" alt="" />' }
    });

    expect(html).toContain("Equipment");
    expect(html).toContain("Player model");
    expect(html).toContain("item-slot");
  });

  it("renders tooltip markup for equipped slots", () => {
    const html = renderEquipment({
      helmet: { icon: '<img src="/storybook-assets/previews/item/golden_helmet.png" alt="" />', tooltip: { title: "Helmet", description: "Test tooltip" } }
    });

    expect(html).toContain("data-tooltip");
    expect(html).toContain("Helmet");
    expect(html).toContain("Test tooltip");
  });

  it("wraps equipment slots in a loading group container", () => {
    const html = renderEquipment({
      helmet: { icon: "<span></span>" },
      chestplate: { icon: "<span></span>" },
      legs: { icon: "<span></span>" },
      boots: { icon: "<span></span>" },
      offhand: { icon: "<span></span>" }
    });

    expect(html).toContain("data-slot-group");
    expect(html).toContain("item-slot-group");
  });
});

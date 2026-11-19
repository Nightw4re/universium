import { describe, expect, it } from "vitest";
import { bindItemSlotHover, bindItemSlotTooltips, renderItemSlot } from "./item-slot";

describe("bindItemSlotTooltips", () => {
  it("marks block model templates as not ready before hydration", () => {
    const html = renderItemSlot({
      icon: '<img data-model-template="{&quot;texturePaths&quot;:[&quot;/storybook-assets/previews/block/reinforced_deepslate.png&quot;],&quot;faceMap&quot;:[0,0,0,0,0,0]}" alt="" />'
    });

    expect(html).toContain('data-item-slot-ready="false"');
  });

  it("renders loading slots with a static placeholder overlay", () => {
    const html = renderItemSlot({
      icon: "",
      loading: true
    });

    expect(html).toContain("item-slot--loading");
    expect(html).toContain("item-slot__loading-overlay");
    expect(html).toContain("item-slot__loading-placeholder");
    expect(html).toContain("item-slot__hover-overlay");
  });

  it("shows and repositions the tooltip with mouse movement", () => {
    document.body.innerHTML = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/golden_helmet.png" alt="" />',
      tooltip: {
        title: "Golden Helmet",
        description: "Armor piece",
        footer: "minecraft"
      }
    });

    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    const tooltip = slot.querySelector<HTMLElement>("[data-tooltip]");
    expect(tooltip).not.toBeNull();
    if (!tooltip) return;
    expect(slot.querySelector(".item-slot__tooltip")).toBeNull();

    Object.defineProperty(tooltip, "getBoundingClientRect", {
      value: () => ({ width: 200, height: 120 } as DOMRect)
    });

    bindItemSlotTooltips(slot);

    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 100, clientY: 100 }));
    expect(tooltip.classList.contains("tooltip--visible")).toBe(true);
    expect(tooltip.style.left).toBe("118px");
    expect(tooltip.style.top).toBe("118px");

    slot.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 300, clientY: 300 }));
    expect(tooltip.style.left).toBe("318px");
    expect(tooltip.style.top).toBe("318px");

    slot.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    expect(tooltip.classList.contains("tooltip--visible")).toBe(false);
  });

  it("does not attach a tooltip to empty slots", () => {
    document.body.innerHTML = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/golden_helmet.png" alt="" />',
      empty: true
    });

    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    expect(slot.querySelector("[data-tooltip]")).toBeNull();
  });

  it("reuses the same tooltip element instead of duplicating it", () => {
    document.body.innerHTML = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/golden_helmet.png" alt="" />',
      tooltip: {
        title: "Golden Helmet",
        description: "Armor piece",
        footer: "minecraft"
      }
    });

    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    const tooltip = slot.querySelector<HTMLElement>("[data-tooltip]");
    expect(tooltip).not.toBeNull();
    if (!tooltip) return;

    Object.defineProperty(tooltip, "getBoundingClientRect", {
      value: () => ({ width: 200, height: 120 } as DOMRect)
    });

    bindItemSlotTooltips(slot);

    const before = document.querySelectorAll("[data-tooltip]").length;
    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 100, clientY: 100 }));
    slot.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 120, clientY: 120 }));
    slot.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 140, clientY: 140 }));
    const after = document.querySelectorAll("[data-tooltip]").length;

    expect(after).toBe(before);
    expect(document.querySelectorAll(".tooltip--visible").length).toBe(1);
    expect(document.querySelector("[data-tooltip]")).toBe(tooltip);
  });

  it("shows the tooltip for a title-only slot story too", () => {
    document.body.innerHTML = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/egg.png" alt="" />',
      tooltip: {
        title: "Chicken Egg",
        footer: "minecraft"
      }
    });

    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    const tooltip = slot.querySelector<HTMLElement>("[data-tooltip]");
    expect(tooltip).not.toBeNull();
    if (!tooltip) return;

    Object.defineProperty(tooltip, "getBoundingClientRect", {
      value: () => ({ width: 200, height: 120 } as DOMRect)
    });

    bindItemSlotTooltips(slot);
    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 100, clientY: 100 }));

    expect(tooltip.classList.contains("tooltip--visible")).toBe(true);
  });

  it("uses a compact hitbox without a fixed width", () => {
    const html = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/egg.png" alt="" />',
      tooltip: {
        title: "Chicken Egg",
        footer: "minecraft"
      }
    });

    expect(html).not.toContain('width: 120px');
    expect(html).not.toContain('item-slot__label');
  });

  it("renders a hovered overlay without changing the slot content structure", () => {
    const html = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/crafting_table.png" alt="" />',
      hovered: true,
      tooltip: {
        title: "Crafting Table",
        footer: "minecraft"
      }
    });

    expect(html).toContain("item-slot--hovered");
    expect(html).toContain("item-slot__hover-overlay");
    expect(html).toContain("data-item-slot");
  });

  it("keeps the hover overlay in markup even before hover so runtime inventory can activate it", () => {
    const html = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/crafting_table.png" alt="" />',
      tooltip: {
        title: "Crafting Table",
        footer: "minecraft"
      }
    });

    expect(html).toContain("item-slot__hover-overlay");
    expect(html).not.toContain("item-slot--hovered");
  });

  it("renders and toggles hover for an empty slot without forcing item content", () => {
    const html = renderItemSlot({
      icon: "",
      empty: true,
    });

    expect(html).not.toContain("item-slot--hovered");
    expect(html).toContain("item-slot--empty");
    expect(html).toContain("item-slot__hover-overlay");
    expect(html).not.toContain('data-tooltip="true"');

    document.body.innerHTML = html;
    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    bindItemSlotHover(slot);
    expect(slot.classList.contains("item-slot--hovered")).toBe(false);
    slot.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    expect(slot.classList.contains("item-slot--hovered")).toBe(true);
    slot.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    expect(slot.classList.contains("item-slot--hovered")).toBe(false);
  });

  it("keeps the loading overlay in the markup without breaking tooltip wiring", () => {
    document.body.innerHTML = renderItemSlot({
      icon: '<img src="/storybook-assets/previews/item/crafting_table.png" alt="" />',
      loading: true,
      tooltip: {
        title: "Crafting Table",
        footer: "minecraft"
      }
    });

    const slot = document.querySelector<HTMLElement>("[data-item-slot]");
    expect(slot).not.toBeNull();
    if (!slot) return;

    expect(slot.classList.contains("item-slot--loading")).toBe(true);
    expect(slot.querySelector(".item-slot__loading-overlay")).not.toBeNull();
    expect(slot.querySelector(".item-slot__loading-placeholder")).not.toBeNull();
    expect(slot.querySelector(".item-slot__hover-overlay")).not.toBeNull();
  });
});

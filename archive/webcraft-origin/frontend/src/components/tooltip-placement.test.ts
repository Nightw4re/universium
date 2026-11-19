import { describe, expect, it } from "vitest";
import { bindTooltipPlacementDemo, renderTooltip } from "./tooltip";

describe("bindTooltipPlacementDemo", () => {
  it("shows tooltip only from the anchor, not from the card surface", () => {
    document.body.innerHTML = `
      <div data-tooltip-placement-card data-tooltip-placement="left-top" class="tooltip-placement-card">
        <button type="button" data-tooltip-anchor>Bottom right</button>
        ${renderTooltip({
          title: "Bottom right",
          description: "Fits up-left"
        })}
      </div>
    `;

    const card = document.querySelector<HTMLElement>("[data-tooltip-placement-card]");
    const anchor = document.querySelector<HTMLElement>("[data-tooltip-anchor]");
    const tooltip = document.querySelector<HTMLElement>("[data-tooltip]");

    expect(card).not.toBeNull();
    expect(anchor).not.toBeNull();
    expect(tooltip).not.toBeNull();
    if (!card || !anchor || !tooltip) return;

    Object.defineProperty(card, "getBoundingClientRect", {
      value: () => ({ left: 0, top: 0, width: 320, height: 260 } as DOMRect)
    });
    Object.defineProperty(tooltip, "getBoundingClientRect", {
      value: () => ({ width: 200, height: 120 } as DOMRect)
    });

    bindTooltipPlacementDemo(document.body);

    card.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 20, clientY: 20 }));
    expect(tooltip.classList.contains("tooltip--visible")).toBe(false);

    anchor.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 300, clientY: 240 }));
    expect(tooltip.classList.contains("tooltip--visible")).toBe(true);
    expect(Number.parseInt(tooltip.style.left, 10)).toBeLessThan(300);
    expect(Number.parseInt(tooltip.style.top, 10)).toBeLessThan(240);

    anchor.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    expect(tooltip.classList.contains("tooltip--visible")).toBe(false);
  });

  it("covers all four placement variants", () => {
    const bounds = { left: 0, top: 0, width: 420, height: 260 };

    const topLeft = computeTooltipPosition(220, 160, 120, 80, bounds, 18, {
      horizontal: "left",
      vertical: "top"
    });
    expect(topLeft.left).toBeLessThan(220);
    expect(topLeft.top).toBeLessThan(160);

    const topRight = computeTooltipPosition(120, 160, 120, 80, bounds, 18, {
      horizontal: "right",
      vertical: "top"
    });
    expect(topRight.left).toBeGreaterThan(120);
    expect(topRight.top).toBeLessThan(160);

    const bottomLeft = computeTooltipPosition(220, 80, 120, 80, bounds, 18, {
      horizontal: "left",
      vertical: "bottom"
    });
    expect(bottomLeft.left).toBeLessThan(220);
    expect(bottomLeft.top).toBeGreaterThan(80);

    const bottomRight = computeTooltipPosition(120, 80, 120, 80, bounds, 18, {
      horizontal: "right",
      vertical: "bottom"
    });
    expect(bottomRight.left).toBeGreaterThan(120);
    expect(bottomRight.top).toBeGreaterThan(80);
  });

  it("uses the side with more available space instead of a smaller forced side", () => {
    const position = computeTooltipPosition(240, 140, 160, 100, {
      left: 0,
      top: 0,
      width: 420,
      height: 260
    }, 18, { horizontal: "left", vertical: "top" });

    expect(position.left).toBeGreaterThan(240);
    expect(position.top).toBeGreaterThan(140);
  });

  it("clamps oversized tooltips inside the container bounds", () => {
    const position = computeTooltipPosition(120, 90, 500, 240, {
      left: 0,
      top: 0,
      width: 420,
      height: 260
    });

    expect(position.left).toBeGreaterThanOrEqual(8);
    expect(position.top).toBeGreaterThanOrEqual(8);
    expect(position.left).toBeLessThanOrEqual(12);
    expect(position.top).toBeLessThanOrEqual(12);
  });
});

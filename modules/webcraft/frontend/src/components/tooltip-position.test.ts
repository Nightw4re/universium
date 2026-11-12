import { describe, expect, it } from "vitest";
import { computeTooltipPosition } from "./tooltip";

describe("computeTooltipPosition", () => {
  it("favors down-right when there is enough space", () => {
    const position = computeTooltipPosition(100, 100, 200, 120, {
      left: 0,
      top: 0,
      width: 800,
      height: 600
    });

    expect(position).toEqual({ left: 118, top: 118 });
  });

  it("flips to the opposite side when the container is tight", () => {
    const position = computeTooltipPosition(760, 560, 200, 120, {
      left: 0,
      top: 0,
      width: 800,
      height: 600
    });

    expect(position.left).toBeLessThan(760);
    expect(position.top).toBeLessThan(560);
  });

  it("stays inside custom bounds instead of using the window", () => {
    const position = computeTooltipPosition(140, 140, 200, 120, {
      left: 0,
      top: 0,
      width: 220,
      height: 180
    });

    expect(position.left).toBeGreaterThanOrEqual(8);
    expect(position.top).toBeGreaterThanOrEqual(8);
    expect(position.left).toBeLessThanOrEqual(12);
  });
});

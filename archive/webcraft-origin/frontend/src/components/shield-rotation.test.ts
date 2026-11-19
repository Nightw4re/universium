import { describe, expect, it } from "vitest";
describe("shield rotation helpers", () => {
  const transformForRotateY = (rotateY: number) => {
    const normalized = ((rotateY % 360) + 360) % 360;
    return normalized
      ? { rotation: [0, normalized, 0], translation: [0, 0, 0], scale: [1, 1, 1] }
      : null;
  };

  it("keeps 0 and 360 degrees equivalent", () => {
    const front = transformForRotateY(0);
    const fullRotation = transformForRotateY(360);

    expect(fullRotation).toBeNull();
    expect(front).toBeNull();
  });

  it("keeps 180 degrees as a live rotateY transform", () => {
    const back = transformForRotateY(180);

    expect(back).not.toBeNull();
    expect(back?.rotation[1]).toBe(180);
  });
});

import { describe, expect, it } from "vitest";
import { renderDetailHero } from "./detail-hero";

describe("renderDetailHero", () => {
  it("renders the player name and played time as separate smaller blocks", () => {
    const html = renderDetailHero({
      name: "nightware6",
      playedTime: "Played 2d 04h",
      combat: [
        { label: "Armor", value: "18" },
        { label: "Armor Toughness", value: "4" },
        { label: "HP", value: "20" },
        { label: "Hunger", value: "20" },
        { label: "Saturation", value: "12.8" }
      ]
    });

    expect(html).toContain('class="detail-hero__name"');
    expect(html).toContain('class="detail-hero__time"');
    expect(html).toContain("nightware6");
    expect(html).toContain("Played 2d 04h");
  });

  it("renders exactly one player head and no legacy avatar markup", () => {
    const html = renderDetailHero({
      name: "nightware6",
      playedTime: "Played 2d 04h",
      combat: [
        { label: "Armor", value: "18" },
        { label: "Armor Toughness", value: "4" },
        { label: "HP", value: "20" },
        { label: "Hunger", value: "20" },
        { label: "Saturation", value: "12.8" }
      ]
    });

    expect(html.match(/class=\"player-head/g)?.length).toBe(1);
    expect(html).not.toContain("detail-hero__avatar");
    expect(html).not.toContain("player-card__avatar");
  });
});

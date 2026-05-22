import { describe, expect, it } from "vitest";
import { renderPlayerCard } from "./player-card";

describe("renderPlayerCard", () => {
  it("renders exactly one player head and no legacy avatar markup", () => {
    const html = renderPlayerCard({
      name: "nightware6",
      online: true,
      meta: "Online now"
    });

    expect(html.match(/class=\"player-head/g)?.length).toBe(1);
    expect(html).not.toContain("player-card__avatar");
    expect(html).not.toContain("detail-hero__avatar");
  });
});

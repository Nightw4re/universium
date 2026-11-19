import { describe, expect, it } from "vitest";
import { renderTooltip } from "./tooltip";

describe("renderTooltip", () => {
  it("renders visible tooltips when requested", () => {
    const html = renderTooltip(
      {
        title: "Terra Shatterer",
        description: "A tool for serious mining jobs."
      },
      { visible: true }
    );

    expect(html).toContain("tooltip--visible");
    expect(html).toContain("Terra Shatterer");
  });

  it("keeps tooltips hidden by default", () => {
    const html = renderTooltip({
      title: "Terra Shatterer",
      description: "A tool for serious mining jobs."
    });

    expect(html).not.toContain("tooltip--visible");
  });
});

import { createElement } from "react";
import { act } from "react-dom/test-utils";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App, createApp } from "./app";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.body.innerHTML = "";
});

describe("createApp", () => {
  it("renders the base shell", () => {
    const html = createApp();
    expect(html).toContain("WebCraft");
    expect(html).toContain("Players");
  });

  it("renders the players page when routed", () => {
    window.location.hash = "#/players";
    const html = createApp();
    expect(html).toContain("Search, filter and sort players");
    expect(html).toContain("nightware6");
  });
});

describe("App runtime", () => {
  it("shows the update page when player detail payload is invalid", async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes("/player/Dev/info")) {
        return Promise.resolve({
          ok: true,
          text: async () => '{"playerName":"Dev","armor":15,"equipment":{"helmet":{"id":"minecraft:turtle_helmet","count":1,"renderMode":"item",,"texturePath":"/mc-assets/textures/item/turtle_helmet.png"}}}'
        });
      }

      if (url.includes("/player/Dev/inventory-hotbar")) {
        return Promise.resolve({
          ok: true,
          text: async () => '{"rows":[],"hotbar":[]}'
        });
      }

      return Promise.resolve({
        ok: true,
        text: async () => "{}"
      });
    });
    vi.stubGlobal("fetch", fetchMock);

    window.location.hash = "#/player/Dev";

    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(createElement(App));
    });

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(container.textContent).toContain("Frontend update required");
    expect(container.textContent).toContain("Backend player detail payload is invalid JSON");

    root.unmount();
  });
});

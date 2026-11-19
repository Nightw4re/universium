import { createElement } from "react";
import { act } from "react-dom/test-utils";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TopPlayers } from "./top-players";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.body.innerHTML = "";
});

describe("TopPlayers", () => {
  it("loads top player data from the backend feed", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: [
          {
            playerName: "nightware6",
            playedTimeTicks: 1200,
            playedTimeLabel: "2h 11m",
            lastOnlineMillis: 0,
            lastOnlineLabel: "Online now",
            verifiedAccount: true
          },
          {
            playerName: "Dev",
            playedTimeTicks: 400,
            playedTimeLabel: "48m",
            lastOnlineMillis: 0,
            lastOnlineLabel: "Offline",
            verifiedAccount: false
          }
        ]
      })
    });

    vi.stubGlobal("fetch", fetchMock);

    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(createElement(TopPlayers, { title: "Top players", periodLabel: "All time", limit: 3 }));
    });

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/top-players", expect.objectContaining({ headers: { Accept: "application/json" } }));
    expect(container.textContent).toContain("nightware6");
    expect(container.textContent).toContain("Dev");
    expect(container.textContent).toContain("Played 2h 11m");

    root.unmount();
  });
});

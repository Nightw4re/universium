import { createElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { act } from "react-dom/test-utils";
import { createRoot } from "react-dom/client";
import { ServerInfoPanel, bindServerInfoPanel, renderServerInfoPanel } from "./server-info-panel";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.body.innerHTML = "";
});

describe("bindServerInfoPanel", () => {
  const render = (state: "idle" | "loading" | "refreshing" | "cooldown" = "idle") => {
    document.body.innerHTML = renderServerInfoPanel({
      title: "Server info",
      state,
      cooldownSeconds: 10,
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });
    const root = document.querySelector<HTMLElement>("[data-server-info-panel]");
    expect(root).not.toBeNull();
    if (!root) throw new Error("ServerInfoPanel root not found");
    bindServerInfoPanel(root);
    return root;
  };

  it("runs through loading, refreshing and cooldown states", () => {
    vi.useFakeTimers();
    const root = render();
    const refresh = root.querySelector<HTMLButtonElement>("[data-server-info-refresh]");
    const cancel = root.querySelector<HTMLButtonElement>("[data-server-info-cancel]");
    expect(refresh).not.toBeNull();
    expect(cancel).not.toBeNull();
    if (!refresh || !cancel) throw new Error("ServerInfoPanel controls not found");

    refresh.click();
    expect(root.dataset.serverInfoState).toBe("loading");
    expect(root.querySelector("[data-server-info-cancel]")).toBeNull();

    vi.advanceTimersByTime(800);
    expect(root.dataset.serverInfoState).toBe("refreshing");
    expect(root.querySelector("[data-server-info-cancel]")).not.toBeNull();

    cancel.click();
    expect(root.dataset.serverInfoState).toBe("idle");
    expect(root.querySelector("[data-server-info-cancel]")).toBeNull();
  });

  it("enters cooldown after refresh and counts down to idle", () => {
    vi.useFakeTimers();
    const root = render();
    const refresh = root.querySelector<HTMLButtonElement>("[data-server-info-refresh]");
    expect(refresh).not.toBeNull();
    if (!refresh) throw new Error("ServerInfoPanel refresh not found");

    refresh.click();
    vi.advanceTimersByTime(800);
    vi.advanceTimersByTime(1500);
    expect(root.dataset.serverInfoState).toBe("cooldown");

    vi.advanceTimersByTime(10000);
    expect(root.dataset.serverInfoState).toBe("idle");
  });

  it("renders the refresh and cancel controls with cooldown hint text", () => {
    const html = renderServerInfoPanel({
      title: "Server info",
      state: "cooldown",
      cooldownSeconds: 7,
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    expect(html).toContain("data-server-info-refresh");
    expect(html).not.toContain("data-server-info-cancel");
    expect(html).toContain("Refresh available in 7s");
  });

  it("does not render a tooltip on the refresh button while clickable", () => {
    const html = renderServerInfoPanel({
      title: "Server info",
      state: "idle",
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    expect(html).toContain("data-server-info-refresh");
    expect(html).not.toContain("data-tooltip");
  });

  it("renders loading skeleton rows without visible text content", () => {
    const html = renderServerInfoPanel({
      title: "Server info",
      state: "loading",
      loadingRows: 2,
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    expect(html).toContain("stat-row--loading");
    expect(html).toContain("stat-row__skeleton");
    expect(html).not.toContain("Players");
    expect(html).not.toContain("12 / 80");
  });

  it("keeps the refresh and cancel icons at the same top position", () => {
    document.body.innerHTML = renderServerInfoPanel({
      title: "Server info",
      state: "idle",
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    const refreshIcon = document.querySelector<HTMLElement>("[data-server-info-refresh] .icon-button__icon");
    const cancelIcon = document.querySelector<HTMLElement>("[data-server-info-cancel] .icon-button__icon");
    expect(refreshIcon).not.toBeNull();
    expect(cancelIcon).not.toBeNull();
    if (!refreshIcon || !cancelIcon) return;

    const refreshRect = refreshIcon.getBoundingClientRect();
    const cancelRect = cancelIcon.getBoundingClientRect();
    const refreshStyle = window.getComputedStyle(refreshIcon);
    const cancelStyle = window.getComputedStyle(cancelIcon);

    expect(refreshStyle.top).toBe("auto");
    expect(cancelStyle.top).toBe("auto");
    expect(Math.abs(refreshRect.top - cancelRect.top)).toBeLessThan(0.5);
  });

  it("shows a loader in loading and refreshing states", () => {
    document.body.innerHTML = renderServerInfoPanel({
      title: "Server info",
      state: "loading",
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    const refreshGlyph = document.querySelector<HTMLElement>("[data-server-info-refresh] .icon-button__glyph");
    const refreshLoader = document.querySelector<HTMLElement>("[data-server-info-refresh] .icon-button__loader");
    expect(refreshGlyph).toBeNull();
    expect(refreshLoader).not.toBeNull();
    if (!refreshLoader) return;

    document.body.innerHTML = renderServerInfoPanel({
      title: "Server info",
      state: "refreshing",
      rows: [
        { label: "Players", value: "12 / 80" },
        { label: "Uptime", value: "3d 12h" }
      ]
    });

    const refreshingGlyph = document.querySelector<HTMLElement>("[data-server-info-refresh] .icon-button__glyph");
    const refreshingLoader = document.querySelector<HTMLElement>("[data-server-info-refresh] .icon-button__loader");
    expect(refreshingGlyph).toBeNull();
    expect(refreshingLoader).not.toBeNull();
  });

  it("loads live server info from the backend endpoint", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          serverName: "WebCraft",
          online: true,
          minecraftVersion: "1.21.1",
          neoforgeBuild: 123,
          host: "0.0.0.0",
          port: 25565,
          publicUrl: "https://example.invalid",
          uptimeSeconds: 3661,
          resourcePackCount: 2
        }
      })
    });
    vi.stubGlobal("fetch", fetchMock);

    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(createElement(ServerInfoPanel, { title: "Server info" }));
    });

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/server-info", expect.objectContaining({ headers: { Accept: "application/json" } }));
    expect(container.textContent).toContain("WebCraft");
    expect(container.textContent).toContain("Status");
    expect(container.textContent).toContain("Online");
    expect(container.textContent).toContain("1h 01m");

    root.unmount();
  });

  it("shows the cooldown tooltip only on refresh hover and keeps it positioned", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          serverName: "WebCraft",
          online: true,
          minecraftVersion: "1.21.1",
          neoforgeBuild: 123,
          host: "0.0.0.0",
          port: 25565,
          publicUrl: "https://example.invalid",
          uptimeSeconds: 3661,
          resourcePackCount: 2
        }
      })
    });
    vi.stubGlobal("fetch", fetchMock);

    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(createElement(ServerInfoPanel, { title: "Server info" }));
    });

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      vi.advanceTimersByTime(800);
      vi.advanceTimersByTime(1500);
    });

    const refresh = container.querySelector<HTMLButtonElement>("[data-server-info-refresh]");
    const tooltip = container.querySelector<HTMLElement>("[data-tooltip]");
    expect(refresh).not.toBeNull();
    expect(tooltip).not.toBeNull();
    if (!refresh || !tooltip) return;

    Object.defineProperty(refresh, "getBoundingClientRect", {
      value: () => ({ left: 40, top: 60, right: 72, bottom: 92, width: 32, height: 32 } as DOMRect)
    });
    Object.defineProperty(tooltip, "getBoundingClientRect", {
      value: () => ({ left: 0, top: 0, right: 200, bottom: 120, width: 200, height: 120 } as DOMRect)
    });

    expect(container.textContent).toContain("Refreshing is available again in 10s.");
    expect(tooltip.classList.contains("tooltip--visible")).toBe(false);

    await act(async () => {
      refresh.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, clientX: 400, clientY: 220 }));
      refresh.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 400, clientY: 220 }));
    });

    expect(tooltip.classList.contains("tooltip--visible")).toBe(true);
    expect(Number.parseFloat(tooltip.style.left)).toBeGreaterThan(72);
    expect(Number.parseFloat(tooltip.style.top)).toBeLessThan(60);

    await act(async () => {
      refresh.dispatchEvent(new MouseEvent("mouseleave", { bubbles: true }));
    });

    expect(tooltip.classList.contains("tooltip--visible")).toBe(false);

    root.unmount();
  });
});

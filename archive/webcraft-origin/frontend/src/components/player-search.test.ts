import { describe, expect, it } from "vitest";
import { bindPlayerSearch, renderPlayerSearch } from "./player-search";

describe("renderPlayerSearch", () => {
  it("renders an interactive search panel with active chip state", () => {
    const html = renderPlayerSearch({ query: "Dev", filter: "offline" });

    expect(html).toContain("data-player-search");
    expect(html).toContain("data-text-clear-input-field");
    expect(html).toContain("data-player-search-chip=\"offline\"");
    expect(html).toContain("chip--active");
    expect(html).toContain("value=\"Dev\"");
  });

  it("emits change events when the input or chips are updated", () => {
    document.body.innerHTML = renderPlayerSearch({ query: "", filter: "all" });

    const root = document.querySelector<HTMLElement>("[data-player-search]");
    const input = root?.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
    const offlineChip = root?.querySelector<HTMLButtonElement>('[data-player-search-chip="offline"]');
    expect(root).toBeTruthy();
    expect(input).toBeTruthy();
    expect(offlineChip).toBeTruthy();
    if (!root || !input || !offlineChip) return;

    const details: Array<{ query: string; filter: string }> = [];
    root.addEventListener("player-search:change", (event) => {
      details.push((event as CustomEvent<{ query: string; filter: string }>).detail);
    });

    bindPlayerSearch(root);

    input.value = "night";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    offlineChip.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    expect(details).toEqual([
      { query: "night", filter: "all" },
      { query: "night", filter: "offline" }
    ]);
    expect(root.dataset.playerSearchQuery).toBe("night");
    expect(root.dataset.playerSearchFilter).toBe("offline");
    expect(offlineChip.classList.contains("chip--active")).toBe(true);
  });

  it("tracks select-box changes for sorting and online priority", () => {
    document.body.innerHTML = renderPlayerSearch({ sort: "Alphabetically [A-Z]", query: "", filter: "all", onlinePriority: true });

    const root = document.querySelector<HTMLElement>("[data-player-search]");
    const selectBox = root?.querySelector<HTMLElement>("[data-select-box]");
    const trigger = root?.querySelector<HTMLElement>("[data-select-box-trigger]");
    const checkbox = root?.querySelector<HTMLInputElement>("[data-select-box-checkbox] input[type='checkbox']");
    const option = root?.querySelector<HTMLButtonElement>("[data-select-box-option='reverse-alphabetical']");
    expect(root).toBeTruthy();
    expect(selectBox).toBeTruthy();
    expect(trigger).toBeTruthy();
    expect(checkbox).toBeTruthy();
    expect(option).toBeTruthy();
    if (!root || !selectBox || !trigger || !checkbox || !option) return;

    bindPlayerSearch(root);

    trigger.click();
    checkbox.click();
    expect(root.dataset.playerSearchOnlinePriority).toBe("false");
    checkbox.click();
    expect(root.dataset.playerSearchOnlinePriority).toBe("true");

    trigger.click();
    option.click();
    expect(root.dataset.playerSearchSort).toBe("Alphabetically [Z-A]");
  });
});

import { afterEach, describe, expect, it } from "vitest";
import { webcraftConfig } from "../config";
import { bindAppNavigation, renderAppNavigation } from "./app-navigation";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("bindAppNavigation", () => {
  const render = () => {
    document.body.innerHTML = renderAppNavigation({
      brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
      items: webcraftConfig.routes,
      activeId: "home"
    });
    const root = document.querySelector<HTMLElement>("[data-app-nav]");
    expect(root).not.toBeNull();
    if (!root) throw new Error("AppNavigation root not found");
    bindAppNavigation(root);
    return root;
  };

  it("toggles drawer on click and closes on Escape", () => {
    const root = render();
    const toggle = root.querySelector<HTMLButtonElement>("[data-app-nav-toggle]");
    expect(toggle).not.toBeNull();
    if (!toggle) throw new Error("AppNavigation toggle not found");

    toggle.click();
    expect(root.classList.contains("app-nav--open")).toBe(true);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    expect(root.classList.contains("app-nav--open")).toBe(false);
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
  });

  it("closes when clicking outside", () => {
    const root = render();
    const toggle = root.querySelector<HTMLButtonElement>("[data-app-nav-toggle]");
    expect(toggle).not.toBeNull();
    if (!toggle) throw new Error("AppNavigation toggle not found");

    toggle.click();
    expect(root.classList.contains("app-nav--open")).toBe(true);

    document.body.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    expect(root.classList.contains("app-nav--open")).toBe(false);
  });
});

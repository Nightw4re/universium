import { afterEach, describe, expect, it } from "vitest";
import { bindTextClearInput, renderTextClearInput } from "./text-clear-input";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("renderTextClearInput", () => {
  it("renders the input and clear button", () => {
    const html = renderTextClearInput({ value: "Dev", placeholder: "Search by name" });

    expect(html).toContain("text-clear-input");
    expect(html).toContain("text-clear-input__control");
    expect(html).toContain("data-text-clear-input-field");
    expect(html).toContain("data-text-clear-input-clear");
    expect(html).toContain("value=\"Dev\"");
  });

  it("uses a custom clear label when provided", () => {
    const html = renderTextClearInput({ clearLabel: "Remove text" });

    expect(html).toContain('aria-label="Remove text"');
  });
});

describe("bindTextClearInput", () => {
  it("toggles the clear button and clears the field", () => {
    document.body.innerHTML = renderTextClearInput({ value: "night", placeholder: "Search by name" });

    const root = document.querySelector<HTMLElement>("[data-text-clear-input]");
    const field = root?.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
    const clear = root?.querySelector<HTMLButtonElement>("[data-text-clear-input-clear]");
    const control = root?.querySelector<HTMLElement>(".text-clear-input__control");
    expect(root).toBeTruthy();
    expect(field).toBeTruthy();
    expect(clear).toBeTruthy();
    expect(control).toBeTruthy();
    if (!root || !field || !clear) return;

    bindTextClearInput(root);

    expect(clear.hidden).toBe(false);
    clear.click();
    expect(field.value).toBe("");
    expect(clear.hidden).toBe(true);
  });

  it("emits a change event when typing and clearing", () => {
    document.body.innerHTML = renderTextClearInput({ value: "", placeholder: "Search by name" });

    const root = document.querySelector<HTMLElement>("[data-text-clear-input]");
    const field = root?.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
    const clear = root?.querySelector<HTMLButtonElement>("[data-text-clear-input-clear]");
    expect(root).toBeTruthy();
    expect(field).toBeTruthy();
    expect(clear).toBeTruthy();
    if (!root || !field || !clear) return;

    const changes: string[] = [];
    root.addEventListener("text-clear-input:change", (event) => {
      changes.push((event as CustomEvent<{ value: string }>).detail.value);
    });

    bindTextClearInput(root);

    field.value = "night";
    field.dispatchEvent(new Event("input", { bubbles: true }));
    expect(changes.at(-1)).toBe("night");

    clear.click();
    expect(changes.at(-1)).toBe("");
  });

  it("keeps the clear button hidden for an empty initial value", () => {
    document.body.innerHTML = renderTextClearInput({ value: "", placeholder: "Search by name" });

    const root = document.querySelector<HTMLElement>("[data-text-clear-input]");
    const clear = root?.querySelector<HTMLButtonElement>("[data-text-clear-input-clear]");
    expect(root).toBeTruthy();
    expect(clear).toBeTruthy();
    if (!root || !clear) return;

    bindTextClearInput(root);

    expect(clear.hidden).toBe(true);
  });
});

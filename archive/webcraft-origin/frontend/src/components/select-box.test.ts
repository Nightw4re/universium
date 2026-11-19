import { afterEach, describe, expect, it } from "vitest";
import { bindSelectBox, resolveSelectBoxMenuWidth, renderSelectBox } from "./select-box";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("renderSelectBox", () => {
  it("passes a menu width hint to the wrapper", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      menuWidth: "440px",
      menuAlign: "center",
      options: [
        { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
        { label: "Alphabetical", value: "alphabetical", group: "option" }
      ]
    });

    expect(html).toContain("--select-box-menu-width: 440px");
    expect(html).toContain("--select-box-menu-align: center");
    expect(html).toContain("select-box__menu");
  });

  it("wraps option and checkbox labels for ellipsis-based truncation", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      options: [
        { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
        { label: "A very very long option name that should not create a horizontal scroll", value: "alphabetical", group: "option" }
      ]
    });

    expect(html).toContain("select-box__checkbox-text");
    expect(html).toContain("select-box__option-text");
    expect(html).toContain("A very very long option name that should not create a horizontal scroll");
  });

  it("keeps option text clipped instead of creating a horizontal scroll path", () => {
    document.body.innerHTML = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      menuWidth: "320px",
      options: [
        { label: "A very very long option name that should be clipped by ellipsis", value: "alphabetical", group: "option" }
      ]
    });

    const option = document.querySelector<HTMLElement>(".select-box__option");
    const optionText = document.querySelector<HTMLElement>(".select-box__option-text");

    expect(option).toBeTruthy();
    expect(optionText).toBeTruthy();
    expect(getComputedStyle(optionText as HTMLElement).textOverflow).toBe("ellipsis");
    expect(getComputedStyle(optionText as HTMLElement).overflow).toBe("hidden");
    expect(getComputedStyle(option as HTMLElement).overflow).toBe("hidden");
  });
});

describe("renderSelectBox optional sections", () => {
  it("omits search and checkbox sections when disabled", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      searchable: false,
      showCheckboxes: false,
      options: [
        { label: "Alphabetical", value: "alphabetical", group: "option" },
        { label: "Recently seen", value: "recently-seen", group: "option" }
      ]
    });

    expect(html).not.toContain("select-box__search");
    expect(html).not.toContain("select-box__checkboxes");
    expect(html).toContain("select-box__scroll");
  });

  it("still renders checkbox rows when they are enabled", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      options: [
        { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
        { label: "Alphabetical", value: "alphabetical", group: "option" }
      ]
    });

    expect(html).toContain("select-box__search");
    expect(html).toContain("select-box__checkboxes");
    expect(html).toContain("select-box__checkbox-text");
  });
});

describe("renderSelectBox dimensions", () => {
  it("marks auto width when no explicit width is provided", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      options: [
        { label: "Alphabetical", value: "alphabetical", group: "option" },
        { label: "Recently seen", value: "recently-seen", group: "option" }
      ]
    });

    expect(html).toContain('data-select-box-auto-width="true"');
  });

  it("disables auto width when an explicit width is passed", () => {
    const html = renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      menuWidth: "440px",
      options: [
        { label: "Alphabetical", value: "alphabetical", group: "option" }
      ]
    });

    expect(html).toContain('data-select-box-auto-width="false"');
    expect(html).toContain('--select-box-menu-width: 440px');
  });
});

describe("resolveSelectBoxMenuWidth", () => {
  it("uses explicit width when provided", () => {
    const result = resolveSelectBoxMenuWidth(180, 420, "440px");

    expect(result).toEqual({ width: "440px", minWidth: "180px", maxWidth: "440px", autoWidth: false });
  });

  it("grows to the widest measured content when auto width is enabled", () => {
    const result = resolveSelectBoxMenuWidth(180, 420);

    expect(result).toEqual({ width: "420px", minWidth: "180px", maxWidth: "525px", autoWidth: true });
  });

  it("never shrinks below the trigger width", () => {
    const result = resolveSelectBoxMenuWidth(220, 120);

    expect(result).toEqual({ width: "220px", minWidth: "220px", maxWidth: "275px", autoWidth: true });
  });

  it("clamps auto width to a reasonable maximum", () => {
    const result = resolveSelectBoxMenuWidth(240, 900);

    expect(result).toEqual({ width: "900px", minWidth: "240px", maxWidth: "1125px", autoWidth: true });
  });
});

describe("select box stress width", () => {
  it("clamps oversize auto-width results to a bounded maximum", () => {
    const result = resolveSelectBoxMenuWidth(210, 900);

    expect(result.autoWidth).toBe(true);
    expect(result.width).toBe("900px");
    expect(result.minWidth).toBe("210px");
    expect(result.maxWidth).toBe("1125px");
  });
});

describe("bindSelectBox runtime interaction", () => {
  const mount = (markup = renderSelectBox({
    label: "Sort by",
    selected: "alphabetical",
    options: [
      { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
      { label: "Alphabetical", value: "alphabetical", group: "option" },
      { label: "Recently seen", value: "recently-seen", group: "option" },
      { label: "Most active", value: "most-active", group: "option" }
    ]
  })) => {
    document.body.innerHTML = markup;
    const root = document.querySelector<HTMLElement>("[data-select-box]");
    if (!root) throw new Error("select box root not found");
    bindSelectBox(root);
    return root;
  };

  it("toggles open and closed from the trigger", () => {
    const root = mount();
    const trigger = root.querySelector<HTMLElement>("[data-select-box-trigger]");

    expect(root.classList.contains("select-box--open")).toBe(false);
    trigger?.click();
    expect(root.classList.contains("select-box--open")).toBe(true);
    expect(trigger?.getAttribute("aria-expanded")).toBe("true");
    trigger?.click();
    expect(root.classList.contains("select-box--open")).toBe(false);
    expect(trigger?.getAttribute("aria-expanded")).toBe("false");
  });

  it("closes on Escape and click outside", () => {
    const root = mount();
    const trigger = root.querySelector<HTMLElement>("[data-select-box-trigger]");

    trigger?.click();
    expect(root.classList.contains("select-box--open")).toBe(true);

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    expect(root.classList.contains("select-box--open")).toBe(false);

    trigger?.click();
    expect(root.classList.contains("select-box--open")).toBe(true);
    document.body.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    expect(root.classList.contains("select-box--open")).toBe(false);
  });

  it("keeps the menu open when toggling checkbox rows", () => {
    const root = mount();
    const trigger = root.querySelector<HTMLElement>("[data-select-box-trigger]");
    trigger?.click();

    const checkbox = root.querySelector<HTMLInputElement>("[data-select-box-checkbox] input[type='checkbox']");
    expect(checkbox).toBeTruthy();
    const initial = checkbox?.checked;
    checkbox?.click();
    expect(root.classList.contains("select-box--open")).toBe(true);
    expect(checkbox?.checked).toBe(!initial);
  });

  it("filters options through the search input and clears the filter", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const search = root.querySelector<HTMLInputElement>("[data-select-box-search]");
    const clear = root.querySelector<HTMLButtonElement>("[data-select-box-clear]");
    const options = root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]");

    expect(search).toBeTruthy();
    expect(clear).toBeTruthy();
    expect(options.length).toBe(3);

    if (!search || !clear) throw new Error("search controls missing");
    search.value = "recent";
    search.dispatchEvent(new Event("input", { bubbles: true }));

    expect(clear.hidden).toBe(false);
    expect(root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]:not([hidden])").length).toBe(1);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='recently-seen']")?.hidden).toBe(false);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='alphabetical']")?.hidden).toBe(true);

    clear.click();
    expect(search.value).toBe("");
    expect(clear.hidden).toBe(true);
    expect(root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]:not([hidden])").length).toBe(3);
  });

  it("filters options immediately on input while leaving checkbox rows visible", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const search = root.querySelector<HTMLInputElement>("[data-select-box-search]");
    expect(search).toBeTruthy();
    if (!search) return;

    search.value = "recent";
    search.dispatchEvent(new Event("input", { bubbles: true }));

    expect(root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]:not([hidden])").length).toBe(1);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='recently-seen']")?.hidden).toBe(false);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='alphabetical']")?.hidden).toBe(true);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='most-active']")?.hidden).toBe(true);
    expect(root.querySelectorAll<HTMLElement>("[data-select-box-checkbox]:not([hidden])").length).toBe(1);
  });

  it("filters options after a text-clear-input change event", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const searchRoot = root.querySelector<HTMLElement>("[data-text-clear-input]");
    const search = root.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
    expect(searchRoot).toBeTruthy();
    expect(search).toBeTruthy();
    if (!searchRoot || !search) return;

    search.value = "alp";
    searchRoot.dispatchEvent(new CustomEvent("text-clear-input:change", { bubbles: true, detail: { value: "alp" } }));

    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='alphabetical']")?.hidden).toBe(false);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='recently-seen']")?.hidden).toBe(true);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='most-active']")?.hidden).toBe(true);
  });

  it("filters option visibility in real time when the text clear input changes", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const searchRoot = root.querySelector<HTMLElement>("[data-text-clear-input]");
    expect(searchRoot).toBeTruthy();
    if (!searchRoot) return;

    searchRoot.dispatchEvent(new CustomEvent("text-clear-input:change", { bubbles: true, detail: { value: "recent" } }));

    expect(root.classList.contains("select-box--open")).toBe(true);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='recently-seen']")?.hidden).toBe(false);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='alphabetical']")?.hidden).toBe(true);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='most-active']")?.hidden).toBe(true);
  });

  it("keeps focus on the search input while filtering", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const search = root.querySelector<HTMLInputElement>("[data-select-box-search]");
    const searchRoot = root.querySelector<HTMLElement>("[data-text-clear-input]");
    expect(search).toBeTruthy();
    expect(searchRoot).toBeTruthy();
    if (!search || !searchRoot) return;

    search.focus();
    search.value = "recent";
    searchRoot.dispatchEvent(new CustomEvent("text-clear-input:change", { bubbles: true, detail: { value: "recent" } }));

    expect(root.classList.contains("select-box--open")).toBe(true);
    expect(document.activeElement).toBe(search);
  });

  it("filters options on change events as well as input events", () => {
    const root = mount();
    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();

    const search = root.querySelector<HTMLInputElement>("[data-select-box-search]");
    expect(search).toBeTruthy();
    if (!search) return;

    search.value = "most";
    search.dispatchEvent(new Event("change", { bubbles: true }));

    expect(root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]:not([hidden])").length).toBe(1);
    expect(root.querySelector<HTMLButtonElement>("[data-select-box-option='most-active']")?.hidden).toBe(false);
  });

  it("syncs a measured width when opened in auto-width mode", () => {
    const root = mount(renderSelectBox({
      label: "Sort by",
      selected: "alphabetical",
      options: [
        { label: "Alphabetical with a very long label", value: "alphabetical", group: "option" },
        { label: "Recently seen with another long label", value: "recently-seen", group: "option" }
      ]
    }));

    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
    const menu = root.querySelector<HTMLElement>("[data-select-box-menu]");

    expect(menu).toBeTruthy();
    expect(menu?.style.width).not.toBe("");
    expect(menu?.style.minWidth).not.toBe("");
    expect(menu?.style.maxWidth).not.toBe("");
  });

  it("emits a change event when selecting an option", () => {
    const root = mount();
    const changes: Array<{ value: string; checked: string[] }> = [];
    root.addEventListener("select-box:change", (event) => {
      changes.push((event as CustomEvent<{ value: string; checked: string[] }>).detail);
    });

    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
    root.querySelector<HTMLElement>("[data-select-box-option='recently-seen']")?.click();

    expect(changes.at(-1)?.value).toBe("Recently seen");
    expect(root.querySelector<HTMLElement>("[data-select-box-value]")?.textContent).toBe("Recently seen");
    expect(root.classList.contains("select-box--open")).toBe(false);
  });

  it("emits a change event when toggling checkboxes", () => {
    const root = mount();
    const changes: Array<{ value: string; checked: string[] }> = [];
    root.addEventListener("select-box:change", (event) => {
      changes.push((event as CustomEvent<{ value: string; checked: string[] }>).detail);
    });

    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
    const checkbox = root.querySelector<HTMLInputElement>("[data-select-box-checkbox] input[type='checkbox']");
    expect(checkbox).toBeTruthy();
    if (!checkbox) return;

    checkbox.click();
    expect(changes.at(-1)?.checked).toContain("online-first");
  });

  it("updates checkbox state on every toggle", () => {
    const root = mount();
    const changes: Array<{ value: string; checked: string[] }> = [];
    root.addEventListener("select-box:change", (event) => {
      changes.push((event as CustomEvent<{ value: string; checked: string[] }>).detail);
    });

    root.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
    const checkbox = root.querySelector<HTMLInputElement>("[data-select-box-checkbox] input[type='checkbox']");
    expect(checkbox).toBeTruthy();
    if (!checkbox) return;

    checkbox.click();
    expect(changes.at(-1)?.checked).toEqual([]);

    checkbox.click();
    expect(changes.at(-1)?.checked).toEqual(["online-first"]);
  });
});

import { createElement } from "react";
import { renderTextClearInput } from "./text-clear-input";
import { renderReactMarkup } from "./react-render";

export interface SelectOption {
  label: string;
  value: string;
  checked?: boolean;
  group?: "checkbox" | "option";
}

export interface SelectBoxProps {
  label: string;
  value?: string;
  selected?: string;
  options: SelectOption[];
  placeholder?: string;
  searchable?: boolean;
  showCheckboxes?: boolean;
  menuWidth?: string;
  menuAlign?: "start" | "center" | "end";
}

export const resolveSelectBoxMenuWidth = (triggerWidth: number, contentWidth: number, explicitWidth?: string) => {
  if (explicitWidth) return { width: explicitWidth, minWidth: `${Math.ceil(triggerWidth)}px`, maxWidth: explicitWidth, autoWidth: false };
  const resolvedWidth = Math.ceil(Math.max(triggerWidth, contentWidth));
  const maxWidth = Math.ceil(Math.max(triggerWidth, contentWidth) * 1.25);
  return { width: `${resolvedWidth}px`, minWidth: `${Math.ceil(triggerWidth)}px`, maxWidth: `${maxWidth}px`, autoWidth: true };
};

export const SelectBox = (props: SelectBoxProps) => {
  const selectedKey = props.value ?? props.selected ?? props.placeholder ?? "";
  const checkboxOptions = props.options.filter((option) => option.group === "checkbox");
  const menuOptions = props.options.filter((option) => option.group !== "checkbox");
  const showSearch = props.searchable !== false;
  const showCheckboxes = props.showCheckboxes !== false && checkboxOptions.length > 0;
  const activeOption = menuOptions.find((option) => option.value === selectedKey || option.label === selectedKey);
  const selected = activeOption?.label ?? selectedKey;
  return createElement(
    "div",
    {
      className: "select-box",
      "data-select-box": true,
      "data-select-box-auto-width": !props.menuWidth,
      style: { ["--select-box-menu-width" as never]: props.menuWidth ?? undefined, ["--select-box-menu-align" as never]: props.menuAlign ?? "start" }
    },
    createElement("label", { className: "select-box__label" }, props.label),
    createElement("button", { className: "select-box__trigger", type: "button", "aria-haspopup": "listbox", "aria-expanded": "false", "data-select-box-trigger": true }, createElement("span", { className: "select-box__value", "data-select-box-value": true }, selected), createElement("span", { className: "select-box__chevron" })),
    createElement(
      "div",
      { className: "select-box__menu", "data-select-box-menu": true },
      showSearch ? createElement("span", { dangerouslySetInnerHTML: { __html: renderTextClearInput({ placeholder: "Filter options" }) } }) : null,
      showSearch ? createElement("div", { className: "select-box__divider" }) : null,
      showCheckboxes
        ? createElement(
            "div",
            { className: "select-box__checkboxes" },
            checkboxOptions.map((option) =>
              createElement(
                "label",
                { className: "select-box__checkbox-row", "data-select-box-checkbox": true, key: option.value },
                createElement("input", { type: "checkbox", value: option.value, defaultChecked: option.checked }),
                createElement("span", { className: "select-box__checkbox-text" }, option.label)
              )
            )
          )
        : null,
      showCheckboxes ? createElement("div", { className: "select-box__divider" }) : null,
      createElement(
        "div",
        { className: "select-box__scroll", "data-select-box-options": true },
        menuOptions.map((option) =>
          createElement(
            "button",
            { className: `select-box__option${option.value === selectedKey || option.label === selectedKey ? " select-box__option--active" : ""}`, type: "button", "data-select-box-option": option.value, "data-select-box-option-label": option.label, key: option.value },
            createElement("span", { className: "select-box__option-text" }, option.label)
          )
        )
      )
    )
  );
};

export const renderSelectBox = (props: SelectBoxProps) => renderReactMarkup(createElement(SelectBox, props));

export const bindSelectBox = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  const trigger = root.querySelector<HTMLElement>("[data-select-box-trigger]");
  const menu = root.querySelector<HTMLElement>("[data-select-box-menu]");
  const value = root.querySelector<HTMLElement>("[data-select-box-value]");
  const searchRoot = root.querySelector<HTMLElement>("[data-text-clear-input]");
  const search = root.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
  const clear = root.querySelector<HTMLButtonElement>("[data-text-clear-input-clear]");
  const optionButtons = Array.from(root.querySelectorAll<HTMLButtonElement>("[data-select-box-option]"));
  const checkboxRows = Array.from(root.querySelectorAll<HTMLElement>("[data-select-box-checkbox]"));
  if (!trigger || !menu || !value) return;
  const state = { open: false, currentValue: value.textContent ?? "", selectedCheckboxes: new Map<HTMLInputElement, boolean>() };
  const getCheckedValues = () => Array.from(state.selectedCheckboxes.entries()).filter(([, checked]) => checked).map(([inp]) => inp.value);
  const applySearch = (query: string) => {
    const normalized = query.trim().toLowerCase();
    optionButtons.forEach((button) => {
      const label = button.dataset.selectBoxOptionLabel ?? button.textContent ?? "";
      button.hidden = normalized.length > 0 && !label.toLowerCase().includes(normalized);
    });
    if (clear) clear.hidden = !query;
  };
  const setOpen = (nextOpen: boolean) => {
    state.open = nextOpen;
    root.classList.toggle("select-box--open", nextOpen);
    trigger.setAttribute("aria-expanded", String(nextOpen));
    if (nextOpen) search?.focus();
  };
  trigger.addEventListener("click", (event) => { event.preventDefault(); setOpen(!state.open); });
  optionButtons.forEach((button) => button.addEventListener("click", () => {
    state.currentValue = button.dataset.selectBoxOptionLabel ?? button.textContent ?? state.currentValue;
    value.textContent = state.currentValue;
    optionButtons.forEach((other) => other.classList.toggle("select-box__option--active", other === button));
    root.dispatchEvent(new CustomEvent("select-box:change", { bubbles: true, detail: { value: state.currentValue, checked: getCheckedValues() } }));
    setOpen(false);
  }));
  checkboxRows.forEach((row) => {
    const input = row.querySelector<HTMLInputElement>("input[type='checkbox']");
    if (!input) return;
    state.selectedCheckboxes.set(input, input.checked);
    input.addEventListener("change", () => {
      state.selectedCheckboxes.set(input, input.checked);
      root.dispatchEvent(new CustomEvent("select-box:change", { bubbles: true, detail: { value: state.currentValue, checked: getCheckedValues() } }));
    });
  });
  search?.addEventListener("input", () => applySearch(search.value));
  search?.addEventListener("change", () => applySearch(search.value));
  clear?.addEventListener("click", () => {
    if (!search) return;
    search.value = "";
    applySearch("");
    search.focus();
  });
  document.addEventListener("click", (event) => { if (!root.contains(event.target as Node)) setOpen(false); });
  document.addEventListener("keydown", (event) => { if (event.key === "Escape") setOpen(false); });
  setOpen(false);
  if (searchRoot) {
    searchRoot.addEventListener("text-clear-input:change", (event) => {
      const detail = (event as CustomEvent<{ value: string }>).detail;
      applySearch(detail.value);
    });
  }
};

import { createElement } from "react";
import { renderReactMarkup } from "./react-render";

export interface TextClearInputProps {
  value?: string;
  placeholder?: string;
  clearLabel?: string;
}

export const TextClearInput = (props: TextClearInputProps = {}) =>
  createElement(
    "div",
    { className: "text-clear-input", "data-text-clear-input": true },
    createElement(
      "div",
      { className: "text-clear-input__control" },
      createElement("input", {
        className: "text-clear-input__field",
        type: "text",
        defaultValue: props.value ?? "",
        placeholder: props.placeholder ?? "",
        "data-text-clear-input-field": true,
        "data-select-box-search": true
      }),
      createElement(
        "button",
        { type: "button", className: "text-clear-input__clear", "aria-label": props.clearLabel ?? "Clear text", "data-text-clear-input-clear": true, "data-select-box-clear": true, hidden: true },
        "X"
      )
    )
  );

export const renderTextClearInput = (props: TextClearInputProps = {}) => renderReactMarkup(createElement(TextClearInput, props));

export const bindTextClearInput = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  const field = root.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
  const clear = root.querySelector<HTMLButtonElement>("[data-text-clear-input-clear]");
  if (!field || !clear) return;
  const sync = () => { clear.hidden = !field.value; };
  const emit = () => root.dispatchEvent(new CustomEvent("text-clear-input:change", { bubbles: true, detail: { value: field.value } }));
  field.addEventListener("input", () => { sync(); emit(); });
  clear.addEventListener("click", () => {
    field.value = "";
    sync();
    emit();
    field.dispatchEvent(new Event("input", { bubbles: true }));
    field.focus();
  });
  sync();
};

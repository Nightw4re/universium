import { createElement } from "react";
import { Tooltip, bindTooltipHover, type TooltipDescriptor } from "./tooltip";
import { renderReactMarkup } from "./react-render";
import { hydrateModelTemplateTextures, hydrateModelTextures } from "./item-render";

export interface InventoryItemProps {
  kind?: "block" | "item" | "entity";
  icon: string;
  label?: string;
  count?: string | number;
  layout?: "card" | "slot";
  hovered?: boolean;
  tooltip?: TooltipDescriptor;
  empty?: boolean;
}

export const InventoryItem = (data: InventoryItemProps) =>
  createElement(
    "article",
    {
      className: `inventory-item inventory-item--${data.kind ?? "item"} inventory-item--${data.layout ?? "card"}${data.empty ? " inventory-item--empty" : ""}${data.hovered ? " inventory-item--hovered" : ""}`,
      "aria-label": data.label
    },
    createElement(
      "div",
      { className: "inventory-item__icon" },
      createElement("div", { className: "inventory-item__icon-content", dangerouslySetInnerHTML: { __html: data.icon } }),
      createElement("div", { className: "inventory-item__hover-overlay", "aria-hidden": "true" })
    ),
    data.count !== undefined ? createElement("div", { className: "inventory-item__count" }, String(data.count)) : null,
    data.tooltip ? createElement(Tooltip, { descriptor: data.tooltip, options: { visible: false } }) : null
  );

export const renderInventoryItem = (data: InventoryItemProps) => renderReactMarkup(createElement(InventoryItem, data));

export const bindInventoryItemTooltips = (root: HTMLElement) => {
  bindTooltipHover(root);
  void hydrateModelTemplateTextures(root);
  void hydrateModelTextures(root);
};

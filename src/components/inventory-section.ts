import { createElement } from "react";
import { renderItemSlot, type ItemSlotProps } from "./item-slot";
import { renderReactMarkup } from "./react-render";

export interface InventorySectionProps {
  title: string;
  rows: ItemSlotProps[][];
  hotbar: ItemSlotProps[];
}

export const InventorySection = (data: InventorySectionProps) =>
  createElement(
    "section",
    { className: "panel inventory-section" },
    createElement("p", { className: "eyebrow" }, data.title),
    createElement(
      "div",
      { className: "inventory-section__grid" },
      data.rows.map((row, index) => createElement("div", { className: "inventory-section__row", key: index }, createElement("span", { dangerouslySetInnerHTML: { __html: row.map(renderItemSlot).join("") } }))),
      createElement("div", { className: "inventory-section__row inventory-section__row--hotbar" }, createElement("span", { dangerouslySetInnerHTML: { __html: data.hotbar.map(renderItemSlot).join("") } }))
    )
  );

export const renderInventorySection = (data: InventorySectionProps) => renderReactMarkup(createElement(InventorySection, data));

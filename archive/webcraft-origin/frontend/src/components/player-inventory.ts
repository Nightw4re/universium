import { createElement, Fragment } from "react";
import { bindItemSlotGroupLoading, bindItemSlotHover, bindItemSlotTooltips, renderItemSlot, type ItemSlotProps } from "./item-slot";
import { renderReactMarkup } from "./react-render";

export interface PlayerInventoryProps {
  title?: string;
  rows: ItemSlotProps[][];
  hotbar?: ItemSlotProps[];
  hotbarLabel?: string;
  slotSize?: "default" | "large";
}

export const PlayerInventory = (data: PlayerInventoryProps) =>
  createElement(
    "section",
    { className: `panel player-inventory${data.slotSize === "large" ? " player-inventory--large" : ""}` },
    data.title ? createElement("p", { className: "eyebrow" }, data.title) : null,
    createElement(
      "div",
      { className: "player-inventory__grid" },
      createElement(
        "div",
        { className: "player-inventory__group", "data-slot-group": true, "data-player-inventory-group": "inventory" },
        data.rows.map((row, index) =>
          createElement("div", {
            className: "player-inventory__row",
            key: index,
            dangerouslySetInnerHTML: { __html: row.map(renderPlayerInventorySlot).join("") }
          })
        )
      ),
      data.hotbar?.length
        ? createElement(
            Fragment,
            null,
            data.hotbarLabel ? createElement("p", { className: "eyebrow player-inventory__hotbar-label" }, data.hotbarLabel) : null,
            createElement(
              "div",
              { className: "player-inventory__group player-inventory__group--hotbar", "data-slot-group": true, "data-player-inventory-group": "hotbar" },
              createElement("div", {
                className: "player-inventory__row player-inventory__row--hotbar",
                key: "hotbar",
                dangerouslySetInnerHTML: { __html: data.hotbar.map(renderPlayerInventorySlot).join("") }
              })
            )
          )
        : null
    )
  );

const renderPlayerInventorySlot = (slot: ItemSlotProps) => `
    <div class="player-inventory__slot" data-player-inventory-slot data-player-inventory-slot-props="${encodeURIComponent(JSON.stringify(slot))}">
      ${renderItemSlot({
        ...slot,
        hovered: slot.hovered ?? false,
        tooltipMode: slot.tooltip ? slot.tooltipMode ?? "global" : slot.tooltipMode
      })}
    </div>
`;

export const renderPlayerInventory = (data: PlayerInventoryProps) => renderReactMarkup(createElement(PlayerInventory, data));

export const bindPlayerInventoryHover = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  root.querySelectorAll<HTMLElement>("[data-player-inventory-group]").forEach((group) => {
    bindItemSlotGroupLoading(group);
  });
  const slots = Array.from(root.querySelectorAll<HTMLElement>("[data-player-inventory-slot]"));
  const rerender = (slot: HTMLElement, hovered: boolean) => {
    const serialized = slot.dataset.playerInventorySlotProps;
    if (!serialized) return;
    const props = JSON.parse(decodeURIComponent(serialized)) as ItemSlotProps;
    slot.innerHTML = renderItemSlot({
      ...props,
      hovered,
      tooltipMode: props.tooltip ? props.tooltipMode ?? "global" : props.tooltipMode
    });
    const itemSlot = slot.querySelector<HTMLElement>("[data-item-slot]");
    if (itemSlot && props.tooltip) bindItemSlotTooltips(itemSlot);
    if (itemSlot) bindItemSlotHover(itemSlot);
  };
  slots.forEach((slot) => {
    slot.addEventListener("mouseenter", () => rerender(slot, true));
    slot.addEventListener("mouseleave", () => rerender(slot, false));
  });
};

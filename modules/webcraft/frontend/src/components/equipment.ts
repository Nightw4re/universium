import { createElement } from "react";
import { ItemSlot, type ItemSlotProps } from "./item-slot";
import { renderReactMarkup } from "./react-render";
import type { TooltipDescriptor } from "./tooltip";

export interface EquipmentSlotConfig {
  icon: string;
  tooltip?: TooltipDescriptor;
  tooltipMode?: "inline" | "global";
  count?: string | number;
  empty?: boolean;
}

export interface EquipmentProps {
  helmet?: EquipmentSlotConfig;
  chestplate?: EquipmentSlotConfig;
  legs?: EquipmentSlotConfig;
  boots?: EquipmentSlotConfig;
  offhand?: EquipmentSlotConfig;
}

const renderSlot = (slot?: EquipmentSlotConfig) => {
  return createElement(
    "div",
    { className: "equipment__slot" },
    createElement(ItemSlot, {
      icon: slot?.icon ?? '<span class="equipment__slot-empty"></span>',
      tooltip: slot?.tooltip,
      tooltipMode: slot?.tooltipMode,
      count: slot?.count,
      empty: slot?.empty ?? !slot,
      hovered: false
    } satisfies ItemSlotProps)
  );
};

export const Equipment = (props: EquipmentProps) =>
  createElement(
    "section",
    { className: "panel equipment item-slot-group", "data-slot-group": true },
    createElement("p", { className: "eyebrow" }, "Equipment"),
    createElement(
      "div",
      { className: "equipment__layout" },
      createElement(
        "div",
        { className: "equipment__armor" },
        renderSlot(props.helmet),
        renderSlot(props.chestplate),
        renderSlot(props.legs),
        renderSlot(props.boots)
      ),
      createElement(
        "div",
        { className: "equipment__model" },
        createElement("div", { className: "equipment__model-frame" }, createElement("span", null, "Player model"))
      ),
      createElement(
        "div",
        { className: "equipment__offhand" },
        renderSlot(props.offhand)
      )
    )
  );

export const renderEquipment = (props: EquipmentProps) => renderReactMarkup(createElement(Equipment, props));

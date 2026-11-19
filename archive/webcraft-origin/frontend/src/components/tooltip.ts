import { createElement, forwardRef } from "react";
import { renderReactMarkup } from "./react-render";

export interface TooltipDescriptor {
  title: string;
  attributes?: Array<{ label: string; value: string }>;
  enchantments?: string[];
  description?: string;
  hidden?: Array<{ label: string; value: string }>;
  footer?: string;
}
export interface TooltipBounds { left: number; top: number; width: number; height: number; }
export interface TooltipPosition { left: number; top: number; }
export interface TooltipRenderOptions { visible?: boolean; position?: TooltipPosition; strategy?: "fixed" | "absolute"; }
export interface TooltipPlacementPreference { horizontal?: "left" | "right"; vertical?: "top" | "bottom"; }

export const Tooltip = forwardRef<HTMLDivElement, { descriptor: TooltipDescriptor; options?: TooltipRenderOptions }>((props, ref) =>
  createElement(
    "div",
    {
      className: `tooltip${props.options?.visible ? " tooltip--visible" : ""}${props.options?.strategy === "absolute" ? " tooltip--absolute" : ""}`,
      "data-tooltip": true,
      ref,
      style: props.options?.position ? { left: `${props.options.position.left}px`, top: `${props.options.position.top}px` } : undefined
    },
    createElement("div", { className: "tooltip__title" }, props.descriptor.title),
    props.descriptor.attributes?.length
      ? createElement("div", { className: "tooltip__attributes" }, props.descriptor.attributes.map((attr) => createElement("div", { className: "tooltip__attribute", key: attr.label }, createElement("span", null, attr.label), createElement("strong", null, attr.value))))
      : null,
    props.descriptor.enchantments?.length
      ? createElement("div", { className: "tooltip__block" }, props.descriptor.enchantments.map((line) => createElement("div", { className: "tooltip__line tooltip__line--accent", key: line }, line)))
      : null,
    props.descriptor.description ? createElement("div", { className: "tooltip__block tooltip__description" }, props.descriptor.description) : null,
    props.descriptor.hidden?.length
      ? createElement("div", { className: "tooltip__hidden" }, props.descriptor.hidden.map((line) => createElement("div", { className: "tooltip__hidden-line", key: line.label }, createElement("span", null, line.label), createElement("strong", null, line.value))))
      : null,
    props.descriptor.footer ? createElement("div", { className: "tooltip__footer" }, props.descriptor.footer) : null
  )
);

export const computeTooltipPosition = (
  anchorX: number,
  anchorY: number,
  tooltipWidth: number,
  tooltipHeight: number,
  bounds: TooltipBounds,
  offset = 18,
  preference: TooltipPlacementPreference = {}
): TooltipPosition => {
  const rightEdge = bounds.left + bounds.width;
  const bottomEdge = bounds.top + bounds.height;
  const leftSpace = anchorX - bounds.left;
  const rightSpace = rightEdge - anchorX;
  const topSpace = anchorY - bounds.top;
  const bottomSpace = bottomEdge - anchorY;
  const canFitLeft = leftSpace >= tooltipWidth + offset;
  const canFitRight = rightSpace >= tooltipWidth + offset;
  const canFitTop = topSpace >= tooltipHeight + offset;
  const canFitBottom = bottomSpace >= tooltipHeight + offset;
  const chooseHorizontal = (): "left" | "right" => preference.horizontal === "left" && canFitLeft ? "left" : preference.horizontal === "right" && canFitRight ? "right" : canFitRight && !canFitLeft ? "right" : canFitLeft && !canFitRight ? "left" : rightSpace >= leftSpace ? "right" : "left";
  const chooseVertical = (): "top" | "bottom" => preference.vertical === "top" && canFitTop ? "top" : preference.vertical === "bottom" && canFitBottom ? "bottom" : canFitBottom && !canFitTop ? "bottom" : canFitTop && !canFitBottom ? "top" : bottomSpace >= topSpace ? "bottom" : "top";
  const horizontal = chooseHorizontal();
  const vertical = chooseVertical();
  let left = horizontal === "right" ? anchorX + offset : anchorX - tooltipWidth - offset;
  let top = vertical === "bottom" ? anchorY + offset : anchorY - tooltipHeight - offset;
  const minLeft = bounds.left + 8;
  const minTop = bounds.top + 8;
  const maxLeft = rightEdge - tooltipWidth - 8;
  const maxTop = bottomEdge - tooltipHeight - 8;
  if (left < minLeft) left = minLeft;
  if (top < minTop) top = minTop;
  if (left > maxLeft) left = maxLeft;
  if (top > maxTop) top = maxTop;
  return { left: Math.round(left), top: Math.round(top) };
};

export const renderTooltip = (descriptor: TooltipDescriptor, options: TooltipRenderOptions = {}) => renderReactMarkup(createElement(Tooltip, { descriptor, options }));

export const bindTooltipHover = (root: HTMLElement, tooltipSelector = "[data-tooltip]") => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  const tooltip = root.querySelector<HTMLElement>(tooltipSelector);
  if (!tooltip) return;
  let visible = false;
  const offset = 18;
  const setPosition = (clientX: number, clientY: number) => {
    const width = tooltip.getBoundingClientRect().width || 0;
    const height = tooltip.getBoundingClientRect().height || 0;
    const { left, top } = computeTooltipPosition(clientX, clientY, width, height, { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight }, offset);
    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${top}px`;
  };
  root.addEventListener("mouseenter", (event) => {
    visible = true;
    tooltip.classList.add("tooltip--visible");
    setPosition((event as MouseEvent).clientX, (event as MouseEvent).clientY);
  });
  root.addEventListener("mousemove", (event) => {
    if (!visible) return;
    setPosition((event as MouseEvent).clientX, (event as MouseEvent).clientY);
  });
  root.addEventListener("mouseleave", () => {
    visible = false;
    tooltip.classList.remove("tooltip--visible");
  });
};

export const bindTooltipPlacementDemo = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  root.querySelectorAll<HTMLElement>("[data-tooltip-placement-card]").forEach((card) => {
    const tooltip = card.querySelector<HTMLElement>("[data-tooltip]");
    const anchor = card.querySelector<HTMLElement>("[data-tooltip-anchor]");
    if (!tooltip || !anchor) return;
    const [horizontal, vertical] = (card.dataset.tooltipPlacement ?? "").split("-") as Array<"left" | "right" | "top" | "bottom" | undefined>;
    const preference: TooltipPlacementPreference = { horizontal: horizontal === "left" || horizontal === "right" ? horizontal : undefined, vertical: vertical === "top" || vertical === "bottom" ? vertical : undefined };
    let visible = false;
    const update = (event: MouseEvent) => {
      const cardRect = card.getBoundingClientRect();
      const tooltipRect = tooltip.getBoundingClientRect();
      const { left, top } = computeTooltipPosition(event.clientX - cardRect.left, event.clientY - cardRect.top, tooltipRect.width || 0, tooltipRect.height || 0, { left: 0, top: 0, width: cardRect.width, height: cardRect.height }, 18, preference);
      tooltip.style.left = `${left}px`;
      tooltip.style.top = `${top}px`;
    };
    anchor.addEventListener("mouseenter", (event) => { visible = true; tooltip.classList.add("tooltip--visible"); update(event as MouseEvent); });
    anchor.addEventListener("mousemove", (event) => { if (!visible) return; update(event as MouseEvent); });
    anchor.addEventListener("mouseleave", () => { visible = false; tooltip.classList.remove("tooltip--visible"); });
  });
};

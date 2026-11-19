import { createElement } from "react";
import { Tooltip, bindTooltipHover, renderTooltip, type TooltipDescriptor } from "./tooltip";
import { renderReactMarkup } from "./react-render";
import { hydrateModelTemplateTextures, hydrateModelTextures } from "./item-render";

export interface ItemSlotProps {
  icon: string;
  tooltip?: TooltipDescriptor;
  tooltipMode?: "inline" | "global";
  empty?: boolean;
  count?: string | number;
  hovered?: boolean;
  loading?: boolean;
}

export const ItemSlot = (props: ItemSlotProps) =>
  createElement(
    "div",
    {
      className: `item-slot${props.empty ? " item-slot--empty" : ""}${props.hovered ? " item-slot--hovered" : ""}${props.loading ? " item-slot--loading" : ""}`,
      "data-item-slot": true,
      "data-item-slot-ready": props.loading || props.icon.includes("data-model-template") || props.icon.includes("data-model-geometry-path") || props.icon.includes("data-model-builtin") ? "false" : "true",
      "data-item-slot-empty": props.tooltip ? undefined : true,
      "data-tooltip-mode": props.tooltipMode ?? "inline",
      "data-tooltip-descriptor": props.tooltip && props.tooltipMode === "global" ? encodeURIComponent(JSON.stringify(props.tooltip)) : undefined
    },
    createElement(
      "div",
      { className: "item-slot__icon" },
      createElement("div", { className: "item-slot__icon-content", dangerouslySetInnerHTML: { __html: props.icon } }),
      props.count !== undefined ? createElement("div", { className: "item-slot__count" }, String(props.count)) : null,
      createElement(
        "div",
        { className: "item-slot__loading-overlay", "aria-hidden": "true" },
        createElement(
          "svg",
          { viewBox: "0 0 64 64", focusable: "false", className: "item-slot__loading-placeholder" },
          createElement("rect", { width: 64, height: 64, fill: "#10140f" }),
          createElement("path", { d: "M14 46h36v4H14zM18 14h28v4H18zM14 18h4v28h-4zM46 18h4v28h-4z", fill: "#263120" }),
          createElement("rect", { x: 22, y: 22, width: 20, height: 20, fill: "#171d15" })
        )
      ),
      createElement("div", { className: "item-slot__hover-overlay", "aria-hidden": "true" })
    ),
    props.tooltip && (props.tooltipMode ?? "inline") === "inline"
      ? createElement(Tooltip, { descriptor: props.tooltip, options: { visible: false } })
      : null
  );

export const renderItemSlot = (props: ItemSlotProps) => renderReactMarkup(createElement(ItemSlot, props));

export const bindItemSlotTooltips = (root: HTMLElement) => {
  const mode = root.dataset.tooltipMode ?? "inline";
  if (mode === "global") {
    const descriptor = root.dataset.tooltipDescriptor;
    if (!descriptor) return;
    bindGlobalTooltipHover(root, JSON.parse(decodeURIComponent(descriptor)) as TooltipDescriptor);
    void hydrateModelTemplateTextures(document);
    void hydrateModelTextures(document);
    return;
  }
  bindTooltipHover(root);
  void hydrateModelTemplateTextures(root);
  void hydrateModelTextures(root);
};

const bindGlobalTooltipHover = (root: HTMLElement, descriptor: TooltipDescriptor) => {
  if (root.dataset.tooltipBound === "true") return;
  root.dataset.tooltipBound = "true";
  let visible = false;
  const getLayer = () => {
    const host = document.querySelector<HTMLElement>(".app-shell");
    if (!host) return null;
    let layer = host.querySelector<HTMLElement>("[data-app-tooltip-layer]");
    if (!layer) {
      layer = document.createElement("div");
      layer.setAttribute("data-app-tooltip-layer", "true");
      layer.className = "app-tooltip-layer";
      host.appendChild(layer);
    }
    return layer;
  };
  const renderLayer = (position?: { left: number; top: number }) => {
    const layer = getLayer();
    if (!layer) return;
    layer.innerHTML = renderTooltip(descriptor, { visible, position, strategy: "absolute" });
  };
  const update = (event: MouseEvent) => {
    const layer = getLayer();
    if (!layer) return;
    const tooltip = layer.querySelector<HTMLElement>("[data-tooltip]");
    if (!tooltip) return;
    const host = layer.parentElement?.getBoundingClientRect();
    const tooltipRect = tooltip.getBoundingClientRect();
    const width = tooltipRect.width || 0;
    const height = tooltipRect.height || 0;
    const bounds = host ? { left: 0, top: 0, width: host.width, height: host.height } : { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight };
    const left = event.clientX - (host?.left ?? 0);
    const top = event.clientY - (host?.top ?? 0);
    const position = { left: Math.round(Math.max(8, Math.min(bounds.width - width - 8, left + 18))), top: Math.round(Math.max(8, Math.min(bounds.height - height - 8, top + 18))) };
    renderLayer(position);
  };
  root.addEventListener("mouseenter", (event) => {
    visible = true;
    renderLayer({ left: 0, top: 0 });
    update(event as MouseEvent);
  });
  root.addEventListener("mousemove", (event) => {
    if (!visible) return;
    update(event as MouseEvent);
  });
  root.addEventListener("mouseleave", () => {
    visible = false;
    renderLayer({ left: 0, top: 0 });
  });
};

export const bindItemSlotHover = (root: HTMLElement) => {
  if (root.dataset.hoverBound === "true") return;
  root.dataset.hoverBound = "true";
  root.addEventListener("mouseenter", () => root.classList.add("item-slot--hovered"));
  root.addEventListener("mouseleave", () => root.classList.remove("item-slot--hovered"));
};

export const bindItemSlotGroupLoading = (root: HTMLElement) => {
  if (root.dataset.groupBound === "true") return;
  root.dataset.groupBound = "true";
  const generation = String(Number(root.dataset.groupGeneration ?? "0") + 1);
  root.dataset.groupGeneration = generation;
  root.dataset.groupReady = "false";
  const timeoutId = window.setTimeout(() => {
    if (!root.isConnected || root.dataset.groupGeneration !== generation) return;
    root.querySelectorAll<HTMLElement>("[data-item-slot]").forEach((slot) => {
      slot.classList.remove("item-slot--loading");
    });
    root.dataset.groupReady = "true";
    root.classList.add("item-slot-group--ready");
  }, 30_000);
  const slots = Array.from(root.querySelectorAll<HTMLElement>("[data-item-slot]"));
  const sync = () => {
    const ready = slots.every((slot) => !slot.classList.contains("item-slot--loading"));
    root.dataset.groupReady = ready ? "true" : "false";
    root.classList.toggle("item-slot-group--ready", ready);
  };
  slots.forEach((slot) => slot.classList.add("item-slot--loading"));
  sync();
  const waitForImages = async () => {
    const images = Array.from(root.querySelectorAll<HTMLImageElement>("img"));
    await Promise.all(images.map(async (image) => {
      try {
        if ("decode" in image && typeof image.decode === "function") {
          await image.decode();
          return;
        }
      } catch {
        // Ignore decode failures and fall back to load/error state.
      }
      if (image.complete) return;
      await new Promise<void>((resolve) => {
        const finish = () => resolve();
        image.addEventListener("load", finish, { once: true });
        image.addEventListener("error", finish, { once: true });
      });
    }));
  };
  void Promise.all([hydrateModelTemplateTextures(root), hydrateModelTextures(root)])
    .then(() => waitForImages())
    .then(() => {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          if (!root.isConnected || root.dataset.groupGeneration !== generation) return;
          window.clearTimeout(timeoutId);
          slots.forEach((slot) => {
            slot.classList.remove("item-slot--loading");
          });
          sync();
        });
      });
  });
};

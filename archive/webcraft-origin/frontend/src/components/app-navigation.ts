import { createElement } from "react";
import { renderReactMarkup } from "./react-render";

export interface AppNavigationItem {
  id: string;
  label: string;
  href: string;
}

export interface AppNavigationBrand {
  mark: string;
  title: string;
  subtitle?: string;
  href?: string;
}

export interface AppNavigationProps {
  brand: AppNavigationBrand;
  items: AppNavigationItem[];
  activeId: string;
  menuLabel?: string;
  variant?: "topbar" | "sidebar";
}

export const AppNavigation = (props: AppNavigationProps) => {
  const isSidebar = props.variant === "sidebar";
  return createElement(
    "header",
    {
      className: `app-nav${isSidebar ? " app-nav--sidebar" : ""}`,
      "data-app-nav": true
    },
    createElement(
      "a",
      { className: "app-nav__brand", href: props.brand.href ?? "#/" },
      createElement("span", { className: "app-nav__brand-mark" }, props.brand.mark),
      createElement(
        "span",
        { className: "app-nav__brand-copy" },
        createElement("strong", null, props.brand.title),
        props.brand.subtitle ? createElement("span", null, props.brand.subtitle) : null
      )
    ),
    createElement(
      "div",
      { className: "app-nav__actions" },
      createElement(
        "nav",
        { className: "app-nav__inline", "data-app-nav-inline": true },
        props.items.map((item) =>
          createElement(
            "a",
            {
              key: item.id,
              className: `app-nav__item${item.id === props.activeId ? " app-nav__item--active" : ""}`,
              href: item.href,
              "data-app-nav-inline-item": true
            },
            createElement("span", { className: "app-nav__item-label" }, item.label)
          )
        )
      ),
      createElement(
        "button",
        {
          className: "app-nav__menu",
          type: "button",
          "aria-expanded": "false",
          "aria-label": props.menuLabel ?? "Open menu",
          "data-app-nav-toggle": true
        },
        createElement("span", null),
        createElement("span", null),
        createElement("span", null)
      ),
      createElement(
        "nav",
        { className: "app-nav__submenu", "data-app-nav-submenu": true },
        props.items.map((item) =>
          createElement(
            "a",
            {
              key: item.id,
              className: `app-nav__item${item.id === props.activeId ? " app-nav__item--active" : ""}`,
              href: item.href,
              "data-app-nav-submenu-item": true
            },
            createElement("span", { className: "app-nav__item-label" }, item.label)
          )
        )
      )
    )
  );
};

export const renderAppNavigation = (props: AppNavigationProps) => {
  return renderReactMarkup(createElement(AppNavigation, props));
};

export const bindAppNavigation = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";

  const toggle = root.querySelector<HTMLButtonElement>("[data-app-nav-toggle]");
  const inlineNav = root.querySelector<HTMLElement>("[data-app-nav-inline]");
  const submenu = root.querySelector<HTMLElement>("[data-app-nav-submenu]");
  if (!toggle || !inlineNav || !submenu) return;

  const isSidebar = root.classList.contains("app-nav--sidebar");

  const getGap = (element: HTMLElement) => {
    const styles = getComputedStyle(element);
    return Number.parseFloat(isSidebar ? styles.rowGap : styles.columnGap) || Number.parseFloat(styles.gap) || 0;
  };

  const getInlineItems = () => {
    return Array.from(inlineNav.querySelectorAll<HTMLElement>("[data-app-nav-inline-item]"));
  };

  const getInlineWidth = () => {
    const items = getInlineItems();
    const gap = getGap(inlineNav);
    const itemWidth = items.reduce((total, item) => total + item.getBoundingClientRect().width, 0);
    return itemWidth + Math.max(0, items.length - 1) * gap;
  };

  const getInlineHeight = () => {
    const items = getInlineItems();
    const gap = getGap(inlineNav);
    const itemHeight = items.reduce((total, item) => total + item.getBoundingClientRect().height, 0);
    return itemHeight + Math.max(0, items.length - 1) * gap;
  };

  const setOpen = (open: boolean) => {
    root.classList.toggle("app-nav--open", open);
    toggle.setAttribute("aria-expanded", String(open));
  };

  const setCollapsed = (collapsed: boolean) => {
    root.classList.toggle("app-nav--collapsed", collapsed);
    root.classList.toggle("app-nav--has-submenu", collapsed);
    if (!collapsed) setOpen(false);
  };

  const measureTopbar = () => {
    const available = inlineNav.clientWidth;
    const collapsed = getInlineWidth() > available;
    setCollapsed(collapsed);
  };

  const measureSidebar = () => {
    const available = root.clientHeight - root.querySelector<HTMLElement>(".app-nav__brand")!.offsetHeight - 28;
    const collapsed = getInlineHeight() > available;
    setCollapsed(collapsed);
  };

  const measure = () => {
    if (isSidebar) {
      measureSidebar();
      return;
    }
    measureTopbar();
  };

  toggle.addEventListener("click", () => {
    setOpen(!root.classList.contains("app-nav--open"));
  });

  document.addEventListener("click", (event) => {
    if (!root.contains(event.target as Node)) setOpen(false);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") setOpen(false);
  });

  let rafId = 0;
  const scheduleMeasure = () => {
    if (rafId) return;
    rafId = window.requestAnimationFrame(() => {
      rafId = 0;
      measure();
    });
  };

  const resizeObserver = new ResizeObserver(() => scheduleMeasure());
  resizeObserver.observe(root);
  resizeObserver.observe(inlineNav);
  scheduleMeasure();
  document.fonts?.ready.then(scheduleMeasure).catch(() => undefined);
};

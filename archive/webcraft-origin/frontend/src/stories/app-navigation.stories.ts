import { webcraftConfig } from "../config";
import { AppNavigation, bindAppNavigation } from "../components/app-navigation";
import { renderAppNavigation } from "../components/app-navigation";
import { htmlStory } from "./storybook-html";

const storyRoutes = [
  { id: "home", label: "Home", href: "#/" },
  { id: "players", label: "Active Players", href: "#/players" },
  { id: "shop", label: "Marketplace", href: "#/shop" },
  { id: "stats", label: "Server Stats", href: "#/stats" },
  { id: "events", label: "Seasonal Events", href: "#/events" },
  { id: "about", label: "About WebCraft", href: "#/about" },
  { id: "support", label: "Support", href: "#/support" },
  { id: "rules", label: "Rules", href: "#/rules" }
];

export default {
  title: "WebCraft/Layout/AppNavigation",
  component: AppNavigation,
  parameters: {
    layout: "fullscreen"
  }
};

const navigationShell = (maxWidth: string, activeId: string) => `
  <div class="app-shell" style="max-width: ${maxWidth}; margin: 0 auto;">
    ${renderAppNavigation({
      brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
      items: storyRoutes,
      activeId
    })}
    <main class="main">
      <section class="panel hero">
        <p class="eyebrow">Overview</p>
        <h1>App navigation</h1>
        <p class="lede">Resize the viewport to watch items move into the submenu when the top bar runs out of space.</p>
      </section>
    </main>
  </div>
`;

const sidebarShell = (activeId: string) => `
  <div class="app-shell" style="display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 16px; max-width: 1440px; margin: 0 auto;">
    <div>
      ${renderAppNavigation({
        brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
        items: storyRoutes,
        activeId,
        variant: "sidebar"
      })}
    </div>
    <main class="main" style="width: 100%; margin: 16px 0 32px;">
      <section class="panel hero">
        <p class="eyebrow">Overview</p>
        <h1>App navigation</h1>
        <p class="lede">This layout keeps the navigation beside the content on wider screens.</p>
      </section>
    </main>
  </div>
`;

export const TopBar = {
  render: () =>
    htmlStory({
      html: navigationShell("1180px", "home"),
      setup: (root) => {
        const nav = root.querySelector<HTMLElement>("[data-app-nav]");
        if (nav) bindAppNavigation(nav);
      }
    })
};

export const SideBar = {
  render: () =>
    htmlStory({
      html: sidebarShell("players"),
      setup: (root) => {
        const nav = root.querySelector<HTMLElement>("[data-app-nav]");
        if (!nav) return;
        bindAppNavigation(nav);
      }
    })
};

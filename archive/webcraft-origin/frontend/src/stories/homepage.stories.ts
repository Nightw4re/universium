import { AppNavigation } from "../components/app-navigation";
import { webcraftConfig } from "../config";
import { bindAppNavigation, renderAppNavigation } from "../components/app-navigation";
import { DetailHero } from "../components/detail-hero";
import { ServerInfoPanel } from "../components/server-info-panel";
import { TopPlayers } from "../components/top-players";
import { renderDetailHero } from "../components/detail-hero";
import { renderServerInfoPanel } from "../components/server-info-panel";
import { renderTopPlayers } from "../components/top-players";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Layout/Homepage",
  component: AppNavigation,
  parameters: {
    layout: "fullscreen"
  }
};

export const Default = {
  render: () =>
    htmlStory({
      html: `
        <div class="app-shell">
          ${renderAppNavigation({
            brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
            items: webcraftConfig.routes,
            activeId: "home"
          })}
          <main class="main">
            <div class="demo-grid">
              ${renderServerInfoPanel({
                title: "Server info",
                rows: [
                  { label: "Players", value: "12 / 80" },
                  { label: "Uptime", value: "3d 12h" }
                ]
              })}
              ${renderDetailHero({
                name: "nightware6",
                playedTime: "12h 32m",
                combat: [
                  { label: "Armor", value: "18" },
                  { label: "Armor Toughness", value: "4" },
                  { label: "HP", value: "20" },
                  { label: "Hunger", value: "16" },
                  { label: "Saturation", value: "8.0" }
                ]
              })}
            </div>

          ${renderTopPlayers({
            title: "Top players",
            periodLabel: "All time",
            limit: 3,
              players: [
                { name: "nightware6", online: true, meta: "Played 2h 11m" },
                { name: "Dev", online: false, meta: "Played 48m" },
                { name: "Notch", online: false, meta: "Played 1d 03h" },
                { name: "Jeb_", online: false, meta: "Played 57m" }
              ]
            })}
          </main>
        </div>
      `
      ,
      setup: (root) => {
        const nav = root.querySelector<HTMLElement>("[data-app-nav]");
        if (nav) bindAppNavigation(nav);
      }
    })
};

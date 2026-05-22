import { PlayerSearch } from "../components/player-search";
import { bindPlayerSearch, renderPlayerSearch } from "../components/player-search";
import { renderPlayerCard } from "../components/player-card";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Composed/PlayerSearch",
  component: PlayerSearch,
  parameters: {}
};

export const Default = {
  render: () => {
    return htmlStory({
      html: `
        ${renderPlayerSearch()}
        <section class="panel" data-player-search-results>
          <p class="eyebrow">Matching players</p>
          <div class="placeholder-list">
            <div data-player-search-result data-player-search-name="nightware6" data-player-search-filter="online has-head verified">${renderPlayerCard({ name: "nightware6", meta: "Online now", online: true })}</div>
            <div data-player-search-result data-player-search-name="Dev" data-player-search-filter="offline verified">${renderPlayerCard({ name: "Dev", meta: "Offline profile", online: false })}</div>
            <div data-player-search-result data-player-search-name="CraftyFox" data-player-search-filter="online has-head">${renderPlayerCard({ name: "CraftyFox", meta: "Has head", online: true })}</div>
            <div data-player-search-result data-player-search-name="Ghost" data-player-search-filter="offline verified">${renderPlayerCard({ name: "Ghost", meta: "Verified", online: false })}</div>
          </div>
        </section>
      `,
      setup: (root) => {
        const search = root.querySelector<HTMLElement>("[data-player-search]");
        const input = root.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
        const selectBox = root.querySelector<HTMLElement>("[data-select-box]");
        const results = Array.from(root.querySelectorAll<HTMLElement>("[data-player-search-result]"));
        const syncResults = () => {
          const query = (input?.value ?? "").trim().toLowerCase();
          const filter = search?.dataset.playerSearchFilter ?? "all";
          const onlinePriority = search?.dataset.playerSearchOnlinePriority !== "false";
          const sort = search?.dataset.playerSearchSort ?? "Alphabetically [A-Z]";
          const visible = results.filter((result) => {
            const name = (result.dataset.playerSearchName ?? "").toLowerCase();
            const tags = (result.dataset.playerSearchFilter ?? "").toLowerCase();
            const matchesQuery = !query || name.includes(query);
            const matchesFilter = filter === "all" || tags.includes(filter);
            return matchesQuery && matchesFilter;
          });
          visible.sort((left, right) => {
            const leftOnline = (left.dataset.playerSearchFilter ?? "").includes("online");
            const rightOnline = (right.dataset.playerSearchFilter ?? "").includes("online");
            if (onlinePriority && leftOnline !== rightOnline) return leftOnline ? -1 : 1;
            const leftName = (left.dataset.playerSearchName ?? "").toLowerCase();
            const rightName = (right.dataset.playerSearchName ?? "").toLowerCase();
            const direction = sort === "Alphabetically [Z-A]" ? -1 : 1;
            return leftName.localeCompare(rightName) * direction;
          });
          const list = root.querySelector<HTMLElement>("[data-player-search-results] .placeholder-list");
          if (list) {
            visible.forEach((result) => list.appendChild(result));
          }
          results.forEach((result) => { result.hidden = !visible.includes(result); });
        };
        if (search) {
          bindPlayerSearch(search);
          search.addEventListener("player-search:change", syncResults);
        }
        selectBox?.addEventListener("select-box:change", syncResults);
        syncResults();
      }
    });
  }
};

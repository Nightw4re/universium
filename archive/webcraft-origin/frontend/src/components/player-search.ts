import { createElement, useEffect, useMemo, useRef, useState } from "react";
import { bindSelectBox, renderSelectBox } from "./select-box";
import { bindTextClearInput, renderTextClearInput } from "./text-clear-input";
import { renderPlayerCard, type PlayerCardData } from "./player-card";
import { renderReactMarkup } from "./react-render";

export type PlayerSearchFilter = "all" | "online" | "offline" | "has-head" | "verified";

export interface PlayerSearchProps {
  query?: string;
  filter?: PlayerSearchFilter;
  sort?: string;
  onlinePriority?: boolean;
}

export interface PlayerSearchSnapshot {
  playerName: string;
  online: boolean;
  lastOnlineLabel?: string;
  verifiedAccount?: boolean;
  hasHead?: boolean;
}

export interface PlayerSearchRuntimeProps extends PlayerSearchProps {
  endpoint?: string;
  emptyLabel?: string;
}

const snapshotToPlayer = (snapshot: PlayerSearchSnapshot): PlayerCardData => ({
  name: snapshot.playerName,
  online: snapshot.online,
  verified: snapshot.verifiedAccount,
  href: `#/player/${encodeURIComponent(snapshot.playerName)}`,
  meta: `${snapshot.online ? "Online now" : snapshot.lastOnlineLabel ?? "Offline profile"}${snapshot.verifiedAccount ? " · Verified" : ""}${snapshot.hasHead ? " · Has head" : ""}`
});

export const PlayerSearch = (props: PlayerSearchProps = {}) =>
  createElement(
    "section",
    {
      className: "panel player-search",
      "data-player-search": true,
      "data-player-search-query": props.query ?? "",
      "data-player-search-filter": props.filter ?? "all",
      "data-player-search-sort": props.sort ?? "Alphabetically [A-Z]",
      "data-player-search-online-priority": String(props.onlinePriority ?? true)
    },
    createElement("p", { className: "eyebrow" }, "Player search"),
    createElement("h1", null, "Search, filter, and sort players"),
    createElement(
      "div",
      { className: "player-search__controls" },
      createElement(
        "div",
        { className: "field" },
        createElement("span", null, "Search players"),
        createElement("span", { dangerouslySetInnerHTML: { __html: renderTextClearInput({ placeholder: "Search by name", value: props.query ?? "" }) } })
      ),
      createElement("span", {
        dangerouslySetInnerHTML: {
          __html: renderSelectBox({
            label: "Sort by",
            value: props.sort ?? "alphabetical",
            placeholder: "Alphabetically [A-Z]",
            searchable: false,
            showCheckboxes: true,
            menuAlign: "end",
            selected: props.sort ?? "alphabetical",
            options: [
              { label: "Online first", value: "online-priority", checked: true, group: "checkbox" },
              { label: "Alphabetically [A-Z]", value: "alphabetical", group: "option" },
              { label: "Alphabetically [Z-A]", value: "reverse-alphabetical", group: "option" }
            ]
          })
        }
      })
    ),
    createElement(
      "div",
      { className: "player-search__filters" },
      ["all", "online", "offline", "has-head", "verified"].map((filter) =>
        createElement(
          "button",
          { key: filter, className: `chip${(props.filter ?? "all") === filter ? " chip--active" : ""}`, "data-player-search-chip": filter },
          filter === "all" ? "All" : filter === "has-head" ? "Has head" : filter === "verified" ? "Verified" : filter.charAt(0).toUpperCase() + filter.slice(1)
        )
      )
    )
  );

export const renderPlayerSearch = (props: PlayerSearchProps = {}) => renderReactMarkup(createElement(PlayerSearch, props));

export const PlayerSearchRuntime = ({ endpoint = "/api/player-search", emptyLabel, ...props }: PlayerSearchRuntimeProps = {}) => {
  const [players, setPlayers] = useState<PlayerCardData[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState(props.query ?? "");
  const [filter, setFilter] = useState<PlayerSearchFilter>(props.filter ?? "all");
  const [sort, setSort] = useState(props.sort ?? "Alphabetically [A-Z]");
  const [onlinePriority, setOnlinePriority] = useState(props.onlinePriority ?? true);
  const controlsRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const controlsHost = controlsRef.current;
    if (!controlsHost) return;
    controlsHost.innerHTML = renderPlayerSearch({
      query,
      filter,
      sort,
      onlinePriority
    });
    const controls = controlsHost.querySelector<HTMLElement>("[data-player-search]");
    if (!controls) return;
    bindPlayerSearch(controls);
    const onChange = (event: Event) => {
      const detail = (event as CustomEvent<{ query: string; filter: PlayerSearchFilter; sort: string; onlinePriority: boolean }>).detail;
      setQuery(detail.query);
      setFilter(detail.filter);
      setSort(detail.sort);
      setOnlinePriority(detail.onlinePriority);
    };
    controls.addEventListener("player-search:change", onChange);
    return () => controls.removeEventListener("player-search:change", onChange);
  }, [filter, onlinePriority, query, sort]);

  useEffect(() => {
    const controller = new AbortController();
    const load = async () => {
      setLoading(true);
      try {
        const response = await fetch(endpoint, { signal: controller.signal, headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = (await response.json()) as { data?: PlayerSearchSnapshot[]; players?: PlayerSearchSnapshot[] } | PlayerSearchSnapshot[];
        const list = Array.isArray(payload)
          ? payload
          : Array.isArray(payload.data)
            ? payload.data
            : Array.isArray(payload.players)
              ? payload.players
              : [];
        setPlayers(list.map(snapshotToPlayer));
      } catch {
        setPlayers([]);
      } finally {
        setLoading(false);
      }
    };
    void load();
    return () => controller.abort();
  }, [endpoint]);

  const filtered = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    const visible = players.filter((player) => {
      const matchesQuery = !normalizedQuery || player.name.toLowerCase().includes(normalizedQuery);
      const matchesFilter =
        filter === "all" ||
        (filter === "online" && player.online) ||
        (filter === "offline" && !player.online) ||
        (filter === "has-head" && (player.meta ?? "").toLowerCase().includes("has head")) ||
        (filter === "verified" && player.verified === true);
      return matchesQuery && matchesFilter;
    });
    visible.sort((left, right) => {
      if (onlinePriority && left.online !== right.online) return left.online ? -1 : 1;
      const direction = sort === "Alphabetically [Z-A]" ? -1 : 1;
      return left.name.localeCompare(right.name) * direction;
    });
    return visible;
  }, [filter, onlinePriority, players, query, sort]);

  return createElement(
    "section",
    { className: "panel player-search", "data-player-search-runtime": true },
    createElement("div", { ref: controlsRef }),
    createElement(
      "section",
      { className: "panel", "data-player-search-results": true },
      createElement("p", { className: "eyebrow" }, "Matching players"),
      createElement(
        "div",
        { className: "placeholder-list" },
        loading
          ? createElement("div", { className: "empty-state" }, "Loading players...")
          : filtered.length
            ? filtered.map((player) => createElement("span", { key: player.name, dangerouslySetInnerHTML: { __html: renderPlayerCard(player) } }))
            : createElement("div", { className: "empty-state" }, emptyLabel ?? "No players match")
      )
    )
  );
};

export const bindPlayerSearch = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";
  const input = root.querySelector<HTMLInputElement>("[data-text-clear-input-field]");
  const searchRoot = root.querySelector<HTMLElement>("[data-text-clear-input]");
  const selectBox = root.querySelector<HTMLElement>("[data-select-box]");
  const chips = Array.from(root.querySelectorAll<HTMLButtonElement>("[data-player-search-chip]"));
  const emit = () =>
    root.dispatchEvent(
      new CustomEvent("player-search:change", {
        bubbles: true,
        detail: {
          query: input?.value ?? "",
          filter: root.dataset.playerSearchFilter ?? "all",
          sort: root.dataset.playerSearchSort ?? "Alphabetically [A-Z]",
          onlinePriority: root.dataset.playerSearchOnlinePriority !== "false"
        }
      })
    );
  input?.addEventListener("input", () => {
    root.dataset.playerSearchQuery = input.value;
    emit();
  });
  if (searchRoot) {
    bindTextClearInput(searchRoot);
    searchRoot.addEventListener("text-clear-input:change", (event) => {
      const detail = (event as CustomEvent<{ value: string }>).detail;
      root.dataset.playerSearchQuery = detail.value;
      if (input && input.value !== detail.value) input.value = detail.value;
      emit();
    });
  }
  if (selectBox) bindSelectBox(selectBox);
  chips.forEach((chip) =>
    chip.addEventListener("click", () => {
      const filter = chip.dataset.playerSearchChip ?? "all";
      root.dataset.playerSearchFilter = filter;
      chips.forEach((other) => other.classList.toggle("chip--active", other === chip));
      emit();
    })
  );
  selectBox?.addEventListener("select-box:change", (event) => {
    const detail = (event as CustomEvent<{ value: string; checked?: string[] }>).detail;
    root.dataset.playerSearchSort = detail.value;
    root.dataset.playerSearchOnlinePriority = String((detail.checked ?? []).includes("online-priority"));
    emit();
  });
};

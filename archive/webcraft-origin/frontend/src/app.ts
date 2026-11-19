import { createElement, useEffect, useRef, useState } from "react";
import { HashRouter, Navigate, Route, Routes, useLocation, useParams } from "react-router-dom";
import { webcraftConfig, type Route as AppRoute } from "./config";
import { AppNavigation, bindAppNavigation } from "./components/app-navigation";
import { PlayerSearchRuntime } from "./components/player-search";
import { PlayerDetail } from "./components/player-detail";
import { ServerInfoPanel } from "./components/server-info-panel";
import { TopPlayers } from "./components/top-players";
import { renderReactMarkup } from "./components/react-render";
import { APP_CONTRACT_ERROR_EVENT } from "./components/runtime-contract";
import { hydrateModelTemplateTextures, hydrateModelTextures, renderItemIcon } from "./components/item-render";

const navigationItems = webcraftConfig.routes;

const renderHome = () =>
  createElement(
    "div",
    { className: "home-grid" },
    createElement(TopPlayers, {
      title: "Top players",
      periodLabel: "All time",
      limit: 3,
      endpoint: "/api/top-players"
    }),
    createElement(ServerInfoPanel, {
      title: "Server info"
    })
  );

const renderPlayers = () =>
  createElement("div", { className: "players-page" }, createElement(PlayerSearchRuntime, { endpoint: "/api/player-search" }));

const ShieldModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const params = new URLSearchParams(window.location.search);
  const debugRotateY = Number(params.get("rotateY") ?? "0");
  const debugNoTexture = params.get("textured") !== "1";
  const debugUseDisplayTransform = params.get("display") === "1";
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-shield-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Shield model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:shield",
            displayName: "Shield",
            renderMode: "model",
            modelId: "builtin/entity",
            debugNoTexture,
            debugRotateY,
            debugUseDisplayTransform
          })
        }
      })
    )
  );
};

const SpawnerModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTemplateTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-spawner-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Spawner model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:spawner",
            displayName: "Spawner",
            kind: "block",
            renderMode: "block",
            modelId: "minecraft:block/cube_all",
            texturePath: "/mc-assets/textures/block/spawner.png"
          })
        }
      })
    )
  );
};

const StairsModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTemplateTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-stairs-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Stairs model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:deepslate_tile_stairs",
            displayName: "Deepslate Tile Stairs",
            kind: "block",
            renderMode: "block",
            modelId: "minecraft:block/stairs"
          })
        }
      })
    )
  );
};

const SculkSensorModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTemplateTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-sculk-sensor-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Sculk sensor model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:sculk_sensor",
            displayName: "Sculk Sensor",
            kind: "block",
            renderMode: "block",
            modelId: "minecraft:block/sculk_sensor"
          })
        }
      })
    )
  );
};

const CraftingTableModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTemplateTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-crafting-table-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Crafting table model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:crafting_table",
            displayName: "Crafting Table",
            renderMode: "model",
            modelId: "minecraft:block/crafting_table",
            texturePaths: [
              "/mc-assets/textures/block/oak_planks.png",
              "/mc-assets/textures/block/crafting_table_side.png",
              "/mc-assets/textures/block/crafting_table_front.png",
              "/mc-assets/textures/block/crafting_table_top.png"
            ],
            textureLayers: {
              down: "/mc-assets/textures/block/oak_planks.png",
              east: "/mc-assets/textures/block/crafting_table_side.png",
              north: "/mc-assets/textures/block/crafting_table_front.png",
              south: "/mc-assets/textures/block/crafting_table_side.png",
              up: "/mc-assets/textures/block/crafting_table_top.png",
              west: "/mc-assets/textures/block/crafting_table_front.png"
            },
            faceMap: []
          })
        }
      })
    )
  );
};

const BannerModelDebug = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    void hydrateModelTextures(root);
  }, []);
  return createElement(
    "section",
    { className: "panel shield-model-debug", "data-banner-model-debug": true },
    createElement("p", { className: "eyebrow" }, "Debug"),
    createElement("h1", null, "Banner model only"),
    createElement(
      "div",
      { className: "shield-model-debug__frame", ref: rootRef },
      createElement("div", {
        className: "shield-model-debug__icon",
        dangerouslySetInnerHTML: {
          __html: renderItemIcon({
            id: "minecraft:red_banner",
            displayName: "Red Banner",
            renderMode: "model",
            modelId: "builtin/entity",
            texturePaths: ["/mc-assets/textures/block/oak_planks.png"],
            textureLayers: { particle: "/mc-assets/textures/block/oak_planks.png" }
          })
        }
      })
    )
  );
};

const renderRoute = (pathname: string) => {
  if (pathname === "/debug/shield-model") {
    return createElement(ShieldModelDebug);
  }
  if (pathname === "/debug/spawner-model") {
    return createElement(SpawnerModelDebug);
  }
  if (pathname === "/debug/stairs-model") {
    return createElement(StairsModelDebug);
  }
  if (pathname === "/debug/sculk-sensor-model") {
    return createElement(SculkSensorModelDebug);
  }
  if (pathname === "/debug/crafting-table-model") {
    return createElement(CraftingTableModelDebug);
  }
  if (pathname === "/debug/banner-model") {
    return createElement(BannerModelDebug);
  }
  if (pathname.startsWith("/player/")) {
    const name = decodeURIComponent(pathname.slice("/player/".length)) || "Unknown";
    return createElement(PlayerDetail, { playerName: name, endpointBase: "/api" });
  }
  if (pathname === "/players") return renderPlayers();
  return renderHome();
};

const PlayerDetailRoute = () => {
  const { name } = useParams<{ name: string }>();
  return createElement(PlayerDetail, { playerName: name ?? "Unknown", endpointBase: "/api" });
};

const AppShell = () => {
  const location = useLocation();
  const [contractError, setContractError] = useState<string | null>(null);
  const activeRoute: AppRoute = location.pathname === "/players" || location.pathname.startsWith("/player/") ? "players" : "home";
  useEffect(() => {
    const nav = document.querySelector<HTMLElement>("[data-app-nav]");
    if (nav) bindAppNavigation(nav);
  }, [location.pathname]);
  useEffect(() => {
    const onContractError = (event: Event) => {
      const detail = (event as CustomEvent<{ message?: string }>).detail;
      setContractError(detail?.message ?? "Backend response does not match the frontend contract.");
    };
    window.addEventListener(APP_CONTRACT_ERROR_EVENT, onContractError);
    return () => window.removeEventListener(APP_CONTRACT_ERROR_EVENT, onContractError);
  }, []);
  if (contractError) {
    return createElement(
      "div",
      { className: "app-shell" },
      createElement(
        "main",
        { className: "main" },
        createElement(
          "section",
          { className: "panel" },
          createElement("h1", null, "Frontend update required"),
          createElement("p", null, contractError)
        )
      )
    );
  }
  return createElement(
    "div",
    { className: "app-shell" },
    createElement(AppNavigation, {
      brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
      items: navigationItems,
      activeId: activeRoute
    }),
    createElement(
      "main",
      { className: "main" },
      createElement(
        Routes,
        null,
        createElement(Route, { path: "/debug/shield-model", element: createElement(ShieldModelDebug) }),
        createElement(Route, { path: "/debug/spawner-model", element: createElement(SpawnerModelDebug) }),
        createElement(Route, { path: "/debug/stairs-model", element: createElement(StairsModelDebug) }),
        createElement(Route, { path: "/debug/sculk-sensor-model", element: createElement(SculkSensorModelDebug) }),
        createElement(Route, { path: "/debug/crafting-table-model", element: createElement(CraftingTableModelDebug) }),
        createElement(Route, { path: "/debug/banner-model", element: createElement(BannerModelDebug) }),
        createElement(Route, { path: "/", element: renderHome() }),
        createElement(Route, { path: "/players", element: renderPlayers() }),
        createElement(Route, { path: "/player/:name", element: createElement(PlayerDetailRoute) }),
        createElement(Route, { path: "*", element: createElement(Navigate, { to: "/" }) })
      )
    )
  );
};

export const App = () => createElement(HashRouter, null, createElement(AppShell));

export const createApp = (hash = window.location.hash) => {
  const normalized = hash.replace(/^#\/?/, "").replace(/\/+$/, "");
  const activeRoute: AppRoute = normalized.startsWith("player/") || normalized === "players" ? "players" : "home";
  return renderReactMarkup(
    createElement(
      "div",
      { className: "app-shell" },
      createElement(AppNavigation, {
        brand: { mark: "WC", title: webcraftConfig.title, subtitle: webcraftConfig.subtitle },
        items: navigationItems,
        activeId: activeRoute
      }),
      createElement("main", { className: "main" }, renderRoute(`/${normalized}`))
    )
  );
};

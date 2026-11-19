import { createElement, Fragment, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Tooltip, bindTooltipHover, computeTooltipPosition } from "./tooltip";
import { renderReactMarkup } from "./react-render";
import { dispatchAppContractError } from "./runtime-contract";

export interface ServerInfoSnapshot {
  serverName: string;
  online: boolean;
  minecraftVersion: string;
  neoforgeBuild: number;
  host: string;
  port: number;
  publicUrl: string;
  uptimeSeconds: number;
  resourcePackCount?: number;
  totalPlayersEver?: number;
}

export interface ServerInfoPanelProps {
  title: string;
  rows: Array<{ label: string; value: string }>;
  state?: "idle" | "loading" | "refreshing" | "cooldown";
  cooldownSeconds?: number;
  loadingRows?: number;
  onRefresh?: () => void;
  onCancel?: () => void;
  tooltipVisible?: boolean;
  tooltipPosition?: { left: number; top: number };
  onTooltipEnter?: () => void;
  onTooltipMove?: (clientX: number, clientY: number) => void;
  onTooltipLeave?: () => void;
  refreshButtonRef?: { current: HTMLButtonElement | null };
  tooltipRef?: { current: HTMLDivElement | null };
  tooltipHost?: HTMLElement | null;
}

export interface ServerInfoPanelRuntimeProps {
  title: string;
  endpoint?: string;
}

type ServerInfoPayload =
  | { success?: boolean; data?: ServerInfoSnapshot }
  | ServerInfoSnapshot
  | {
      server?: ServerInfoSnapshot;
      serverInfo?: ServerInfoSnapshot;
      info?: ServerInfoSnapshot;
      data?: ServerInfoSnapshot;
    };

const formatUptime = (seconds: number) => {
  const total = Math.max(0, Math.floor(seconds));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) return `${days}d ${String(hours).padStart(2, "0")}h`;
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  return `${minutes}m`;
};

const snapshotToRows = (snapshot: ServerInfoSnapshot) => [
  { label: "Server", value: snapshot.serverName },
  { label: "Status", value: snapshot.online ? "Online" : "Offline" },
  { label: "Version", value: snapshot.minecraftVersion },
  { label: "Uptime", value: formatUptime(snapshot.uptimeSeconds) },
  ...(snapshot.resourcePackCount !== undefined ? [{ label: "Resource packs", value: String(snapshot.resourcePackCount) }] : []),
  ...(snapshot.totalPlayersEver !== undefined ? [{ label: "Players ever", value: String(snapshot.totalPlayersEver) }] : [])
];

const isServerInfoSnapshot = (value: unknown): value is ServerInfoSnapshot => {
  if (typeof value !== "object" || value === null) return false;
  const snapshot = value as Partial<ServerInfoSnapshot>;
  return (
    typeof snapshot.serverName === "string" &&
    typeof snapshot.online === "boolean" &&
    typeof snapshot.minecraftVersion === "string" &&
    typeof snapshot.neoforgeBuild === "number" &&
    typeof snapshot.host === "string" &&
    typeof snapshot.port === "number" &&
    typeof snapshot.publicUrl === "string" &&
    typeof snapshot.uptimeSeconds === "number" &&
    (snapshot.resourcePackCount === undefined || typeof snapshot.resourcePackCount === "number") &&
    (snapshot.totalPlayersEver === undefined || typeof snapshot.totalPlayersEver === "number")
  );
};

const normalizeServerInfoSnapshot = (payload: ServerInfoPayload): ServerInfoSnapshot | null => {
  if (isServerInfoSnapshot(payload)) return payload;
  if ("success" in payload && payload.success === true && isServerInfoSnapshot(payload.data)) return payload.data;
  if ("server" in payload && isServerInfoSnapshot(payload.server)) return payload.server;
  if ("serverInfo" in payload && isServerInfoSnapshot(payload.serverInfo)) return payload.serverInfo;
  if ("info" in payload && isServerInfoSnapshot(payload.info)) return payload.info;
  if ("data" in payload && isServerInfoSnapshot(payload.data)) return payload.data;
  return null;
};

export const ServerInfoPanelView = (props: ServerInfoPanelProps) =>
  createElement(
    "section",
    {
      className: `panel server-info-panel${props.state ? ` server-info-panel--${props.state}` : ""}`,
      "data-server-info-panel": true,
      "data-server-info-state": props.state ?? "idle"
    },
    createElement(
      "div",
      { className: "panel__head" },
      createElement("div", null, createElement("p", { className: "eyebrow" }, props.title), createElement("h2", null, "Server status")),
      createElement(
        "div",
        { className: "server-info-panel__actions" },
        createElement(
          "button",
          {
            className: "icon-button icon-button--refresh",
            type: "button",
            "aria-label": "Refresh",
            "data-server-info-refresh": true,
            ref: props.refreshButtonRef,
            onClick: props.onRefresh,
            onMouseEnter: props.onTooltipEnter,
            onMouseMove: (event) => props.onTooltipMove?.((event as React.MouseEvent).clientX, (event as React.MouseEvent).clientY),
            onMouseLeave: props.onTooltipLeave
          },
          props.state === "loading" || props.state === "refreshing"
            ? createElement("span", { className: "icon-button__loader", "aria-hidden": "true" })
            : createElement("span", { className: "icon-button__glyph", "aria-hidden": "true" }, "↻")
        ),
        props.state === "refreshing"
          ? createElement(
              "button",
              {
                className: "icon-button icon-button--ghost",
                type: "button",
                "aria-label": "Cancel",
                "data-server-info-cancel": true,
                onClick: props.onCancel
              },
              createElement("span", { className: "icon-button__glyph", "aria-hidden": "true" }, "✕")
            )
          : null
      )
    ),
    createElement(
      "div",
      { className: "server-info-panel__rows" },
      props.state === "loading"
        ? Array.from({ length: props.loadingRows ?? props.rows.length }, (_, index) =>
            createElement(
              "div",
              { className: "stat-row stat-row--loading", key: `loading-${index}` },
              createElement("span", { className: "stat-row__skeleton" }),
              createElement("strong", { className: "stat-row__skeleton" })
            )
          )
        : props.rows.map((row) => createElement("div", { className: "stat-row", key: row.label }, createElement("span", null, row.label), createElement("strong", null, row.value)))
    )
  );

export const ServerInfoPanel = ({ title, endpoint = "/api/server-info" }: ServerInfoPanelRuntimeProps) => {
  const [rows, setRows] = useState<Array<{ label: string; value: string }>>([]);
  const [state, setState] = useState<"idle" | "loading" | "refreshing" | "cooldown">("loading");
  const [cooldownSeconds, setCooldownSeconds] = useState(10);
  const [loadingRows, setLoadingRows] = useState(5);
  const abortRef = useRef<AbortController | null>(null);
  const cooldownTimerRef = useRef<number | null>(null);
  const refreshButtonRef = useRef<HTMLButtonElement | null>(null);
  const tooltipRef = useRef<HTMLDivElement | null>(null);
  const [tooltipHost, setTooltipHost] = useState<HTMLElement | null>(null);
  const [tooltipVisible, setTooltipVisible] = useState(false);
  const [tooltipPosition, setTooltipPosition] = useState({ left: 0, top: 0 });

  const clearCooldownTimer = () => {
    if (cooldownTimerRef.current !== null) {
      window.clearTimeout(cooldownTimerRef.current);
      cooldownTimerRef.current = null;
    }
  };

  const startCooldown = () => {
    clearCooldownTimer();
    setState("cooldown");
    setCooldownSeconds(10);
    setTooltipVisible(false);
    const tick = (remaining: number) => {
      if (remaining <= 1) {
        setState("idle");
        setTooltipVisible(false);
        clearCooldownTimer();
        return;
      }
      setCooldownSeconds(remaining - 1);
      cooldownTimerRef.current = window.setTimeout(() => tick(remaining - 1), 1000);
    };
    cooldownTimerRef.current = window.setTimeout(() => tick(10), 1000);
  };

  const load = async (mode: "initial" | "refresh" = "initial") => {
    clearCooldownTimer();
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    if (mode === "initial") {
      setState("loading");
      setLoadingRows(5);
    } else {
      setState("refreshing");
    }

    try {
      const response = await fetch(endpoint, { signal: controller.signal, headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = (await response.json()) as ServerInfoPayload;
      const snapshot = normalizeServerInfoSnapshot(payload);
      if (!snapshot) {
        const message = `Backend server-info payload does not match the expected contract at ${endpoint}.`;
        dispatchAppContractError(message);
        throw new Error("Invalid server info payload");
      }
      setRows(snapshotToRows(snapshot));
      startCooldown();
    } catch (error) {
      if ((error as Error).name === "AbortError") return;
      console.error(`Failed to load server info from ${endpoint}:`, error);
      setState("idle");
      setTooltipVisible(false);
    }
  };

  useEffect(() => {
    void load("initial");
    setTooltipHost(document.querySelector<HTMLElement>(".app-shell"));
    return () => {
      abortRef.current?.abort();
      clearCooldownTimer();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [endpoint]);

  const tooltip = state === "cooldown" && tooltipHost
    ? createPortal(
      createElement(Tooltip, {
        descriptor: {
          title: "Refresh",
          description: `Refreshing is available again in ${cooldownSeconds}s.`
        },
        options: {
          visible: tooltipVisible,
          position: tooltipPosition,
          strategy: "absolute"
        },
        ref: tooltipRef
      }),
      tooltipHost
    )
    : null;

  return createElement(
    Fragment,
    null,
    createElement(ServerInfoPanelView, {
      title,
      state,
      cooldownSeconds,
      loadingRows,
      rows,
      refreshButtonRef,
      tooltipRef,
      onRefresh: () => {
        if (state !== "idle") return;
        void load("refresh");
      },
      onCancel: () => {
        if (state !== "refreshing") return;
        abortRef.current?.abort();
        clearCooldownTimer();
        setState("idle");
        setTooltipVisible(false);
      },
      onTooltipEnter: () => {
        if (state === "cooldown") {
          setTooltipVisible(true);
          const refreshRect = refreshButtonRef.current?.getBoundingClientRect();
          const tooltipHostNode = tooltipHost ?? document.querySelector<HTMLElement>(".app-shell");
          const hostRect = tooltipHostNode?.getBoundingClientRect();
          if (refreshRect) {
            const anchorX = hostRect ? refreshRect.left - hostRect.left + refreshRect.width / 2 : refreshRect.left + refreshRect.width / 2;
            const anchorY = hostRect ? refreshRect.top - hostRect.top + refreshRect.height / 2 : refreshRect.top + refreshRect.height / 2;
            const tooltipNode = tooltipRef.current;
            const width = tooltipNode?.getBoundingClientRect().width || 320;
            const height = tooltipNode?.getBoundingClientRect().height || 160;
            const bounds = hostRect
              ? { left: 0, top: 0, width: hostRect.width, height: hostRect.height }
              : { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight };
            const { left, top } = computeTooltipPosition(anchorX, anchorY, width, height, bounds, 12);
            setTooltipPosition({ left, top });
          }
        }
      },
      onTooltipMove: (clientX, clientY) => {
        if (state !== "cooldown") return;
        const tooltipNode = tooltipRef.current;
        const tooltipHostNode = tooltipHost ?? document.querySelector<HTMLElement>(".app-shell");
        const hostRect = tooltipHostNode?.getBoundingClientRect();
        const width = tooltipNode?.getBoundingClientRect().width || 320;
        const height = tooltipNode?.getBoundingClientRect().height || 160;
        const bounds = hostRect
          ? { left: 0, top: 0, width: hostRect.width, height: hostRect.height }
          : { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight };
        const anchorX = hostRect ? clientX - hostRect.left : clientX;
        const anchorY = hostRect ? clientY - hostRect.top : clientY;
        const { left, top } = computeTooltipPosition(
          anchorX,
          anchorY,
          width,
          height,
          bounds,
          12
        );
        setTooltipPosition({ left, top });
      },
      onTooltipLeave: () => {
        if (state === "cooldown") setTooltipVisible(false);
      }
    }),
    tooltip
  );
};

export const renderServerInfoPanel = (props: ServerInfoPanelProps) => renderReactMarkup(createElement(ServerInfoPanelView, props));

export const bindServerInfoPanel = (root: HTMLElement) => {
  if (root.dataset.bound === "true") return;
  root.dataset.bound = "true";

  const refresh = root.querySelector<HTMLButtonElement>("[data-server-info-refresh]");
  const cancel = root.querySelector<HTMLButtonElement>("[data-server-info-cancel]");
  const stateLabel = root.dataset.serverInfoState ?? "idle";
  let state: "idle" | "loading" | "refreshing" | "cooldown" = stateLabel === "loading" || stateLabel === "refreshing" || stateLabel === "cooldown" ? stateLabel : "idle";
  let cooldownSeconds = 10;
  let timer: number | undefined;

  const syncState = () => {
    root.dataset.serverInfoState = state;
    root.classList.toggle("server-info-panel--loading", state === "loading");
    root.classList.toggle("server-info-panel--refreshing", state === "refreshing");
    root.classList.toggle("server-info-panel--cooldown", state === "cooldown");
    if (refresh) refresh.disabled = state === "loading" || state === "refreshing";
    if (cancel) cancel.hidden = state !== "refreshing";
  };

  const clearTimer = () => {
    if (typeof timer === "number") window.clearTimeout(timer);
    timer = undefined;
  };

  const startCooldown = () => {
    clearTimer();
    state = "cooldown";
    cooldownSeconds = 10;
    syncState();
    const tick = () => {
      if (cooldownSeconds <= 1) {
        state = "idle";
        syncState();
        clearTimer();
        return;
      }
      cooldownSeconds -= 1;
      syncState();
      timer = window.setTimeout(tick, 1000);
    };
    timer = window.setTimeout(tick, 1000);
  };

  const startRefresh = () => {
    clearTimer();
    state = "loading";
    syncState();
    timer = window.setTimeout(() => {
      state = "refreshing";
      syncState();
      timer = window.setTimeout(startCooldown, 1500);
    }, 800);
  };

  refresh?.addEventListener("click", () => {
    if (state !== "idle") return;
    startRefresh();
  });

  cancel?.addEventListener("click", () => {
    if (state !== "refreshing") return;
    clearTimer();
    state = "idle";
    syncState();
  });

  syncState();
  if (refresh) bindTooltipHover(refresh);
};

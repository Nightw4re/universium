import { createElement, useEffect, useRef, useState } from "react";
import { renderPlayerCard, type PlayerCardData } from "./player-card";
import { renderReactMarkup } from "./react-render";

export interface TopPlayerSnapshot {
  playerName: string;
  playedTimeTicks: number;
  playedTimeLabel: string;
  lastOnlineMillis: number;
  lastOnlineLabel: string;
  verifiedAccount: boolean;
}

export interface TopPlayersProps {
  title: string;
  periodLabel: string;
  players: PlayerCardData[];
  limit: number;
  emptyLabel?: string;
}

export interface TopPlayersRuntimeProps {
  title: string;
  periodLabel: string;
  limit: number;
  endpoint?: string;
  emptyLabel?: string;
}

const snapshotToPlayerCard = (snapshot: TopPlayerSnapshot): PlayerCardData => ({
  name: snapshot.playerName,
  verified: snapshot.verifiedAccount,
  href: `#/player/${encodeURIComponent(snapshot.playerName)}`,
  meta: `Played ${snapshot.playedTimeLabel}${snapshot.lastOnlineLabel ? ` · ${snapshot.lastOnlineLabel}` : ""}${snapshot.verifiedAccount ? " · Verified" : ""}`,
  online: snapshot.lastOnlineLabel.toLowerCase().includes("online")
});

export const TopPlayersView = (props: TopPlayersProps) => {
  const list = props.players.slice(0, props.limit);
  return createElement(
    "section",
    { className: "panel top-players" },
    createElement("div", { className: "panel__head" }, createElement("div", null, createElement("p", { className: "eyebrow" }, props.title), createElement("h2", null, props.periodLabel))),
    createElement(
      "div",
      { className: "top-players__list" },
      list.length
        ? list.map((player) => createElement("span", { key: player.name, dangerouslySetInnerHTML: { __html: renderPlayerCard(player) } }))
        : createElement("div", { className: "empty-state" }, props.emptyLabel ?? "No players yet")
    )
  );
};

export const TopPlayers = ({ title, periodLabel, limit, endpoint = "/api/top-players", emptyLabel }: TopPlayersRuntimeProps) => {
  const [players, setPlayers] = useState<PlayerCardData[]>([]);
  const [loading, setLoading] = useState(true);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const load = async () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      try {
        const response = await fetch(endpoint, { signal: controller.signal, headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = (await response.json()) as
          | { success?: boolean; data?: TopPlayerSnapshot[] }
          | TopPlayerSnapshot[]
          | { players?: TopPlayerSnapshot[] };
        const list =
          Array.isArray(payload)
            ? payload
            : Array.isArray((payload as { data?: TopPlayerSnapshot[] }).data)
              ? (payload as { data?: TopPlayerSnapshot[] }).data ?? []
              : Array.isArray((payload as { players?: TopPlayerSnapshot[] }).players)
                ? (payload as { players?: TopPlayerSnapshot[] }).players ?? []
                : [];
        setPlayers(list.map(snapshotToPlayerCard));
      } catch {
        setPlayers([]);
      } finally {
        setLoading(false);
      }
    };

    void load();
    return () => abortRef.current?.abort();
  }, [endpoint]);

  return createElement(TopPlayersView, {
    title,
    periodLabel,
    limit,
    players,
    emptyLabel: loading ? "Loading top players..." : emptyLabel
  });
};

export const renderTopPlayers = (props: TopPlayersProps) => renderReactMarkup(createElement(TopPlayersView, props));

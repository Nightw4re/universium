import { renderReactMarkup } from "./react-render";
import { createElement, type CSSProperties } from "react";

export interface PlayerHeadProps {
  name: string;
  avatar?: string;
  online?: boolean;
  size?: "sm" | "md" | "lg";
  statusColor?: string;
  statusFill?: "match" | "black";
}

const avatarUrl = (name: string) => `https://mc-heads.net/avatar/${encodeURIComponent(name)}`;

export const PlayerHead = (props: PlayerHeadProps) => (
  createElement(
    "div",
    {
      className: `player-head player-head--${props.size ?? "md"}${props.online ? " player-head--online" : " player-head--offline"}`,
      style: {
        "--player-head-status-color": props.statusColor ?? "#4f8f4c",
        "--player-head-status-fill": props.statusFill ?? (props.online ? "var(--player-head-status-color)" : "#000000")
      } as CSSProperties
    },
    createElement("img", { src: props.avatar ?? avatarUrl(props.name), alt: props.name, loading: "lazy" }),
    createElement("span", { className: "player-head__status", "aria-hidden": "true" })
  )
);

export const renderPlayerHead = (props: PlayerHeadProps) => `
  ${renderReactMarkup(createElement(PlayerHead, props))}
`;

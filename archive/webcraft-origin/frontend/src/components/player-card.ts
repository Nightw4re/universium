import { createElement } from "react";
import { renderPlayerHead } from "./player-head";
import { renderReactMarkup } from "./react-render";

export interface PlayerCardData {
  name: string;
  online: boolean;
  verified?: boolean;
  meta?: string;
  avatar?: string;
  href?: string;
}

export const PlayerCard = (data: PlayerCardData) =>
  createElement(
    data.href ? "a" : "article",
    {
      className: "player-card",
      href: data.href
    },
    createElement("span", {
      dangerouslySetInnerHTML: { __html: renderPlayerHead({ name: data.name, avatar: data.avatar, online: data.online, size: "md" }) }
    }),
    createElement(
      "div",
      { className: "player-card__body" },
      createElement("div", { className: "player-card__name" }, data.name),
      data.meta ? createElement("div", { className: "player-card__meta" }, data.meta) : null
    ),
    createElement(
      "div",
      { className: `player-card__status${data.online ? " player-card__status--online" : ""}` },
      data.online ? "Online now" : data.verified ? "Verified" : "Offline profile"
    )
  );

export const renderPlayerCard = (data: PlayerCardData) => `
  ${renderReactMarkup(PlayerCard(data))}
`;

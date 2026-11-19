import { createElement } from "react";
import { renderPlayerHead } from "./player-head";
import { renderReactMarkup } from "./react-render";

export interface DetailHeroProps {
  name: string;
  playedTime?: string;
  avatar?: string;
  combat?: Array<{ label: string; value: string }>;
  armor?: string;
  armorToughness?: string;
  hp?: string;
  hunger?: string;
  saturation?: string;
}

export const DetailHero = (props: DetailHeroProps) =>
  createElement(
    "section",
    { className: "panel detail-hero" },
    createElement(
      "div",
      { className: "detail-hero__identity" },
      createElement("span", { dangerouslySetInnerHTML: { __html: renderPlayerHead({ name: props.name, avatar: props.avatar, size: "lg" }) } }),
      createElement(
        "div",
        null,
        createElement("p", { className: "eyebrow" }, "Player detail"),
        createElement("h1", { className: "detail-hero__name" }, props.name),
        props.playedTime ? createElement("p", { className: "detail-hero__time" }, props.playedTime) : null
      )
    ),
    createElement(
      "div",
      { className: "detail-hero__table" },
      (props.combat ?? [
        { label: "Armor", value: props.armor ?? "0" },
        { label: "Armor Toughness", value: props.armorToughness ?? "0" },
        { label: "HP", value: props.hp ?? "0" },
        { label: "Hunger", value: props.hunger ?? "0" },
        { label: "Saturation", value: props.saturation ?? "0" }
      ]).map((row) => createElement("div", { className: "stat-row", key: row.label }, createElement("span", null, row.label), createElement("strong", null, row.value)))
    )
  );

export const renderDetailHero = (props: DetailHeroProps) => renderReactMarkup(createElement(DetailHero, props));

import { renderDetailHero } from "../components/detail-hero";
import { DetailHero } from "../components/detail-hero";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Composed/DetailHero",
  component: DetailHero,
  parameters: {}
};

export const Default = {
  render: () =>
    htmlStory({
      html: renderDetailHero({
        name: "nightware6",
        playedTime: "Played 2d 04h",
        combat: [
          { label: "Armor", value: "18" },
          { label: "Armor Toughness", value: "4" },
          { label: "HP", value: "20" },
          { label: "Hunger", value: "20" },
          { label: "Saturation", value: "12.8" }
        ]
      })
    })
};

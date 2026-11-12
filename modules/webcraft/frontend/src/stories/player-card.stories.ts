import { PlayerCard } from "../components/player-card";
import { renderPlayerCard } from "../components/player-card";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Simple/PlayerCard",
  component: PlayerCard,
  parameters: {}
};

export const Nightware6 = {
  render: () =>
    htmlStory({
      html: renderPlayerCard({
        name: "nightware6",
        meta: "Online now",
        online: true
      })
    })
};

export const Dev = {
  render: () =>
    htmlStory({
      html: renderPlayerCard({
        name: "Dev",
        meta: "Verified",
        online: false
      })
    })
};

import { renderPlayerHead } from "../components/player-head";
import { PlayerHead } from "../components/player-head";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Simple/PlayerHead",
  component: PlayerHead,
  parameters: {}
};

export const Online = {
  render: () =>
    htmlStory({
      html: renderPlayerHead({
        name: "nightware6",
        online: true,
        size: "lg",
        statusColor: "#4f8f4c"
      })
    })
};

export const Offline = {
  render: () =>
    htmlStory({
      html: renderPlayerHead({
        name: "nightware6",
        online: false,
        size: "lg",
        statusColor: "#4f8f4c",
        statusFill: "black"
      })
    })
};

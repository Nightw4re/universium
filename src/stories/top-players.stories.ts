import { renderTopPlayers, TopPlayersView } from "../components/top-players";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Composed/TopPlayers",
  component: TopPlayersView,
  parameters: {}
};

const players = [
  { name: "nightware6", meta: "Played 2h 11m", online: true },
  { name: "Dev", meta: "Played 48m", online: false },
  { name: "Notch", meta: "Played 1d 03h", online: false },
  { name: "MHF_Alex", meta: "Played 5h 04m", online: true }
];

export const AllTime = {
  render: () =>
    htmlStory({
        html: renderTopPlayers({
          title: "Top players",
          periodLabel: "All time",
          players,
          limit: 3
      })
    })
};

export const Last7Days = {
  render: () =>
    htmlStory({
        html: renderTopPlayers({
          title: "Top players",
          periodLabel: "Last 7 days",
          players,
          limit: 4
      })
    })
};

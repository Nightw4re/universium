import { ServerInfoPanel } from "../components/server-info-panel";
import { bindServerInfoPanel, renderServerInfoPanel } from "../components/server-info-panel";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Composed/ServerInfoPanel",
  component: ServerInfoPanel,
  parameters: {}
};

export const Default = {
  render: () =>
    htmlStory({
      html: renderServerInfoPanel({
        title: "Server info",
        state: "idle",
        rows: [
          { label: "Players", value: "12 / 80" },
          { label: "Uptime", value: "3d 12h" }
        ]
      }),
      setup: (root) => {
        const panel = root.querySelector<HTMLElement>("[data-server-info-panel]");
        if (panel) bindServerInfoPanel(panel);
      }
    })
};

export const Loading = {
  render: () =>
    htmlStory({
      html: renderServerInfoPanel({
        title: "Server info",
        state: "loading",
        loadingRows: 2,
        rows: []
      }),
      setup: (root) => {
        const panel = root.querySelector<HTMLElement>("[data-server-info-panel]");
        if (panel) bindServerInfoPanel(panel);
      }
    })
};

export const Refreshing = {
  render: () =>
    htmlStory({
      html: renderServerInfoPanel({
        title: "Server info",
        state: "refreshing",
        rows: [
          { label: "Players", value: "12 / 80" },
          { label: "Uptime", value: "3d 12h" }
        ]
      }),
      setup: (root) => {
        const panel = root.querySelector<HTMLElement>("[data-server-info-panel]");
        if (panel) bindServerInfoPanel(panel);
      }
    })
};

export const Cooldown = {
  render: () =>
    htmlStory({
      html: renderServerInfoPanel({
        title: "Server info",
        state: "cooldown",
        cooldownSeconds: 10,
        rows: [
          { label: "Players", value: "12 / 80" },
          { label: "Uptime", value: "3d 12h" }
        ]
      }),
      setup: (root) => {
        const panel = root.querySelector<HTMLElement>("[data-server-info-panel]");
        if (panel) bindServerInfoPanel(panel);
      }
    })
};

import { renderInventoryItem } from "../components/inventory-item";
import { InventoryItem } from "../components/inventory-item";
import { htmlStory } from "./storybook-html";

const blockAsset = (name: string) => `/storybook-assets/previews/block/${name}.png`;
const itemAsset = (name: string) => `/storybook-assets/previews/item/${name}.png`;

export default {
  title: "WebCraft/Simple/InventoryItem",
  component: InventoryItem,
  parameters: {}
};

const plain = (markup: string) => `
  <div class="inventory-item-story inventory-item-story--plain">
    ${markup}
  </div>
`;

export const Block = {
  render: () =>
    htmlStory({
      html: plain(
        renderInventoryItem({
          kind: "block",
          icon: `<img class="story-icon story-icon--block" src="${blockAsset("reinforced_deepslate")}" alt="" aria-hidden="true" />`,
          label: "Reinforced Deepslate"
        })
      )
    })
};

export const CountedItem = {
  render: () =>
    htmlStory({
      html: plain(
        renderInventoryItem({
          kind: "item",
          icon: `<img class="story-icon" src="${itemAsset("golden_apple")}" alt="" aria-hidden="true" />`,
          label: "Golden Apple",
          count: 64
        })
      )
    })
};

export const WithTooltip = {
  render: () =>
    htmlStory({
      html: plain(
        renderInventoryItem({
          kind: "item",
          icon: `<img class="story-icon" src="${itemAsset("golden_helmet")}" alt="" aria-hidden="true" />`,
          label: "Golden Helmet",
          tooltip: {
            title: "Golden Helmet",
            description: "Shared tooltip example",
            hidden: [{ label: "ID", value: "minecraft:golden_helmet" }],
            footer: "minecraft"
          }
        })
      )
    })
};

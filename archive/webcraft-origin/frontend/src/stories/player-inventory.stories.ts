import { PlayerInventory } from "../components/player-inventory";
import { bindPlayerInventoryHover, renderPlayerInventory } from "../components/player-inventory";
import { htmlStory } from "./storybook-html";

const blockAsset = (name: string) => `/storybook-assets/previews/block/${name}.png`;
const itemAsset = (name: string) => `/storybook-assets/previews/item/${name}.png`;
const icon = (src: string) => `<img class="story-icon" src="${src}" alt="" aria-hidden="true" />`;

const inventoryTooltip = (title: string, kind: string) => ({
  title,
  description: kind,
  hidden: [{ label: "ID", value: `minecraft:${title.toLowerCase().replaceAll(" ", "_")}` }],
  footer: "minecraft"
});

const slotTooltip = (title: string, kind: "item" | "block") => inventoryTooltip(title, kind);

const slot = (src: string, title: string, kind: "item" | "block" = "item", count?: number | string) => ({
  kind,
  icon: icon(src),
  count,
  tooltip: slotTooltip(title, kind)
});

const emptySlot = () => ({ icon: "", empty: true });

export default {
  title: "WebCraft/Composed/PlayerInventory",
  component: PlayerInventory
};

const bindInventory = (root: HTMLElement) => {
  const panel = root.firstElementChild as HTMLElement | null;
  if (panel) bindPlayerInventoryHover(panel);
};

const render = () => {
  return htmlStory({
    html: renderPlayerInventory({
      title: "Inventory",
      rows: [
        [
          slot(blockAsset("reinforced_deepslate"), "Reinforced Deepslate", "block", 64),
          slot(itemAsset("poppy"), "Poppy", "item", 42),
          slot(itemAsset("egg"), "Egg", "item", 16),
          slot(itemAsset("golden_apple"), "Golden Apple", "item", 32),
          emptySlot(),
          slot(itemAsset("golden_helmet"), "Golden Helmet"),
          slot(blockAsset("reinforced_deepslate_top"), "Reinforced Deepslate", "block", 48),
          emptySlot(),
          slot(itemAsset("sniffer_egg"), "Sniffer Egg", "item", 3)
        ],
        [
          emptySlot(),
          slot(itemAsset("acacia_boat"), "Acacia Boat", "item", 1),
          emptySlot(),
          slot(itemAsset("golden_apple"), "Golden Apple", "item", 27),
          slot(itemAsset("crafting_table"), "Crafting Table"),
          emptySlot(),
          slot(blockAsset("reinforced_deepslate_side"), "Block", "block", 18),
          slot(blockAsset("reinforced_deepslate_bottom"), "Stone", "block", 27),
          slot(itemAsset("shield"), "Shield")
        ],
        [
          slot(blockAsset("reinforced_deepslate_bottom"), "Stone", "block", 16),
          emptySlot(),
          slot(blockAsset("reinforced_deepslate"), "Ore", "block", 39),
          slot(blockAsset("reinforced_deepslate_top"), "Dirt", "block", 8),
          emptySlot(),
          slot(blockAsset("reinforced_deepslate"), "Gravel", "block", 24),
          slot(blockAsset("reinforced_deepslate_bottom"), "Netherrack", "block", 14),
          slot(itemAsset("egg"), "Egg", "item", 6),
          emptySlot()
        ]
      ],
      hotbarLabel: "Hotbar",
      hotbar: [
        slot(itemAsset("shield"), "Shield", "item", 1),
        emptySlot(),
        slot(itemAsset("poppy"), "Poppy", "item", 7),
        slot(itemAsset("egg"), "Egg", "item", 16),
        emptySlot(),
        slot(itemAsset("crafting_table"), "Crafting Table"),
        slot(blockAsset("reinforced_deepslate"), "Block", "block", 21),
        slot(itemAsset("golden_apple"), "Apple", "item", 4),
        slot(blockAsset("reinforced_deepslate_side"), "Rock", "block", 11)
      ]
    }),
    setup: bindInventory
  });
};

export const Default = {
  render
};

export const EnderChest = {
  render: () =>
    htmlStory({
      html: renderPlayerInventory({
        title: "Ender Chest",
        rows: [
          [
            slot(itemAsset("golden_apple"), "Golden Apple", "item", 16),
            slot(itemAsset("missingno"), "Ender Chest"),
            emptySlot(),
            slot(itemAsset("poppy"), "Poppy", "item", 8),
            slot(itemAsset("egg"), "Egg", "item", 32),
            emptySlot(),
            slot(blockAsset("reinforced_deepslate"), "Obsidian", "block", 64),
            slot(itemAsset("shield"), "Shield"),
            emptySlot()
          ],
          [
            emptySlot(),
            slot(itemAsset("sniffer_egg"), "Sniffer Egg", "item", 1),
            slot(itemAsset("crafting_table"), "Crafting Table"),
            emptySlot(),
            slot(blockAsset("reinforced_deepslate_bottom"), "Block", "block", 12),
            slot(itemAsset("golden_helmet"), "Golden Helmet"),
            emptySlot(),
            slot(itemAsset("golden_apple"), "Apple", "item", 4),
            slot(blockAsset("reinforced_deepslate_side"), "Stone", "block", 21)
          ],
          [
            slot(itemAsset("poppy"), "Poppy", "item", 3),
            emptySlot(),
            slot(itemAsset("egg"), "Egg", "item", 7),
            slot(blockAsset("reinforced_deepslate_top"), "Dirt", "block", 18),
            emptySlot(),
            slot(itemAsset("shield"), "Shield"),
            slot(blockAsset("reinforced_deepslate"), "Rock", "block", 9),
            emptySlot(),
            slot(itemAsset("golden_apple"), "Golden Apple", "item", 2)
          ]
        ],
      }),
      setup: bindInventory
    })
};

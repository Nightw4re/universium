import { bindItemSlotHover, bindItemSlotTooltips, renderItemSlot } from "../components/item-slot";
import { ItemSlot } from "../components/item-slot";
import { htmlStory } from "./storybook-html";

const itemAsset = (name: string) => `/storybook-assets/previews/item/${name}.png`;

export default {
  title: "WebCraft/Simple/ItemSlot",
  component: ItemSlot,
  parameters: {}
};

export const Empty = {
  render: () =>
    htmlStory({
      html: renderItemSlot({
        icon: "",
        empty: true
      }),
      setup: (root) => {
        const slot = root.firstElementChild as HTMLElement | null;
        if (slot) bindItemSlotHover(slot);
      }
    })
};

export const Item = {
  render: () =>
    htmlStory({
      html: renderItemSlot({
        icon: `<img class="story-icon" src="${itemAsset("crafting_table")}" alt="" aria-hidden="true" />`,
        count: 64,
        tooltip: {
          title: "Crafting Table",
          description: "Utility block",
          hidden: [{ label: "ID", value: "minecraft:crafting_table" }],
          footer: "minecraft"
        }
      }),
      setup: (root) => {
        const slot = root.firstElementChild as HTMLElement | null;
        if (slot) bindItemSlotTooltips(slot);
        if (slot) bindItemSlotHover(slot);
      }
    })
};

export const Loading = {
  render: () =>
    htmlStory({
      html: renderItemSlot({
        icon: "",
        loading: true
      })
    })
};

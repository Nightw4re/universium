import { Equipment, renderEquipment } from "../components/equipment";
import { bindItemSlotGroupLoading, bindItemSlotHover, bindItemSlotTooltips } from "../components/item-slot";
import { htmlStory } from "./storybook-html";

const itemAsset = (name: string) => `/storybook-assets/previews/item/${name}.png`;

export default {
  title: "WebCraft/Composed/Equipment",
  component: Equipment,
  parameters: {}
};

export const Empty = {
  render: () =>
    htmlStory({
      html: renderEquipment({
        helmet: { icon: `<img src="${itemAsset("missingno")}" alt="" />`, empty: true },
        chestplate: { icon: `<img src="${itemAsset("missingno")}" alt="" />`, empty: true },
        legs: { icon: `<img src="${itemAsset("missingno")}" alt="" />`, empty: true },
        boots: { icon: `<img src="${itemAsset("missingno")}" alt="" />`, empty: true },
        offhand: { icon: `<img src="${itemAsset("missingno")}" alt="" />`, empty: true }
      }),
      setup: (root) => {
        const group = root.querySelector<HTMLElement>("[data-slot-group]");
        if (group) bindItemSlotGroupLoading(group);
        root.querySelectorAll<HTMLElement>("[data-item-slot]").forEach((slot) => {
          bindItemSlotHover(slot);
          bindItemSlotTooltips(slot);
        });
      }
    })
};

export const Equipped = {
  render: () =>
    htmlStory({
      html: renderEquipment({
        helmet: { icon: `<img src="${itemAsset("golden_helmet")}" alt="" />`, tooltip: { title: "Golden Helmet", description: "Protection IV" } },
        chestplate: { icon: `<img src="${itemAsset("shield_base")}" alt="" />`, tooltip: { title: "Chestplate", description: "Armor slot placeholder" } },
        legs: { icon: `<img src="${itemAsset("shield_base")}" alt="" />`, tooltip: { title: "Leggings", description: "Armor slot placeholder" } },
        boots: { icon: `<img src="${itemAsset("poppy")}" alt="" />`, tooltip: { title: "Boots", description: "Armor slot placeholder" } },
        offhand: { icon: `<img src="${itemAsset("shield")}" alt="" />`, tooltip: { title: "Shield", description: "Offhand slot" } }
      }),
      setup: (root) => {
        const group = root.querySelector<HTMLElement>("[data-slot-group]");
        if (group) bindItemSlotGroupLoading(group);
        root.querySelectorAll<HTMLElement>("[data-item-slot]").forEach((slot) => {
          bindItemSlotHover(slot);
          bindItemSlotTooltips(slot);
        });
      }
    })
};

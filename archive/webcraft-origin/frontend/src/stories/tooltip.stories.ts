import { bindTooltipPlacementDemo, computeTooltipPosition, renderTooltip } from "../components/tooltip";
import { Tooltip } from "../components/tooltip";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Simple/Tooltip",
  component: Tooltip,
  parameters: {}
};

export const Default = {
  render: () =>
    htmlStory({
      className: "tooltip-story-shell",
      html: renderTooltip(
        {
          title: "Terra Shatterer",
          attributes: [
            { label: "Damage", value: "9" },
            { label: "Armor", value: "4" }
          ],
          enchantments: ["Fortune IV", "Unbreaking III"],
          description: "A tool for serious mining jobs.",
          hidden: [
            { label: "ID", value: "botania:terra_shatterer" },
            { label: "Durability", value: "0 / 300" }
          ],
          footer: "Botania"
        },
        { visible: true }
      )
    })
};

export const PlacementScenarios = {
  render: () => {
    const cards = [
      {
        title: "Top left",
        description: "Fits down-right",
        className: "tooltip-placement-card--top-left",
        placement: "right-bottom"
      },
      {
        title: "Top right",
        description: "Fits down-left",
        className: "tooltip-placement-card--top-right",
        placement: "left-bottom"
      },
      {
        title: "Bottom left",
        description: "Fits up-right",
        className: "tooltip-placement-card--bottom-left",
        placement: "right-top"
      },
      {
        title: "Bottom right",
        description: "Fits up-left",
        className: "tooltip-placement-card--bottom-right",
        placement: "left-top"
      }
    ];

    return htmlStory({
      className: "tooltip-placement-grid",
      html: cards
        .map(
          (card) => `
            <div class="tooltip-placement-card ${card.className}" data-tooltip-placement-card data-tooltip-placement="${card.placement}">
              <button class="tooltip-placement-anchor" type="button" data-tooltip-anchor>${card.title}</button>
              ${renderTooltip({
                title: card.title,
                description: card.description
              })}
            </div>
          `
        )
        .join(""),
      setup: bindTooltipPlacementDemo
    });
  }
};

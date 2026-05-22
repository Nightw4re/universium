import { hydrateModelTextures, renderItemIcon } from "../components/item-render";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Debug/ShieldModelOnly",
  parameters: {
    layout: "fullscreen"
  }
};

export const Default = {
  render: () =>
    htmlStory({
      html: `
        <section class="panel shield-model-debug" data-shield-model-debug="true" style="max-width: 360px; margin: 32px auto;">
          <p class="eyebrow">Debug</p>
          <h1>Shield model only</h1>
          <div class="shield-model-debug__frame">
            <div class="shield-model-debug__icon">
              ${renderItemIcon({
                id: "minecraft:shield",
                displayName: "Shield",
                renderMode: "model",
                modelId: "builtin/entity"
              })}
            </div>
          </div>
        </section>
      `,
      setup: (root) => {
        void hydrateModelTextures(root);
      }
    })
};

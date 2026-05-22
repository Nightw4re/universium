import type { StorybookConfig } from "@storybook/react-vite";

const config: StorybookConfig = {
  stories: ["../src/stories/**/*.stories.ts"],
  staticDirs: [{ from: "../storybook-assets", to: "/storybook-assets" }],
  addons: ["@storybook/addon-vitest", "@storybook/addon-mcp", "@storybook/addon-a11y"],
  framework: {
    name: "@storybook/react-vite",
    options: {}
  }
};

export default config;

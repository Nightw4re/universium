import { TextClearInput } from "../components/text-clear-input";
import { bindTextClearInput, renderTextClearInput } from "../components/text-clear-input";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Simple/TextClearInput",
  component: TextClearInput,
  parameters: {}
};

export const Default = {
  render: () => {
    return htmlStory({
      html: renderTextClearInput({
        value: "Search by name",
        placeholder: "Search by name"
      }),
      setup: (root) => {
        const input = root.firstElementChild as HTMLElement | null;
        if (input) bindTextClearInput(input);
      }
    });
  }
};

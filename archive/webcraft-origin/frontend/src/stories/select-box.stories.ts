import { SelectBox } from "../components/select-box";
import { bindSelectBox, renderSelectBox } from "../components/select-box";
import { htmlStory } from "./storybook-html";

export default {
  title: "WebCraft/Simple/SelectBox",
  component: SelectBox,
  parameters: {}
};

const options = [
  { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
  { label: "Alphabetical", value: "alphabetical", group: "option" },
  { label: "Recently seen", value: "recently-seen", group: "option" },
  { label: "Most active", value: "most-active", group: "option" }
];

const story = (selected: string, open = false) => ({
  render: () => {
    return htmlStory({
      html: renderSelectBox({
        label: "Sort by",
        selected,
        options
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        if (open) select.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
      }
    });
  }
});

export const Default = story("alphabetical");
export const SelectedDifferentValue = story("recently-seen");
export const Open = story("alphabetical", true);
export const AutoWidth = {
  render: () => {
    return htmlStory({
      className: "",
      style: { width: "240px" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        options: [
          { label: "Alphabetical", value: "alphabetical", group: "option" },
          { label: "Recently seen", value: "recently-seen", group: "option" },
          { label: "Most active", value: "most-active", group: "option" },
          { label: "A very very long option name that sets the width", value: "wide-option", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
      }
    });
  }
};

export const StressWide = {
  render: () => {
    return htmlStory({
      style: { width: "210px" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        menuWidth: "320px",
        options: [
          { label: "Alphabetical with a deliberately long label to force the menu to become much wider than the trigger", value: "alphabetical", group: "option" },
          { label: "Recently seen with another long label to stress the width calculation and the clamp", value: "recently-seen", group: "option" },
          { label: "Most active with a third long label so the menu keeps its own width and not the trigger width", value: "most-active", group: "option" },
          { label: "Another option that is intentionally verbose to drive the measured content width further", value: "another-wide-option", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
      }
    });
  }
};

export const CompactNoSearch = {
  render: () => {
    return htmlStory({
      style: { width: "280px" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        searchable: false,
        showCheckboxes: false,
        options: [
          { label: "Alphabetical", value: "alphabetical", group: "option" },
          { label: "Recently seen", value: "recently-seen", group: "option" },
          { label: "Most active", value: "most-active", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
      }
    });
  }
};

export const WideOptions = {
  render: () => {
    return htmlStory({
      style: { width: "320px" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        menuWidth: "440px",
        options: [
          { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
          { label: "Alphabetical", value: "alphabetical", group: "option" },
          { label: "Recently seen", value: "recently-seen", group: "option" },
          { label: "Most active", value: "most-active", group: "option" },
          { label: "Longest name wins", value: "longest-name", group: "option" },
          { label: "Online first with a longer explanation", value: "online-first-long", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.click();
      }
    });
  }
};
export const NarrowWideMenu = {
  render: () => {
    return htmlStory({
      style: { width: "280px" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        menuWidth: "440px",
        options: [
          { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
          { label: "Alphabetical", value: "alphabetical", group: "option" },
          { label: "Recently seen", value: "recently-seen", group: "option" },
          { label: "Most active", value: "most-active", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.classList.add("select-box--open");
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.setAttribute("aria-expanded", "true");
      }
    });
  }
};
export const CenteredWideMenu = {
  render: () => {
    return htmlStory({
      style: { width: "560px", display: "flex", justifyContent: "center" },
      html: renderSelectBox({
        label: "Sort by",
        selected: "alphabetical",
        menuWidth: "440px",
        menuAlign: "center",
        options: [
          { label: "Online first", value: "online-first", checked: true, group: "checkbox" },
          { label: "Alphabetical", value: "alphabetical", group: "option" },
          { label: "Recently seen", value: "recently-seen", group: "option" },
          { label: "Most active", value: "most-active", group: "option" },
          { label: "Longest name wins", value: "longest-name", group: "option" }
        ]
      }),
      setup: (root) => {
        const select = root.firstElementChild as HTMLElement | null;
        if (!select) return;
        bindSelectBox(select);
        select.classList.add("select-box--open");
        select.querySelector<HTMLElement>("[data-select-box-trigger]")?.setAttribute("aria-expanded", "true");
      }
    });
  }
};

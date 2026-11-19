import type { ServerAppData } from "./frontend-contract";

export const appContent: ServerAppData = {
  items: ["home", "players"],
};

export const pageData = {
  home: {
      topPlayers: [
        { name: "nightware6", online: true, meta: "Played 2h 11m" },
        { name: "Dev", online: false, meta: "Played 48m" },
        { name: "Notch", online: false, meta: "Played 1d 03h" },
        { name: "Jeb_", online: false, meta: "Played 57m" }
      ],
      detailHero: {
        name: "Nightware6",
        playedTime: "12h 32m",
        combat: [
          { label: "Armor", value: "18" },
          { label: "Armor Toughness", value: "4" },
          { label: "HP", value: "20" },
          { label: "Hunger", value: "16" },
          { label: "Saturation", value: "8.0" }
        ]
      },
      inventory: {
        rows: [
          [
            { icon: "Ă˘Â¬â€ş", label: "Grass Block" },
            { icon: "Ä‘ĹşĹšÂ¸", label: "Poppy" },
            { icon: "Ä‘ĹşÄ„Ĺˇ", label: "Egg" },
            { icon: "Ä‘ĹşĹ¤Ĺ˝", label: "Golden Apple" },
            { icon: "Ă˘Ĺˇâ€™", label: "Crafting Table" },
            { icon: "Ä‘ĹşĹžâ€“", label: "Golden Helmet" },
            { icon: "Ă˘Â¬â€ş", label: "Reinforced Deepslate" }
          ],
          [
            { icon: "Ä‘Ĺşâ€şË‡", label: "Shield" },
            { icon: "Ä‘ĹşÂÂ´", label: "Boat" },
            { icon: "Ä‘ĹşĹ¤Ĺ˝", label: "Apple" },
            { icon: "Ä‘ĹşÂĹ", label: "Sniffer Egg" },
            { icon: "Ă˘Ĺˇâ€™", label: "Crafting Table" },
            { icon: "Ă˘Â¬â€ş", label: "Block" },
            { icon: "Ä‘ĹşĹšÂ¸", label: "Flower" }
          ]
        ],
        hotbar: [
          { icon: "Ä‘Ĺşâ€şË‡", label: "Shield" },
          { icon: "Ä‘ĹşÂÂ´", label: "Boat" },
          { icon: "Ä‘ĹşĹšÂ¸", label: "Poppy" },
          { icon: "Ä‘ĹşÄ„Ĺˇ", label: "Egg" },
          { icon: "Ä‘ĹşĹžâ€“", label: "Helmet" },
          { icon: "Ă˘Ĺˇâ€™", label: "Table" },
          { icon: "Ă˘Â¬â€ş", label: "Block" },
          { icon: "Ä‘ĹşĹ¤Ĺ˝", label: "Apple" },
          { icon: "Ă˘Â¬â€ş", label: "Rock" }
        ]
      },
      playerSearch: {
        query: "",
        filter: "all",
        sort: "Recently seen",
        players: [
          { name: "nightware6", online: true, meta: "Online now" },
          { name: "Dev", online: false, meta: "Offline profile" }
        ]
      },
    tooltip: {
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
      }
  },
  players: {
    topPlayers: [
        { name: "nightware6", online: true, meta: "Played 2h 11m" },
        { name: "Dev", online: false, meta: "Played 48m" }
      ],
      detailHero: {
        name: "nightware6",
        playedTime: "12h 32m",
        combat: [
          { label: "Armor", value: "18" },
          { label: "Armor Toughness", value: "4" },
          { label: "HP", value: "20" },
          { label: "Hunger", value: "16" },
          { label: "Saturation", value: "8.0" }
        ]
      },
      inventory: {
        rows: [],
        hotbar: []
      },
      playerSearch: {
        query: "",
        filter: "all",
        sort: "Recently seen",
        players: [
          { name: "nightware6", online: true, meta: "Online now" },
          { name: "Dev", online: false, meta: "Offline profile" }
        ]
      },
      tooltip: {
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
      }
  }
} as const;

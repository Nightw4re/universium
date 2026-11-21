import assert from "node:assert/strict";
import { test } from "node:test";
import { itemFeedRequests } from "../item-feeds.mjs";

const prefix = "/api/v1/modules/webcraft_catalog/";
const modules = [
  { id: "webcraft_catalog:items", routes: { stack: prefix + "items/stack" } },
  { id: "webcraft_catalog:recipes", routes: { recipes: prefix + "recipes/recipes" } },
  { id: "webcraft_catalog:tooltips", routes: { tooltip: prefix + "tooltips/tooltip" } },
];

test("variant data is scoped to the catalog entry while recipes follow the selected registered item", () => {
  assert.deepEqual(itemFeedRequests(modules, "ae2:fluix_glass_cable", "ae2:red_glass_cable", "color/red"), {
    stack: prefix + "items/stack?id=ae2%3Afluix_glass_cable&variant=color%2Fred",
    recipes: prefix + "recipes/recipes?id=ae2%3Ared_glass_cable",
    tooltip: prefix + "tooltips/tooltip?id=ae2%3Afluix_glass_cable&variant=color%2Fred",
  });
});

test("uses only independently published routes and encodes breeder composite identifiers", () => {
  const tooltip = modules[2];
  const selected = "villager_1/minecraft:plains/minecraft:farmer+villager_2/minecraft:desert/minecraft:librarian";
  const id = "easy_villagers:breeder";
  const routes = itemFeedRequests([tooltip], id, id, selected);
  assert.deepEqual(Object.keys(routes), ["tooltip"]);
  assert.equal(new URL(routes.tooltip, "http://localhost").searchParams.get("variant"), selected);
  assert.deepEqual(itemFeedRequests([
    { id: "webcraft_catalog:items", routes: { detail: prefix + "items/detail" } },
    { id: "webcraft_catalog:recipes", routes: { recipes: "https://example.com/recipes" } },
  ], id, id), {});
});

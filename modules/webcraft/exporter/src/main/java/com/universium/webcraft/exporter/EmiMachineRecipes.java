package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class EmiMachineRecipes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> BUILTIN_NAMESPACES = Set.of("minecraft", "emi", "jei");
    private static final int MAX_DIMENSION = 4096;
    private static final int MAX_SLOTS = 128;
    private static final int MAX_ALTERNATIVES = 64;

    private EmiMachineRecipes() {}

    static Optional<Set<Item>> visibleItems() {
        var stacks = EmiApi.getIndexStacks();
        if (stacks.isEmpty()) return Optional.empty();
        return Optional.of(stacks.stream().map(EmiStack::getKey)
                .filter(Item.class::isInstance).map(Item.class::cast).collect(Collectors.toUnmodifiableSet()));
    }

    static Iterator<Supplier<JsonObject>> captures(Set<String> excludedCategories, MachineRecipeMaterials materials) {
        var manager = EmiApi.getRecipeManager();
        return manager.getCategories().stream()
                .filter(category -> !BUILTIN_NAMESPACES.contains(category.getId().getNamespace())
                        && !excludedCategories.contains(category.getId().toString()))
                .flatMap(category -> {
                    var stations = manager.getWorkstations(category).stream()
                            .flatMap(ingredient -> ingredient.getEmiStacks().stream())
                            .filter(stack -> !stack.isEmpty() && stack.getKey() instanceof Item)
                            .map(stack -> stack.getId().toString()).distinct().sorted().limit(64).toList();
                    return manager.getRecipes(category).stream()
                            .<Supplier<JsonObject>>map(recipe -> () -> capture(recipe, category, stations, materials));
                }).iterator();
    }

    private static JsonObject capture(EmiRecipe recipe, EmiRecipeCategory category, List<String> stations,
                                      MachineRecipeMaterials materials) {
        int width = recipe.getDisplayWidth();
        int height = recipe.getDisplayHeight();
        if (!validDimension(width) || !validDimension(height)) return null;
        var entries = Stream.of(
                recipe.getInputs().stream().filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> new Slot("input", ingredient)),
                recipe.getCatalysts().stream().filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> new Slot("catalyst", ingredient)),
                recipe.getOutputs().stream().filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> new Slot("output", ingredient)))
                .flatMap(stream -> stream).limit(MAX_SLOTS + 1L).toList();
        if (entries.size() > MAX_SLOTS) return null;

        var slots = new JsonArray();
        boolean hasInput = entries.stream().anyMatch(entry -> !"output".equals(entry.role));
        boolean hasOutput = false;
        boolean hasMaterialOutput = false;
        captureBounds(recipe, entries, width, height);
        for (var entry : entries) {
            var slot = slot(entry, materials);
            if ("output".equals(entry.role)) {
                var alternatives = slot.getAsJsonArray("alternatives");
                hasOutput |= !alternatives.isEmpty();
                hasMaterialOutput |= alternatives.asList().stream()
                        .anyMatch(value -> !"item".equals(value.getAsJsonObject().get("kind").getAsString()));
            }
            slots.add(slot);
        }
        if (!hasOutput || (!hasInput && !hasMaterialOutput)) return null;

        var layout = new JsonObject();
        layout.addProperty("width", width);
        layout.addProperty("height", height);
        layout.add("slots", slots);
        var row = new JsonObject();
        var categoryId = category.getId();
        ResourceLocation id = recipe.getId();
        if (id == null) {
            String hash = Hashing.sha256().hashString(layout.toString(), StandardCharsets.UTF_8).toString();
            id = categoryId.withPath(categoryId.getPath() + "/display/" + hash);
        }
        row.addProperty("id", id.toString());
        row.addProperty("category", categoryId.toString());
        String title = category.getName().getString();
        row.addProperty("title", title.isBlank() ? categoryId.toString() : title.substring(0, Math.min(title.length(), 256)));
        var stationIds = new JsonArray();
        stations.forEach(stationIds::add);
        row.add("stations", stationIds);
        row.add("layout", layout);
        return row;
    }

    private static void captureBounds(EmiRecipe recipe, List<Slot> entries, int width, int height) {
        try {
            var holder = new Slots(width, height);
            recipe.addWidgets(holder);
            for (var widget : holder.widgets) {
                var ingredient = widget.getStack();
                if (ingredient.isEmpty()) continue;
                boolean output = widget.getRecipe() == recipe;
                Slot match = null;
                for (var entry : entries) {
                    if (entry.bounds != null || output != "output".equals(entry.role)
                            || !sameIngredient(ingredient, entry.ingredient)) continue;
                    if (match == null) match = entry;
                    else if (!match.role.equals(entry.role)) {
                        // EMI declares input/catalyst roles but has no public catalyst flag on SlotWidget.
                        match = null;
                        break;
                    }
                }
                var bounds = widget.getBounds();
                if (match != null && validDimension(bounds.width()) && validDimension(bounds.height())
                        && Math.abs((long) bounds.x()) <= MAX_DIMENSION && Math.abs((long) bounds.y()) <= MAX_DIMENSION) {
                    match.bounds = bounds;
                }
            }
        } catch (RuntimeException | LinkageError error) {
            LOGGER.debug("WebCraft could not inspect EMI recipe widgets", error);
        }
    }

    private static boolean sameIngredient(EmiIngredient first, EmiIngredient second) {
        return first.getAmount() == second.getAmount() && Float.compare(first.getChance(), second.getChance()) == 0
                && EmiIngredient.areEqual(first, second);
    }

    private static JsonObject slot(Slot entry, MachineRecipeMaterials materials) {
        var slot = new JsonObject();
        slot.addProperty("role", entry.role);
        if (entry.bounds != null) {
            var bounds = new JsonObject();
            bounds.addProperty("x", entry.bounds.x());
            bounds.addProperty("y", entry.bounds.y());
            bounds.addProperty("width", entry.bounds.width());
            bounds.addProperty("height", entry.bounds.height());
            slot.add("bounds", bounds);
        }
        var alternatives = new JsonArray();
        var seen = new HashSet<JsonObject>();
        long amount = entry.ingredient.getAmount();
        for (var stack : entry.ingredient.getEmiStacks()) {
            if (stack.isEmpty()) continue;
            var material = material(stack, amount, materials);
            if (material == null) {
                slot.addProperty("otherIngredients", true);
                continue;
            }
            if (!seen.add(material)) continue;
            if (seen.size() > MAX_ALTERNATIVES) {
                slot.addProperty("alternativesTruncated", true);
                break;
            }
            alternatives.add(material);
        }
        slot.add("alternatives", alternatives);
        return slot;
    }

    private static JsonObject material(EmiStack stack, long amount, MachineRecipeMaterials materials) {
        if (amount < 1) return null;
        Object key = stack.getKey();
        if (!(key instanceof Item || key instanceof Fluid)) return materials.chemical(key, stack.getId(), amount);
        if (amount > Integer.MAX_VALUE) return null;
        if (key instanceof Fluid fluid) {
            return materials.fluid(new FluidStack(fluid.builtInRegistryHolder(), (int) amount, stack.getComponentChanges()));
        }
        var material = new JsonObject();
        material.addProperty("kind", "item");
        material.addProperty("id", stack.getId().toString());
        material.addProperty("count", amount);
        return material;
    }

    private static boolean validDimension(int value) {
        return value > 0 && value <= MAX_DIMENSION;
    }

    private static final class Slot {
        final String role;
        final EmiIngredient ingredient;
        Bounds bounds;

        Slot(String role, EmiIngredient ingredient) {
            this.role = role;
            this.ingredient = ingredient;
        }
    }

    private static final class Slots implements WidgetHolder {
        private final int width;
        private final int height;
        private final List<SlotWidget> widgets = new ArrayList<>();

        Slots(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public <T extends Widget> T add(T widget) {
            if (widget instanceof SlotWidget slot && widgets.size() < MAX_SLOTS) widgets.add(slot);
            return widget;
        }
    }
}

package com.universium.webcraft.exporter;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** JEI discovers this plugin when both mods are installed on the client. */
@JeiPlugin
public final class JeiMachineRecipePlugin implements IModPlugin {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("webcraft_exporter", "machine_recipes");
    private static final Set<String> VANILLA_EXPORTS = Set.of(
            "crafting", "smelting", "blasting", "smoking", "campfire", "campfire_cooking", "stonecutting", "smithing");
    private static final int MAX_ALTERNATIVES = 64;
    private static final int MAX_SLOTS = 128;
    private static final int MAX_DIMENSION = 4096;
    private static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime current) {
        runtime = current;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    static Set<String> categoryIds() {
        var current = runtime;
        if (current == null) return Set.of();
        // JEMI imports hidden JEI categories as well, using their original IDs.
        return current.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                .map(category -> category.getRecipeType().getUid().toString()).collect(Collectors.toSet());
    }

    static Optional<Set<Item>> visibleItems() {
        var current = runtime;
        if (current == null) return Optional.empty();
        var visibility = current.getJeiHelpers().getIngredientVisibility();
        var visible = current.getIngredientManager().getAllTypedIngredients(VanillaTypes.ITEM_STACK).stream()
                .filter(visibility::isIngredientVisible)
                .map(ingredient -> ingredient.getIngredient().getItem())
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(visible);
    }

    static Iterator<Supplier<JsonObject>> captures(MachineRecipeMaterials materials) {
        var current = runtime;
        if (current == null) return null;
        var manager = current.getRecipeManager();
        var focuses = current.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        return manager.createRecipeCategoryLookup().get()
                .filter(category -> {
                    var id = category.getRecipeType().getUid();
                    return !"minecraft".equals(id.getNamespace()) || !VANILLA_EXPORTS.contains(id.getPath());
                })
                .flatMap(category -> captures(manager, focuses, category, materials)).iterator();
    }

    private static <T> Stream<Supplier<JsonObject>> captures(IRecipeManager manager, IFocusGroup focuses,
                                                             IRecipeCategory<T> category, MachineRecipeMaterials materials) {
        var type = category.getRecipeType();
        var stations = manager.createRecipeCatalystLookup(type).getItemStack()
                .filter(stack -> !stack.isEmpty()).map(JeiMachineRecipePlugin::itemId)
                .distinct().sorted().limit(64).toList();
        return manager.createRecipeLookup(type).get()
                .map(recipe -> () -> manager.createRecipeLayoutDrawable(category, recipe, focuses)
                        .map(layout -> capture(category, recipe, layout, stations, materials)).orElse(null));
    }

    private static <T> JsonObject capture(IRecipeCategory<T> category, T recipe, IRecipeLayoutDrawable<T> drawable,
                                           List<String> stations, MachineRecipeMaterials materials) {
        var frame = drawable.getRect();
        if (!validDimension(frame.getWidth()) || !validDimension(frame.getHeight())) return null;
        drawable.setPosition(0, 0);
        var slots = new JsonArray();
        var views = drawable.getRecipeSlotsView().getSlotViews();
        if (views.size() > MAX_SLOTS) return null;
        boolean hasInput = false;
        boolean hasOutput = false;
        boolean hasMaterialOutput = false;
        for (var view : views) {
            var role = view.getRole();
            if (role == RecipeIngredientRole.RENDER_ONLY) continue;
            var slot = captureSlot(view, materials);
            var alternatives = slot.getAsJsonArray("alternatives");
            hasInput |= role == RecipeIngredientRole.INPUT && (!alternatives.isEmpty() || slot.has("otherIngredients"));
            if (role == RecipeIngredientRole.OUTPUT) {
                hasOutput |= !alternatives.isEmpty();
                hasMaterialOutput |= alternatives.asList().stream()
                        .anyMatch(value -> !"item".equals(value.getAsJsonObject().get("kind").getAsString()));
            }
            slots.add(slot);
        }
        if (!hasOutput || (!hasInput && !hasMaterialOutput)) return null;

        var layout = new JsonObject();
        layout.addProperty("width", frame.getWidth());
        layout.addProperty("height", frame.getHeight());
        layout.add("slots", slots);
        var row = new JsonObject();
        var categoryId = category.getRecipeType().getUid();
        ResourceLocation id = recipe instanceof RecipeHolder<?> holder ? holder.id() : category.getRegistryName(recipe);
        if (id == null) {
            String hash = Hashing.sha256().hashString(layout.toString(), StandardCharsets.UTF_8).toString();
            id = categoryId.withPath(categoryId.getPath() + "/display/" + hash);
        }
        row.addProperty("id", id.toString());
        row.addProperty("category", categoryId.toString());
        String title = category.getTitle().getString();
        row.addProperty("title", title.substring(0, Math.min(title.length(), 256)));
        var stationIds = new JsonArray();
        stations.forEach(stationIds::add);
        row.add("stations", stationIds);
        row.add("layout", layout);
        return row;
    }

    private static JsonObject captureSlot(IRecipeSlotView view, MachineRecipeMaterials materials) {
        var slot = new JsonObject();
        slot.addProperty("role", view.getRole().name().toLowerCase(Locale.ROOT));
        view.getSlotName().filter(name -> !name.isBlank() && name.length() <= 128)
                .ifPresent(name -> slot.addProperty("name", name));
        if (view instanceof IRecipeSlotDrawable drawable) {
            var rect = drawable.getRect();
            if (validDimension(rect.getWidth()) && validDimension(rect.getHeight())
                    && Math.abs((long) rect.getX()) <= MAX_DIMENSION && Math.abs((long) rect.getY()) <= MAX_DIMENSION) {
                var bounds = new JsonObject();
                bounds.addProperty("x", rect.getX());
                bounds.addProperty("y", rect.getY());
                bounds.addProperty("width", rect.getWidth());
                bounds.addProperty("height", rect.getHeight());
                slot.add("bounds", bounds);
            }
        }
        var alternatives = new JsonArray();
        var distinct = new java.util.LinkedHashSet<JsonObject>();
        try (var ingredients = view.getAllIngredients()) {
            var iterator = ingredients.iterator();
            while (iterator.hasNext()) {
                var ingredient = material(iterator.next(), materials);
                if (ingredient == null) slot.addProperty("otherIngredients", true);
                else if (distinct.add(ingredient)) {
                    if (distinct.size() > MAX_ALTERNATIVES) {
                        slot.addProperty("alternativesTruncated", true);
                        break;
                    }
                    alternatives.add(ingredient);
                }
            }
        }
        slot.add("alternatives", alternatives);
        return slot;
    }

    private static JsonObject material(ITypedIngredient<?> ingredient, MachineRecipeMaterials materials) {
        var item = ingredient.getItemStack();
        if (item.isPresent() && !item.get().isEmpty()) {
            var stack = item.get();
            var material = new JsonObject();
            material.addProperty("kind", "item");
            material.addProperty("id", itemId(stack));
            material.addProperty("count", stack.getCount());
            return material;
        }
        var fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
        if (fluid.isPresent() && !fluid.get().isEmpty()) {
            return materials.fluid(fluid.get());
        }
        return materials.jeiChemical(ingredient.getIngredient());
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean validDimension(int value) {
        return value > 0 && value <= MAX_DIMENSION;
    }
}

package com.universium.webcraft.quests;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Resolves representative items with FTB's server-side matcher, without creative-tab rendering. */
final class QuestItems {
    private static final Pattern HINT = Pattern.compile("(?:\\bftbfiltersystem:)?\\b(item|item_tag)\\(([a-z0-9_.:/-]+)\\)");
    private static final Pattern COMPONENTS = Pattern.compile("\\bcomponent\\((?:fuzzy|strict):(?=\\{)");
    private static final int MAX_HINTS = 128;
    private static final int MAX_TAG_CANDIDATES = 256;
    private static final int MAX_FALLBACK_CANDIDATES = 1024;
    private static final int MAX_FALLBACK_CHECKS = 80_000;
    private static final int MAX_TAG_CHECKS = 40_000;
    private static final int MAX_FILTERS = 1024;
    private final HolderLookup.Provider registries;
    private final HashMap<CompoundTag, ItemStack> matches = new HashMap<>();
    private int fallbackBudget = MAX_FALLBACK_CHECKS;
    private int tagBudget = MAX_TAG_CHECKS;

    QuestItems(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    Optional<String> filterHint(ItemStack stack) {
        if (stack.isEmpty() || !isPlaceholder(stack)) return Optional.empty();
        var saved = (CompoundTag) stack.save(registries);
        String source = saved.getCompound("components").getString("ftbfiltersystem:filter");
        if (source.length() > 16_384) return Optional.empty();
        var hint = HINT.matcher(source);
        if (!hint.find()) return Optional.empty();
        String label = (hint.group(1).equals("item_tag") ? "#" : "") + hint.group(2);
        return label.length() <= QuestText.MAX_TITLE_CHARS ? Optional.of(label) : Optional.empty();
    }

    private static boolean isPlaceholder(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("ftbfiltersystem")
                || id.toString().equals("ftbquests:custom_icon")
                || id.toString().equals("ftbquests:missing_item");
    }

    ItemStack representative(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        var adapter = ItemMatchingSystem.INSTANCE.getFilterAdapter(stack);
        if (adapter.isEmpty()) return isPlaceholder(stack) ? ItemStack.EMPTY : stack;
        var key = (CompoundTag) stack.save(registries);
        key.remove("count");
        ItemStack cached = matches.get(key);
        if (cached != null) return cached;
        if (matches.size() >= MAX_FILTERS) return ItemStack.EMPTY;
        String source = key.getCompound("components").getString("ftbfiltersystem:filter");
        ItemStack match = source.length() > 16_384 ? ItemStack.EMPTY : resolve(source, adapter.get().getMatcher(stack, registries));
        matches.put(key, match);
        return match;
    }

    private ItemStack resolve(String source, Predicate<ItemStack> predicate) {
        var hint = HINT.matcher(source);
        var patches = componentPatches(source);
        int hints = 0;
        while (hint.find() && hints++ < MAX_HINTS) {
            var id = ResourceLocation.tryParse(hint.group(2));
            if (id == null) continue;
            if (hint.group(1).equals("item")) {
                var item = BuiltInRegistries.ITEM.getOptional(id);
                if (item.isEmpty()) continue;
                var candidate = matchingVariant(item.get().getDefaultInstance(), patches, predicate);
                if (!candidate.isEmpty()) return candidate;
            } else {
                if (tagBudget <= 0) continue;
                var tag = registries.lookupOrThrow(Registries.ITEM).get(TagKey.create(Registries.ITEM, id));
                if (tag.isEmpty()) continue;
                int allowance = MAX_TAG_CANDIDATES;
                int checks = 1 + patches.size();
                for (var item : tag.get()) {
                    if (allowance-- <= 0 || tagBudget < checks) break;
                    tagBudget -= checks;
                    var candidate = matchingVariant(item.value().getDefaultInstance(), patches, predicate);
                    if (!candidate.isEmpty()) return candidate;
                }
            }
        }
        int allowance = Math.min(fallbackBudget, MAX_FALLBACK_CANDIDATES);
        for (var item : BuiltInRegistries.ITEM) {
            if (allowance-- <= 0) break;
            fallbackBudget--;
            var candidate = item.getDefaultInstance();
            if (accepts(candidate, predicate)) return candidate;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack matchingVariant(ItemStack candidate, List<DataComponentPatch> patches,
                                             Predicate<ItemStack> predicate) {
        if (accepts(candidate, predicate)) return candidate;
        if (candidate.isEmpty() || isPlaceholder(candidate)) return ItemStack.EMPTY;
        for (var patch : patches) {
            var variant = candidate.copy();
            variant.applyComponents(patch);
            if (accepts(variant, predicate)) return variant;
        }
        return ItemStack.EMPTY;
    }

    private List<DataComponentPatch> componentPatches(String source) {
        var matcher = COMPONENTS.matcher(source);
        var patches = new ArrayList<DataComponentPatch>();
        int attempts = 0;
        while (matcher.find() && attempts++ < 8) {
            var reader = new StringReader(source);
            reader.setCursor(matcher.end());
            try {
                var tag = new TagParser(reader).readStruct();
                DataComponentPatch.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                        .result().ifPresent(patches::add);
            } catch (CommandSyntaxException ignored) {
                // A non-SNBT filter variant can still match a default stack.
            }
        }
        return patches;
    }

    private static boolean accepts(ItemStack candidate, Predicate<ItemStack> predicate) {
        return !candidate.isEmpty() && !isPlaceholder(candidate) && predicate.test(candidate)
                && !ItemMatchingSystem.INSTANCE.isItemFilter(candidate);
    }
}

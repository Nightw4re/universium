package com.universium.webcraft.exporter;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class EasyVillagerVariants {
    private static final ResourceLocation VILLAGER = ResourceLocation.parse("easy_villagers:villager");
    private static final ResourceLocation BREEDER = ResourceLocation.parse("easy_villagers:breeder");

    private EasyVillagerVariants() {}

    static Iterator<List<CatalogPreviewVariant>> batches(HolderLookup.Provider registries) {
        if (!BuiltInRegistries.ITEM.containsKey(VILLAGER) || !BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(VILLAGER)) {
            return List.<List<CatalogPreviewVariant>>of().iterator();
        }
        var types = BuiltInRegistries.VILLAGER_TYPE.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        var professions = BuiltInRegistries.VILLAGER_PROFESSION.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        boolean hasBreeder = BuiltInRegistries.ITEM.containsKey(BREEDER) && BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(BREEDER);
        return IntStream.range(0, types.size() * professions.size()).mapToObj(index ->
                variants(registries, types.get(index / professions.size()), professions.get(index % professions.size()), hasBreeder))
                .iterator();
    }

    private static List<CatalogPreviewVariant> variants(HolderLookup.Provider registries, ResourceLocation type,
                                                        ResourceLocation profession, boolean hasBreeder) {
        var stack = decode(registries, type, profession);
        if (stack.isEmpty()) return List.of();
        String id = type + "/" + profession;
        var options = List.of(
                new CatalogPreviewVariant.Option("biome", type.toString(), label(type)),
                new CatalogPreviewVariant.Option("profession", profession.toString(),
                        profession.toString().equals("minecraft:none") ? "Unemployed" : label(profession)));
        var variants = new ArrayList<CatalogPreviewVariant>(3);
        variants.add(new CatalogPreviewVariant(id, stack.get(), options));
        if (hasBreeder) {
            variants.add(new CatalogPreviewVariant("villager_1/" + id,
                    breederStack(registries, stack.get(), ItemStack.EMPTY), options));
            variants.add(new CatalogPreviewVariant("villager_2/" + id,
                    breederStack(registries, ItemStack.EMPTY, stack.get()), options));
        }
        return variants;
    }

    private static ItemStack breederStack(HolderLookup.Provider registries, ItemStack first, ItemStack second) {
        var stack = BuiltInRegistries.ITEM.get(BREEDER).getDefaultInstance();
        var data = new CompoundTag();
        if (!first.isEmpty()) {
            data.put("Villager1", first.save(registries));
        }
        if (!second.isEmpty()) {
            data.put("Villager2", second.save(registries));
        }
        if (!data.isEmpty()) {
            BlockItem.setBlockEntityData(stack, BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BREEDER), data);
        }
        return stack;
    }

    private static Optional<ItemStack> decode(HolderLookup.Provider registries, ResourceLocation type,
                                              ResourceLocation profession) {
        var data = new CompoundTag();
        data.putString("type", type.toString());
        data.putString("profession", profession.toString());
        data.putInt("level", 1);
        var villager = new CompoundTag();
        villager.put("VillagerData", data);
        var components = new CompoundTag();
        components.put(VILLAGER.toString(), villager);
        var item = new CompoundTag();
        item.putString("id", VILLAGER.toString());
        item.putInt("count", 1);
        item.put("components", components);
        return ItemStack.parse(registries, item);
    }

    private static String label(ResourceLocation id) {
        return Arrays.stream(id.getPath().split("[/_-]+"))
                .filter(word -> !word.isEmpty()).map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }
}

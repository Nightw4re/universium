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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

final class EasyVillagerVariants {
    private static final ResourceLocation VILLAGER = ResourceLocation.parse("easy_villagers:villager");
    private static final ResourceLocation BREEDER = ResourceLocation.parse("easy_villagers:breeder");

    private EasyVillagerVariants() {}

    static List<CatalogPreviewVariant> collect(HolderLookup.Provider registries) {
        if (!BuiltInRegistries.ITEM.containsKey(VILLAGER) || !BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(VILLAGER)) {
            return List.of();
        }
        var types = BuiltInRegistries.VILLAGER_TYPE.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        var professions = BuiltInRegistries.VILLAGER_PROFESSION.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        boolean hasBreeder = BuiltInRegistries.ITEM.containsKey(BREEDER) && BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(BREEDER);
        var variants = new ArrayList<CatalogPreviewVariant>();
        for (var type : types) {
            for (var profession : professions) {
                String id = type + "/" + profession;
                var options = List.of(
                        new CatalogPreviewVariant.Option("biome", type.toString(), label(type)),
                        new CatalogPreviewVariant.Option("profession", profession.toString(),
                                profession.toString().equals("minecraft:none") ? "Unemployed" : label(profession)));
                decode(registries, type, profession).ifPresent(stack -> {
                    variants.add(new CatalogPreviewVariant(id, stack, options));
                    if (hasBreeder) {
                        variants.add(new CatalogPreviewVariant("villager_1/" + id,
                                breederStack(registries, stack, ItemStack.EMPTY), options));
                        variants.add(new CatalogPreviewVariant("villager_2/" + id,
                                breederStack(registries, ItemStack.EMPTY, stack), options));
                    }
                });
            }
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

package com.universium.webcraft.exporter;

import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogItemDataExportTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void exportsEffectiveComponentsAndKeepsTheOriginalNbtTypes() {
        var stack = Items.LEATHER_HELMET.getDefaultInstance();
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x112233, true));
        var tag = new CompoundTag();
        tag.putLong("serial", Long.MAX_VALUE);
        tag.putByteArray("bytes", new byte[]{1, 2});
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.remove(DataComponents.REPAIR_COST);
        var data = CatalogItemDataExport.stackData(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), stack);
        var components = data.getAsJsonObject("components");
        assertEquals(0x112233, components.getAsJsonObject("minecraft:dyed_color").get("rgb").getAsInt());
        assertEquals(1, components.get("minecraft:max_stack_size").getAsInt());
        assertFalse(components.has("minecraft:repair_cost"));
        var nbt = data.getAsJsonObject("nbt").get("minecraft:custom_data").getAsString();
        assertTrue(nbt.contains("9223372036854775807L"));
        assertTrue(nbt.contains("[B;1B,2B]"));
    }

    @Test
    void resolvesTranslationsAndInheritedAndLegacyFormatting() {
        var line = Component.translatable("item.minecraft.iron_pickaxe").withStyle(ChatFormatting.ITALIC)
                .append(Component.literal(" žluťoučký 😀").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" §ntext"));
        var spans = CatalogItemDataExport.spans(line);
        assertEquals("Iron Pickaxe", spans.get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(spans.get(0).getAsJsonObject().get("italic").getAsBoolean());
        assertEquals(" žluťoučký 😀", spans.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals(ChatFormatting.GREEN.getColor().intValue(), spans.get(1).getAsJsonObject().get("color").getAsInt());
        assertTrue(spans.get(1).getAsJsonObject().get("bold").getAsBoolean());
        assertTrue(spans.get(1).getAsJsonObject().get("italic").getAsBoolean());
        assertTrue(spans.get(3).getAsJsonObject().get("underlined").getAsBoolean());
    }

    @Test
    void exportsModdedArgbTextColorsAsRgb() {
        var line = Component.literal("Mod tooltip").withStyle(style -> style.withColor(TextColor.fromRgb(0xff112233)));
        var span = CatalogItemDataExport.spans(line).get(0).getAsJsonObject();
        assertEquals(0x112233, span.get("color").getAsInt());
    }
}

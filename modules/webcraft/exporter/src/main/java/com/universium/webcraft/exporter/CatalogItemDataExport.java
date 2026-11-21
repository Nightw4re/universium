package com.universium.webcraft.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.StringJoiner;

final class CatalogItemDataExport {
    private static final int TOOLTIP_LIMIT = 128 * 1024;

    private CatalogItemDataExport() {}

    static boolean write(Minecraft minecraft, HolderLookup.Provider registries, ItemStack stack,
                         String variant, Set<ExportSection> sections, ExportArchive.EntryWriter writer) throws IOException {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String suffix = variant == null ? "" : variant.replace(":", "%3A").replace("/", "%2F");
        String file = id.getNamespace() + "/" + id.getPath() + "@" + suffix + ".json";
        boolean wroteStack = false;
        if (sections.contains(ExportSection.ITEMS)) {
            try {
                wroteStack = writeBounded(writer, "catalog-data/stacks/" + file, stackData(registries, stack), 256 * 1024);
            } catch (RuntimeException ignored) {
                // A stack whose components cannot be read can still have a tooltip and an icon.
            }
        }
        if (!sections.contains(ExportSection.TOOLTIPS)) return wroteStack;
        try {
            boolean wroteTooltip = writeBounded(writer, "catalog-data/tooltips/" + file, tooltip(minecraft, stack), TOOLTIP_LIMIT);
            return wroteStack || wroteTooltip;
        } catch (RuntimeException ignored) {
            // A client tooltip hook can require unavailable state. Leave that tooltip absent.
            return wroteStack;
        }
    }

    static JsonObject stackData(HolderLookup.Provider registries, ItemStack stack) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var components = new JsonObject();
        var nbt = new JsonObject();
        var omitted = new JsonArray();
        for (var component : stack.getComponents()) {
            ResourceLocation key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            if (key == null) continue;
            String id = key.toString();
            try {
                if (component.value() instanceof CustomData customData) {
                    nbt.addProperty(id, customData.copyTag().toString());
                }
                var value = component.encodeValue(ops).result();
                if (value.isPresent()) {
                    components.add(id, value.get());
                } else {
                    omitted.add(id);
                }
            } catch (RuntimeException exception) {
                omitted.add(id);
            }
        }
        var result = new JsonObject();
        result.addProperty("version", 1);
        result.addProperty("count", stack.getCount());
        result.add("components", components);
        result.add("nbt", nbt);
        result.add("omittedComponents", omitted);
        return result;
    }

    private static JsonObject tooltip(Minecraft minecraft, ItemStack stack) {
        var capture = TooltipKeyProbe.capture(() -> tooltipState(minecraft, stack.copy()));
        var result = capture.baseline();
        result.addProperty("version", 1);
        result.addProperty("locale", minecraft.getLanguageManager().getSelected());
        if (!capture.alternatives().isEmpty()) {
            var alternatives = new JsonArray();
            result.add("alternatives", alternatives);
            for (var alternative : capture.alternatives()) {
                var state = alternative.state();
                var keys = new JsonArray();
                var label = new StringJoiner(" + ");
                for (int keyCode : alternative.keys()) {
                    var key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
                    keys.add(key.getName());
                    label.add(key.getDisplayName().getString());
                }
                String shortcut = label.toString();
                if (shortcut.isBlank() || shortcut.length() > 128) continue;
                state.add("keys", keys);
                state.addProperty("label", shortcut);
                alternatives.add(state);
                if (result.toString().getBytes(StandardCharsets.UTF_8).length > TOOLTIP_LIMIT) {
                    alternatives.remove(alternatives.size() - 1);
                }
            }
            if (alternatives.isEmpty()) result.remove("alternatives");
        }
        return result;
    }

    private static JsonObject tooltipState(Minecraft minecraft, ItemStack stack) {
        var elements = new ArrayList<Either<FormattedText, TooltipComponent>>();
        // Use the client's real item and NeoForge tooltip hooks in the current world.
        stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), null, TooltipFlag.Default.NORMAL)
                .forEach(line -> elements.add(Either.left(line)));
        if (!elements.isEmpty()) {
            stack.getTooltipImage().ifPresent(image -> elements.add(1, Either.right(image)));
            var window = minecraft.getWindow();
            var event = new RenderTooltipEvent.GatherComponents(stack, window.getGuiScaledWidth(),
                    window.getGuiScaledHeight(), elements, -1);
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) elements.clear();
        }
        var lines = new JsonArray();
        boolean hasGraphics = false;
        for (var element : elements) {
            if (element.left().isPresent()) {
                lines.add(spans(element.left().get()));
            } else {
                hasGraphics = true;
            }
        }
        var result = new JsonObject();
        result.addProperty("hasGraphicElements", hasGraphics);
        result.add("lines", lines);
        return result;
    }

    static JsonArray spans(FormattedText line) {
        var spans = new JsonArray();
        var text = new StringBuilder();
        Style[] current = {Style.EMPTY};
        StringDecomposer.iterateFormatted(line, Style.EMPTY, (index, style, codePoint) -> {
            if (!style.equals(current[0])) {
                appendSpan(spans, text, current[0]);
                current[0] = style;
            }
            text.appendCodePoint(codePoint);
            return true;
        });
        appendSpan(spans, text, current[0]);
        return spans;
    }

    private static void appendSpan(JsonArray spans, StringBuilder text, Style style) {
        if (text.isEmpty()) return;
        var span = new JsonObject();
        span.addProperty("text", text.toString());
        // Minecraft's font renderer uses the RGB channels even when a mod supplies ARGB.
        if (style.getColor() != null) span.addProperty("color", style.getColor().getValue() & 0xffffff);
        if (style.isItalic()) span.addProperty("italic", true);
        if (style.isBold()) span.addProperty("bold", true);
        if (style.isUnderlined()) span.addProperty("underlined", true);
        if (style.isStrikethrough()) span.addProperty("strikethrough", true);
        if (style.isObfuscated()) span.addProperty("obfuscated", true);
        spans.add(span);
        text.setLength(0);
    }

    private static boolean writeBounded(ExportArchive.EntryWriter writer, String path, JsonElement data, int limit) throws IOException {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) return false;
        writer.write(path, bytes);
        return true;
    }
}

package com.universium.webcraft.quests;

import com.google.gson.JsonParseException;
import dev.ftb.mods.ftblibrary.util.TextComponentParser;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.Optional;

final class QuestText {
    static final int MAX_TITLE_CHARS = 192;
    static final int MAX_DESCRIPTION_CHARS = 2_048;
    private static final int MAX_SOURCE_CHARS = 16_384;
    private static final int MAX_DESCRIPTION_LINES = 128;

    private QuestText() {}

    static String title(QuestObjectBase object) {
        String raw = object.getRawTitle();
        String title = raw.isEmpty() ? plain(object.getAltTitle(), MAX_TITLE_CHARS)
                : plain(raw, object.holderLookup(), MAX_TITLE_CHARS);
        return title.isEmpty() ? object.getCodeString() : title;
    }

    static String description(Quest quest) {
        var output = new StringBuilder();
        int lines = 0;
        for (String raw : quest.getRawDescription()) {
            if (++lines > MAX_DESCRIPTION_LINES || output.length() >= MAX_DESCRIPTION_CHARS - 1) break;
            if (Quest.PAGEBREAK_CODE.equals(raw)) continue;
            int remaining = MAX_DESCRIPTION_CHARS - output.length() - 1;
            String line = plain(raw, quest.holderLookup(), remaining);
            if (!line.isEmpty()) {
                if (!output.isEmpty()) output.append('\n');
                output.append(line);
            }
        }
        return output.toString();
    }

    static String plain(String raw, HolderLookup.Provider registries, int limit) {
        if (raw.length() > MAX_SOURCE_CHARS) return "";
        String text = raw.strip();
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                var component = Component.Serializer.fromJson(text, registries);
                if (component != null) return plain(component, limit);
            } catch (JsonParseException ignored) {
                // The same delimiters also introduce FTB rich text.
            }
        }
        // Drop FTB substitutions such as widgets and quest links without evaluating client data.
        return plain(TextComponentParser.parse(text, ignored -> Component.empty()), limit);
    }

    private static String plain(Component component, int limit) {
        var output = new StringBuilder();
        component.visit((style, text) -> {
            if (!style.isObfuscated()) {
                int end = Math.min(text.length(), limit - output.length());
                if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                output.append(text, 0, end);
                if (end < text.length() || output.length() == limit) return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return output.toString().strip();
    }
}

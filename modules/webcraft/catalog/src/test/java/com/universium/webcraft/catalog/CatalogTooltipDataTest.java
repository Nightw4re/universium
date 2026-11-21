package com.universium.webcraft.catalog;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogTooltipDataTest {
    @Test
    void acceptsOlderTooltipsAndOptionalKeyboardAlternatives() {
        var data = JsonParser.parseString("""
                {"version":1,"locale":"cs_cz","hasGraphicElements":false,"lines":[[{"text":"Podržte Shift"}]]}
                """).getAsJsonObject();
        assertTrue(CatalogData.validTooltip(data));
        data.add("alternatives", JsonParser.parseString("""
                [{"keys":["key.keyboard.left.shift","key.keyboard.n"],"label":"Levý Shift + N",
                  "hasGraphicElements":true,"lines":[[{"text":"Podrobnosti","color":255}]]}]
                """));
        assertTrue(CatalogData.validTooltip(data));

        var alternatives = data.getAsJsonArray("alternatives");
        alternatives.add(alternatives.get(0).deepCopy());
        assertFalse(CatalogData.validTooltip(data));
        alternatives.remove(1);
        alternatives.get(0).getAsJsonObject().getAsJsonArray("keys").add("mouse");
        assertFalse(CatalogData.validTooltip(data));
    }
}

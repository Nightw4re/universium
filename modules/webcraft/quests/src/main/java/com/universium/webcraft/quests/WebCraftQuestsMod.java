package com.universium.webcraft.quests;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import java.util.Map;

@Mod(WebCraftQuestsMod.MOD_ID)
public final class WebCraftQuestsMod {
    public static final String MOD_ID = "webcraft_quests";

    public WebCraftQuestsMod(IEventBus modBus) {
        modBus.addListener(this::register);
    }

    private void register(RegisterWebCraftModulesEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "quests"),
                Map.of("data", new QuestDataRoute(FtbQuestReader::capture)));
    }
}

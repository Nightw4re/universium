package com.universium.webcraft.equipment;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import java.nio.file.Path;
import java.util.Map;

@Mod(WebCraftEquipmentMod.MOD_ID)
public final class WebCraftEquipmentMod {
    public static final String MOD_ID = "webcraft_equipment";

    private static final ResourceLocation MODULE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "equipment");

    private final EquipmentRoute equipment = new EquipmentRoute(new EquipmentPreviews(Path.of("webcraft-assets", "client.jar")));

    public WebCraftEquipmentMod(IEventBus modBus) {
        modBus.addListener(this::registerModules);
    }

    private void registerModules(RegisterWebCraftModulesEvent event) {
        event.register(MODULE_ID, Map.of("loadout", equipment::read));
    }
}

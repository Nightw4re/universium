package com.universium.webcraft.equipment;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftReloadEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.Map;

@Mod(WebCraftEquipmentMod.MOD_ID)
public final class WebCraftEquipmentMod {
    public static final String MOD_ID = "webcraft_equipment";

    private static final ResourceLocation MODULE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "equipment");
    private final EquipmentPreviews previews = new EquipmentPreviews(Path.of("webcraft-assets", "client.jar"));
    private final EquipmentRoute equipment;

    public WebCraftEquipmentMod(IEventBus modBus, ModContainer container) {
        var builder = new ModConfigSpec.Builder();
        var allowOffline = builder.comment("Allow authorized equipment and inventory requests to read the last saved player data.")
                .define("allowOfflineEquipment", false);
        container.registerConfig(ModConfig.Type.SERVER, builder.build(), "webcraft-equipment.toml");
        equipment = new EquipmentRoute(previews, allowOffline::get);
        modBus.addListener(this::registerModules);
        NeoForge.EVENT_BUS.addListener((WebCraftReloadEvent event) -> previews.reload());
    }

    private void registerModules(RegisterWebCraftModulesEvent event) {
        event.register(MODULE_ID, Map.of("loadout", equipment::read, "inventory", equipment::readInventory));
    }
}

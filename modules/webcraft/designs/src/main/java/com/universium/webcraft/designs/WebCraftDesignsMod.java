package com.universium.webcraft.designs;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReloadEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Path;
import java.util.Map;

@Mod(WebCraftDesignsMod.MOD_ID)
public final class WebCraftDesignsMod {
    static final String MOD_ID = "webcraft_designs";
    private final WebCraftPreviewPack pack = new WebCraftPreviewPack(Path.of("webcraft-assets", "previews.zip"));
    private final DesignRoutes banners = new DesignRoutes(pack);
    private final DesignRoutes armor = new DesignRoutes(pack);
    private final DesignRoutes armorGeometry = new DesignRoutes(pack);
    private final ProfileSkinRoute profileSkins = new ProfileSkinRoute();

    public WebCraftDesignsMod(IEventBus modBus) {
        modBus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerStartedEvent event) -> load());
        NeoForge.EVENT_BUS.addListener((WebCraftReloadEvent event) -> load());
    }

    private void register(RegisterWebCraftModulesEvent event) {
        event.register(id("banners"), Map.of(
                "data", (WebCraftReadRoute.PublicRoute) banners::data,
                "texture", (WebCraftReadRoute.PublicRoute) banners::texture));
        event.register(id("armor"), Map.of(
                "data", (WebCraftReadRoute.PublicRoute) armor::data,
                "texture", (WebCraftReadRoute.PublicRoute) armor::texture,
                "geometry", (WebCraftReadRoute.PublicRoute) armorGeometry::data,
                "skin", (WebCraftReadRoute.PublicRoute) profileSkins::read));
    }

    private void load() {
        banners.load(id("banners"));
        armor.load(id("armor"));
        armorGeometry.load(id("armor_geometry"));
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }
}

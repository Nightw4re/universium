package com.universium.webcraft.api;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Fired by WebCraft during common setup to collect modules and their read routes.
 * Add-ons subscribe on their mod event bus and register synchronously in the event handler.
 */
public final class RegisterWebCraftModulesEvent extends Event implements IModBusEvent {
    private final BiConsumer<ResourceLocation, Map<String, WebCraftReadRoute>> registrar;

    /** Creates the event with the registration callback supplied by the core. */
    public RegisterWebCraftModulesEvent(BiConsumer<ResourceLocation, Map<String, WebCraftReadRoute>> registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    /** Registers a module under the add-on's namespace. */
    public void register(ResourceLocation moduleId) {
        register(moduleId, Map.of());
    }

    /**
     * Registers a unique module ID and its exact GET routes. Route names are lowercase segments
     * such as "equipment". Routes require the viewer token by default. A
     * {@link WebCraftReadRoute.PublicRoute} is public, while a
     * {@link WebCraftReadRoute.InternalRoute} uses the undiscoverable internal module path.
     */
    public void register(ResourceLocation moduleId, Map<String, WebCraftReadRoute> routes) {
        registrar.accept(Objects.requireNonNull(moduleId, "moduleId"), Map.copyOf(routes));
    }
}

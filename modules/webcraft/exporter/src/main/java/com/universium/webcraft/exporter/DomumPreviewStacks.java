package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class DomumPreviewStacks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private DomumPreviewStacks() {}

    static ItemStack withDefaultMaterials(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)
                || !BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("domum_ornamentum")) return stack;
        var api = Domum.API;
        if (api == null || !api.blockType().isInstance(item.getBlock())) return stack;
        try {
            var data = api.read().invoke(null, stack);
            if (!Boolean.TRUE.equals(api.isEmpty().invoke(data))) return stack;

            // Unconfigured stacks cycle through valid materials in the game's item renderer.
            // Use the block's own defaults for a deterministic preview of the original stack.
            var materials = new LinkedHashMap<ResourceLocation, Block>();
            for (var component : (Collection<?>) api.components().invoke(item.getBlock())) {
                if (api.defaultMaterial().invoke(component) instanceof Block block) {
                    materials.put((ResourceLocation) api.componentId().invoke(component), block);
                }
            }
            if (materials.isEmpty()) return stack;
            var result = stack.copy();
            api.write().invoke(api.newData().newInstance(materials), result);
            return result;
        } catch (ReflectiveOperationException | LinkageError exception) {
            warn(exception);
            return stack;
        }
    }

    private static void warn(Throwable exception) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("WebCraft could not prepare default Domum Ornamentum preview materials", exception);
        }
    }

    private static final class Domum {
        private static final Api API = load();

        private static Api load() {
            try {
                var block = Class.forName("com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock");
                var component = Class.forName("com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent");
                var data = Class.forName("com.ldtteam.domumornamentum.client.model.data.MaterialTextureData");
                return new Api(block, block.getMethod("getComponents"), component.getMethod("getId"),
                        component.getMethod("getDefault"), data.getMethod("readFromItemStack", ItemStack.class),
                        data.getMethod("isEmpty"), data.getConstructor(Map.class), data.getMethod("writeToItemStack", ItemStack.class));
            } catch (ReflectiveOperationException | LinkageError exception) {
                warn(exception);
                return null;
            }
        }
    }

    private record Api(Class<?> blockType, Method components, Method componentId, Method defaultMaterial,
                       Method read, Method isEmpty, Constructor<?> newData, Method write) {}
}

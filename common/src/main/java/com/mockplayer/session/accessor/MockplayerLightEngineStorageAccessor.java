package com.mockplayer.session.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 LightEngine 的 protected storage（LayerLightSectionStorage）。 */
@Mixin(net.minecraft.world.level.lighting.LightEngine.class)
public interface MockplayerLightEngineStorageAccessor {

    @Accessor("storage")
    net.minecraft.world.level.lighting.LayerLightSectionStorage<?> mockplayer$getStorage();
}

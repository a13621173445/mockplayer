package com.mockplayer.session.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 LevelLightEngine 的 sky/block 光照引擎。 */
@Mixin(net.minecraft.world.level.lighting.LevelLightEngine.class)
public interface MockplayerLightEngineAccessor {

    @Accessor("skyEngine")
    net.minecraft.world.level.lighting.LightEngine<?, ?> mockplayer$getSkyEngine();

    @Accessor("blockEngine")
    net.minecraft.world.level.lighting.LightEngine<?, ?> mockplayer$getBlockEngine();
}

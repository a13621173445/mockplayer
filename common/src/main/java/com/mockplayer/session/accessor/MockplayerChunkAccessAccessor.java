package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.shorts.ShortList;

import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ChunkAccess 的 postProcessing 短列表（结构/红石待处理数据）。 */
@Mixin(ChunkAccess.class)
public interface MockplayerChunkAccessAccessor {

    @Accessor("postProcessing")
    ShortList[] mockplayer$getPostProcessing();

    @Accessor("skyLightSources")
    net.minecraft.world.level.lighting.ChunkSkyLightSources mockplayer$getSkyLightSources();
}

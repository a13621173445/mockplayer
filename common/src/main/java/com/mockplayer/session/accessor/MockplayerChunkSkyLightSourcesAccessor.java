package com.mockplayer.session.accessor;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ChunkSkyLightSources 的高度图 BitStorage（每区块 long[] 大头）。 */
@Mixin(ChunkSkyLightSources.class)
public interface MockplayerChunkSkyLightSourcesAccessor {

    @Accessor("heightmap")
    BitStorage mockplayer$getHeightmap();
}

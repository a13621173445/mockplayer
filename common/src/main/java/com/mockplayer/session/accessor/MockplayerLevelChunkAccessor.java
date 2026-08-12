package com.mockplayer.session.accessor;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ChunkAccess（LevelChunk 父类）的 heightmaps 表（高度图堆尺寸 O(1) 公式用）。 */
@Mixin(ChunkAccess.class)
public interface MockplayerLevelChunkAccessor {

    @Accessor("heightmaps")
    Map<Heightmap.Types, Heightmap> mockplayer$getHeightmaps();
}

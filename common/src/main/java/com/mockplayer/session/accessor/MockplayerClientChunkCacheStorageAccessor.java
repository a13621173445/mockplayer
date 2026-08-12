package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.atomic.AtomicReferenceArray;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ClientChunkCache$Storage（私有内部类）的 chunk 数组与跟踪集合。 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public interface MockplayerClientChunkCacheStorageAccessor {

    @Accessor("chunks")
    AtomicReferenceArray<net.minecraft.world.level.chunk.LevelChunk> mockplayer$getChunks();

    @Accessor("chunkRadius")
    int mockplayer$getChunkRadius();

    @Accessor("addedLoadedChunks")
    LongOpenHashSet[] mockplayer$getAddedLoadedChunks();

    @Accessor("removedLoadedChunks")
    LongOpenHashSet[] mockplayer$getRemovedLoadedChunks();

    @Accessor("addedEmptySections")
    LongOpenHashSet[] mockplayer$getAddedEmptySections();

    @Accessor("removedEmptySections")
    LongOpenHashSet[] mockplayer$getRemovedEmptySections();
}

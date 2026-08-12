package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.client.color.block.BlockTintCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 BlockTintCache 的预分配缓存表（容量 256、负载 0.25）与读写锁。 */
@Mixin(BlockTintCache.class)
public interface MockplayerBlockTintCacheAccessor {

    @Accessor("cache")
    Long2ObjectLinkedOpenHashMap<?> mockplayer$getCache();

    @Accessor("lock")
    java.util.concurrent.locks.ReentrantReadWriteLock mockplayer$getLock();
}

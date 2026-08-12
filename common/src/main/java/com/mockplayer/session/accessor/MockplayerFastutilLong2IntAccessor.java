package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Long2IntOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Long2IntOpenHashMap.class)
public interface MockplayerFastutilLong2IntAccessor {
    @Accessor("key")
    long[] mockplayer$getKey();
}

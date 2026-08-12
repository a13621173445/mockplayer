package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Long2ObjectOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Long2ObjectOpenHashMap.class)
public interface MockplayerFastutilLong2ObjectAccessor {
    @Accessor("key")
    long[] mockplayer$getKey();
}

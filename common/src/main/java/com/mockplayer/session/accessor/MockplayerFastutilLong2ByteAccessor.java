package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Long2ByteOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Long2ByteOpenHashMap.class)
public interface MockplayerFastutilLong2ByteAccessor {
    @Accessor("key")
    long[] mockplayer$getKey();
}

package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Long2ObjectLinkedOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Long2ObjectLinkedOpenHashMap.class)
public interface MockplayerFastutilLong2ObjectLinkedAccessor {
    @Accessor("key")
    long[] mockplayer$getKey();
}

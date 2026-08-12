package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 LongOpenHashSet 的 key 数组（预分配容量按实际数组长度计）。 */
@Mixin(LongOpenHashSet.class)
public interface MockplayerLongOpenHashSetAccessor {

    @Accessor("key")
    long[] mockplayer$getKey();
}

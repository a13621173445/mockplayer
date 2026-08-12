package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Int2ObjectLinkedOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Int2ObjectLinkedOpenHashMap.class)
public interface MockplayerFastutilInt2ObjectLinkedAccessor {
    @Accessor("key")
    int[] mockplayer$getKey();
}

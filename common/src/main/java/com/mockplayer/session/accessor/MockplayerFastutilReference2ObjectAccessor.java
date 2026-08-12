package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Reference2ObjectOpenHashMap 的 key 数组（预分配容量）。 */
@Mixin(Reference2ObjectOpenHashMap.class)
public interface MockplayerFastutilReference2ObjectAccessor {
    @Accessor("key")
    Object[] mockplayer$getKey();
}

package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ObjectOpenCustomHashSet 的 key 数组（预分配容量）。 */
@Mixin(ObjectOpenCustomHashSet.class)
public interface MockplayerFastutilObjectOpenCustomHashSetAccessor {
    @Accessor("key")
    Object[] mockplayer$getKey();
}

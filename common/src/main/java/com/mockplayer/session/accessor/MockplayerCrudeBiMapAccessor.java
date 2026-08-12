package com.mockplayer.session.accessor;

import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 CrudeIncrementalIntIdentityHashBiMap 的三个私有数组（keys/values/byId）。
 *
 * 数组长度 = 当前容量，直接决定 palette 的堆字节；O(1) 读取，不遍历。
 */
@Mixin(CrudeIncrementalIntIdentityHashBiMap.class)
public interface MockplayerCrudeBiMapAccessor {

    @Accessor("keys")
    Object[] mockplayer$getKeys();

    @Accessor("values")
    int[] mockplayer$getValues();

    @Accessor("byId")
    Object[] mockplayer$getById();
}

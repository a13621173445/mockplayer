package com.mockplayer.session.accessor;

import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 HashMapPalette 的私有 bimap（其内部三个数组长度决定 palette 堆尺寸）。 */
@Mixin(HashMapPalette.class)
public interface MockplayerHashMapPaletteAccessor {

    @Accessor("values")
    CrudeIncrementalIntIdentityHashBiMap<?> mockplayer$getValues();
}

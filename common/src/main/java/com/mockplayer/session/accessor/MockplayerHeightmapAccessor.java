package com.mockplayer.session.accessor;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.levelgen.Heightmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Heightmap 的私有 BitStorage（其 long[] 长度决定高度图堆字节）。 */
@Mixin(Heightmap.class)
public interface MockplayerHeightmapAccessor {

    @Accessor("data")
    BitStorage mockplayer$getData();
}

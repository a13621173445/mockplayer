package com.mockplayer.session.accessor;

import net.minecraft.world.level.chunk.DataLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 DataLayer 的 protected byte[] data（判空决定是否计入 2048 数组）。 */
@Mixin(DataLayer.class)
public interface MockplayerDataLayerAccessor {

    @Accessor("data")
    byte[] mockplayer$getData();
}

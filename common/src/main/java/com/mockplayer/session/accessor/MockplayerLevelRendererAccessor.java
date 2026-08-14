package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LevelRenderer 的破坏进度表（26.1.2 中 destroyingBlocks/destructionProgress
 * 在 LevelRenderer 上；26.2 移入了 ClientLevel），供假人 level 内存记账使用。
 */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public interface MockplayerLevelRendererAccessor {

    @Accessor("destroyingBlocks")
    Int2ObjectMap<?> mockplayer$getDestroyingBlocks();

    @Accessor("destructionProgress")
    Long2ObjectMap<?> mockplayer$getDestructionProgress();
}

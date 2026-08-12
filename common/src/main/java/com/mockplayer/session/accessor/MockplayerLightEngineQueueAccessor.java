package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.world.level.lighting.LightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 LightEngine 的待处理光照队列（积压的 long 数组）。 */
@Mixin(LightEngine.class)
public interface MockplayerLightEngineQueueAccessor {

    @Accessor("blockNodesToCheck")
    LongOpenHashSet mockplayer$getBlockNodesToCheck();

    @Accessor("decreaseQueue")
    LongArrayFIFOQueue mockplayer$getDecreaseQueue();

    @Accessor("increaseQueue")
    LongArrayFIFOQueue mockplayer$getIncreaseQueue();
}

package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 fastutil 长整 FIFO 队列的底层 long[]（光照传播队列）。 */
@Mixin(LongArrayFIFOQueue.class)
public interface MockplayerLongArrayFIFOQueueAccessor {

    @Accessor("array")
    long[] mockplayer$getArray();
}

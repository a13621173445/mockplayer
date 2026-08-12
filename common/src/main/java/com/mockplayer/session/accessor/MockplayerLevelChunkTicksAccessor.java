package com.mockplayer.session.accessor;

import java.util.List;
import java.util.Queue;
import java.util.Set;

import net.minecraft.world.ticks.LevelChunkTicks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 LevelChunkTicks 的 tick 队列/待处理列表/去重集合。 */
@Mixin(LevelChunkTicks.class)
public interface MockplayerLevelChunkTicksAccessor {

    @Accessor("tickQueue")
    Queue<?> mockplayer$getTickQueue();

    @Accessor("pendingTicks")
    List<?> mockplayer$getPendingTicks();

    @Accessor("ticksPerPosition")
    Set<?> mockplayer$getTicksPerPosition();
}

package com.mockplayer.session.accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 ChunkMap.updateChunkTracking（private）：原版只在玩家移动（区块变化）时刷新
 * chunk 跟踪视距，假人静止时改 viewDistance（chunkRadius 命令）不会触发服务端发新 chunk。
 */
@Mixin(ChunkMap.class)
public interface MockplayerChunkMapAccessor {

    @Invoker("updateChunkTracking")
    void mockplayer$updateChunkTracking(ServerPlayer player);
}

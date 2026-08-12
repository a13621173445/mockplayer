package com.mockplayer.memory;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotWorldMemory;
import com.mockplayer.api.event.BotListener;

import net.minecraft.core.BlockPos;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * 假人世界内存记账（可插拔模块核心，仅依赖 API 事件）。
 *
 * 维护：区块进出一次估算、实体进出一次估算、section 序列化尺寸变化 O(1) 差值、
 * 方块实体数据新值差值；查询 O(1)。重生/换维/断开时清空。
 */
public final class WorldMemoryAccountant implements BotListener, BotWorldMemory {

    private long total;
    private final Map<ChunkPos, Long> chunkBytes = new HashMap<>();
    private final Map<Integer, Long> entityBytes = new HashMap<>();
    /** 方块实体数据字节（已包含在区块记账内，仅作差值基线）。 */
    private final Map<BlockPos, Long> blockEntityBytes = new HashMap<>();

    @Override
    public void onChunkLoaded(Bot bot, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        // 先移除该区块内可能先到的独立 BE 记账（差值已含在 total 中，需扣除防双计）
        total -= removeBlockEntitiesInChunk(pos);
        long bytes = MemoryEstimator.estimateChunk(chunk);
        for (var entry : chunk.getBlockEntities().entrySet()) {
            long beBytes = MemoryEstimator.estimateBlockEntity(
                    entry.getValue(), chunk.getLevel().registryAccess());
            blockEntityBytes.put(entry.getKey(), beBytes);
            bytes += beBytes;
        }
        Long old = chunkBytes.put(pos, bytes);
        if (old != null) {
            total -= old;
        }
        total += bytes;
    }

    @Override
    public void onChunkUnloaded(Bot bot, ChunkPos pos) {
        Long bytes = chunkBytes.remove(pos);
        if (bytes != null) {
            total -= bytes;
        }
        total -= removeBlockEntitiesInChunk(pos);
    }

    @Override
    public void onEntityAdded(Bot bot, Entity entity) {
        long bytes = MemoryEstimator.estimateEntity(entity, entity.level().registryAccess());
        Long old = entityBytes.put(entity.getId(), bytes);
        if (old != null) {
            total -= old;
        }
        total += bytes;
    }

    @Override
    public void onEntityRemoved(Bot bot, int entityId) {
        Long bytes = entityBytes.remove(entityId);
        if (bytes != null) {
            total -= bytes;
        }
    }

    @Override
    public void onSectionDataChanged(Bot bot, ChunkPos pos, int sectionIndex,
                                     long oldSerializedBytes, long newSerializedBytes) {
        if (oldSerializedBytes == newSerializedBytes) {
            return;
        }
        long delta = newSerializedBytes - oldSerializedBytes;
        total += delta;
        chunkBytes.computeIfPresent(pos, (p, v) -> v + delta);
    }

    @Override
    public void onBlockEntityData(Bot bot, BlockPos pos, long newDataBytes) {
        Long old = blockEntityBytes.put(pos, newDataBytes);
        long delta = old == null ? newDataBytes : newDataBytes - old;
        total += delta;
        // 同步到所属区块记账，保证区块卸载时扣除的是最新值
        chunkBytes.computeIfPresent(
                new ChunkPos(net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),
                        net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ())),
                (p, v) -> v + delta);
    }

    @Override
    public void onRespawn(Bot bot) {
        reset();
    }

    @Override
    public void onDimensionChange(Bot bot, ResourceKey<Level> from, ResourceKey<Level> to) {
        reset();
    }

    @Override
    public void onDisconnected(Bot bot, DisconnectionDetails details) {
        reset();
    }

    /** 当前估算字节（查询 O(1)）。 */
    public long estimatedBytes() {
        return total;
    }

    /** 清空全部记账（世界重建）。 */
    public void reset() {
        total = 0;
        chunkBytes.clear();
        entityBytes.clear();
        blockEntityBytes.clear();
    }

    /** 移除某区块内的独立 BE 记账并返回其字节（供 total 扣除）。 */
    private long removeBlockEntitiesInChunk(ChunkPos pos) {
        long removed = 0;
        var it = blockEntityBytes.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            BlockPos p = entry.getKey();
            if (net.minecraft.core.SectionPos.blockToSectionCoord(p.getX()) == pos.x()
                    && net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ()) == pos.z()) {
                removed += entry.getValue();
                it.remove();
            }
        }
        return removed;
    }
}

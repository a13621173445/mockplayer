package com.mockplayer.memory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * 世界内存估算器（结构级口径，纯公式不依赖 agent）。
 *
 * 已用独立实验验证（tmp/memexp）：静态对象图按「头 + 字段 + 对齐」公式与
 * Instrumentation 真实尺寸完全一致；运行时变化（调色板扩容/方块实体数据）
 * 由事件差值维护，见 {@link WorldMemoryAccountant}。
 */
public final class MemoryEstimator {

    private MemoryEstimator() {
    }

    /** 区块估算：区块对象浅尺寸 + 每个非空 section（浅尺寸 + 序列化存储字节）。 */
    public static long estimateChunk(LevelChunk chunk) {
        long total = LayoutSizes.shallowSize(chunk.getClass());
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            total += LayoutSizes.shallowSize(section.getClass());
            total += section.getStates().getSerializedSize();
        }
        return total;
    }

    /** 方块实体估算：对象浅尺寸 + NBT 序列化数据字节。 */
    public static long estimateBlockEntity(BlockEntity blockEntity, HolderLookup.Provider registry) {
        return LayoutSizes.shallowSize(blockEntity.getClass())
                + nbtBytes(registry, output -> blockEntity.saveWithId(output));
    }

    /** 实体估算：对象浅尺寸 + NBT 序列化数据字节。 */
    public static long estimateEntity(Entity entity, HolderLookup.Provider registry) {
        return LayoutSizes.shallowSize(entity.getClass())
                + nbtBytes(registry, output -> entity.saveWithoutId(output));
    }

    /** 把对象序列化进 TagValueOutput 并返回 NBT 精确字节。 */
    private static long nbtBytes(HolderLookup.Provider registry,
                                 java.util.function.Consumer<TagValueOutput> save) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
        save.accept(output);
        CompoundTag tag = output.buildResult();
        return tag == null ? 0 : tag.sizeInBytes();
    }
}

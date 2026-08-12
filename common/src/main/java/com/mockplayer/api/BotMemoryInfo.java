package com.mockplayer.api;

/**
 * 假人内存信息（/query &lt;player&gt; memory 与 {@link Bot#memoryInfo()} 共用）。
 *
 * 口径：
 * - JVM 堆三个字段来自 {@link Runtime}，真实值；
 * - Mod 侧字节字段为记账估算（不含 Map 桶、对象头外引用、容器/数组槽位等开销）：
 *   String/UUID/Map 节点按 HotSpot 64 位压缩指针布局公式，物品按 Mojang
 *   {@code Tag.sizeInBytes()} 序列化尺寸；
 * - worldBytes 来自可插拔世界内存记账模块（结构级估算：区块/实体/方块实体，
 *   查询 O(1)，口径见 com.mockplayer.memory.WorldMemoryAccountant）；未挂载为 0；
 * - entityCount / chunkCount 为假人 level 原版精确计数。
 */
public record BotMemoryInfo(
        long jvmUsedBytes,
        long jvmCommittedBytes,
        long jvmMaxBytes,
        int botCount,
        long chatBytes,
        long soundBytes,
        long particleBytes,
        int packetCount,
        long onlinePlayersBytes,
        long eventCacheBytes,
        long containerBytes,
        long inventoryBytes,
        long worldBytes,
        int entityCount,
        int chunkCount
) {
    /** Mod 侧精确记账总和（不含 packetCount，数据包只计数不猜字节）。 */
    public long trackedBytes() {
        return this.chatBytes + this.soundBytes + this.particleBytes
                + this.onlinePlayersBytes + this.eventCacheBytes
                + this.containerBytes + this.inventoryBytes;
    }

    /** 显示用总字节：mod 侧精确记账 + 世界内存估算（名牌/GUI 共用）。 */
    public long displayBytes() {
        return this.trackedBytes() + this.worldBytes;
    }
}

package com.mockplayer.api;

/**
 * 假人内存信息（/query &lt;player&gt; memory 与 {@link Bot#memoryInfo()} 共用）。
 *
 * 口径：
 * - JVM 堆三个字段来自 {@link Runtime}，真实值；
 * - Mod 侧字节字段为记账估算（不含 Map 桶、对象头外引用、容器/数组槽位等开销）：
 *   String/UUID/Map 节点按 HotSpot 64 位压缩指针布局公式，物品按 Mojang
 *   {@code Tag.sizeInBytes()} 序列化尺寸；
 * - 原版 ClientLevel 内部（区块/实体）的字节归属在无 Java agent 时物理上无法
 *   精确测量，因此只上报精确的 entityCount / chunkCount，不冒充字节数。
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
        int entityCount,
        int chunkCount
) {
    /** Mod 侧精确记账总和（不含 packetCount，数据包只计数不猜字节）。 */
    public long trackedBytes() {
        return this.chatBytes + this.soundBytes + this.particleBytes
                + this.onlinePlayersBytes + this.eventCacheBytes
                + this.containerBytes + this.inventoryBytes;
    }
}

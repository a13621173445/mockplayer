package com.mockplayer.session;

import com.mockplayer.api.BotMemoryInfo;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.container.BotContainer;

import com.mojang.serialization.DataResult;

import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 假人内存信息采集（Bot.memoryInfo() 与 /query memory 共用，主线程调用）。
 *
 * 精确口径（无估算常数）：
 * - JVM 堆：Runtime 真实值；
 * - chat/sound/particle/eventCache：FakePlayerState / EventRecorder 写入时
 *   用 ExactBytes 精确记账（String 按 HotSpot 压缩指针布局）；
 * - onlinePlayers：查询时按 Map 节点 + UUID + String 精确公式计算；
 * - container/inventory：ItemStack 用 Mojang 自己的 CODEC 编码成 NBT 后取
 *   {@code Tag.sizeInBytes()}（序列化数据精确尺寸）；
 * - packetLog 只报精确条数（包对象堆字节需 javaagent，不猜）；
 * - 原版 ClientLevel 内部报精确 entityCount/chunkCount，不冒充字节数。
 */
public final class BotMemoryEstimator {

    private BotMemoryEstimator() {
    }

    public static BotMemoryInfo estimate(BotImpl bot) {
        FakePlayerState state = bot.session().getState();
        long chat = state.getChatBytes();
        long sound = state.getSoundBytes();
        long particle = state.getParticleBytes();
        int packets = state.getAllLastPackets().size();
        long online = state.getOnlinePlayers().entrySet().stream()
                .mapToLong(e -> ExactBytes.MAP_NODE + ExactBytes.UUID_OBJECT
                        + ExactBytes.stringBytes(e.getValue()))
                .sum();

        long events = 0;
        EventRecorder recorder = EventRecorderRegistry.get(bot.getName());
        if (recorder != null) {
            events = recorder.getCacheBytes();
        }

        long container = 0;
        Optional<BotContainer> open = bot.getContainer();
        if (open.isPresent()) {
            BotContainer c = open.get();
            for (int i = 0; i < c.getSize(); i++) {
                container += stackDataBytes(c.getSlot(i));
            }
            container += stackDataBytes(c.getCarried());
        }

        long inventory = 0;
        if (bot.getLocalPlayer() != null) {
            net.minecraft.world.entity.player.Inventory inv = bot.getLocalPlayer().getInventory();
            for (int i = 0; i < 36; i++) {
                inventory += stackDataBytes(inv.getItem(i));
            }
        }

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long committed = rt.totalMemory();
        long max = rt.maxMemory();
        int botCount = MockplayerApi.bots().getBots().size();

        int entityCount = 0;
        int chunkCount = 0;
        net.minecraft.client.multiplayer.ClientLevel level = bot.getLevel();
        if (level != null) {
            entityCount = level.getEntityCount();
            chunkCount = level.getChunkSource().getLoadedChunksCount();
        }

        return new BotMemoryInfo(used, committed, max, botCount,
                chat, sound, particle, packets, online, events, container, inventory,
                entityCount, chunkCount);
    }

    /** ItemStack 的精确序列化数据字节（Mojang CODEC → NBT sizeInBytes）。 */
    private static long stackDataBytes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        DataResult<net.minecraft.nbt.Tag> encoded = ItemStack.OPTIONAL_CODEC.encodeStart(NbtOps.INSTANCE, stack);
        return encoded.result()
                .map(tag -> (long) tag.sizeInBytes())
                .orElse(0L);
    }
}

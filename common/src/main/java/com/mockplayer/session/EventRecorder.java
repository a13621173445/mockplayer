package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.event.BotListener;
import com.mockplayer.config.MockplayerConfig;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Bot 事件记录器（/control listen on 时挂到目标 bot 私有事件总线）。
 *
 * 设计约束（主人拍板 2026-08-08）：
 * - 不常驻、不全局注册：listen on 挂、listen off 摘，其他 bot 保持惰性零开销
 * - 低频事件：存缓存明细 + 实时推送到聊天栏（i18n）
 * - 高频事件 onTick/onMove：不推送，节流采样存明细（onTick 每 20 tick 一条快照；
 *   onMove 位移 ≥0.5 块采一条），其余只计数 + 更新最新位置
 * - 缓存固定 50 条环形，格式化延迟到 /control events 查询时
 */
public class EventRecorder implements BotListener {

    private final String botName;
    /** 环形缓存：明细字符串（type + 摘要），查询时才翻译/上色。 */
    private final String[] cache;
    /** 环形缓存容量（配置文件 eventCacheSize，监听开始时读取）。 */
    private final int cacheSize;
    /** 推送摘要最大长度（配置文件 eventSummaryMaxLength）。 */
    private final int summaryMaxLength;
    /** onTick 采样间隔（tick，配置文件 eventTickSampleInterval）。 */
    private final int tickSampleInterval;
    /** onMove 采样位移阈值（方块，配置文件 eventMoveSampleDistance）。 */
    private final double moveSampleDistance;
    private int cacheIndex;
    private int cacheCount;
    /** onTick 触发总数。 */
    private long tickCount;
    /** onMove 触发总数。 */
    private long moveCount;
    /** 实时推送次数（测试断言用）。 */
    private long pushCount;
    /** 环形缓存条目字符串的精确字节（record 写入加、覆盖减）。 */
    private long cacheBytes;
    /** onTick 距上次采样经过的 tick。 */
    private int ticksSinceSample;
    private double lastMoveX;
    private double lastMoveY;
    private double lastMoveZ;

    public EventRecorder(String botName) {
        this.botName = botName;
        var cfg = MockplayerConfig.get();
        this.cacheSize = cfg.getEventCacheSize();
        this.cache = new String[this.cacheSize];
        this.summaryMaxLength = cfg.getEventSummaryMaxLength();
        this.tickSampleInterval = cfg.getEventTickSampleInterval();
        this.moveSampleDistance = cfg.getEventMoveSampleDistance();
    }

    public String getBotName() {
        return this.botName;
    }

    public long getTickCount() {
        return this.tickCount;
    }

    public long getMoveCount() {
        return this.moveCount;
    }

    public long getPushCount() {
        return this.pushCount;
    }

    /** 环形缓存条目字符串的精确字节（不包含数组槽位本身）。 */
    public long getCacheBytes() {
        return this.cacheBytes;
    }

    /** 测试/查询用：当前缓存明细快照（最近在前）。 */
    public List<String> snapshot() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(this.cacheCount, this.cacheSize); i++) {
            int idx = (this.cacheIndex - 1 - i + this.cacheSize) % this.cacheSize;
            if (this.cache[idx] != null) {
                out.add(this.cache[idx]);
            }
        }
        return out;
    }

    /** 环形写入。 */
    private void record(String type, String summary) {
        String s = type + "|" + summary;
        if (this.cache[this.cacheIndex] != null) {
            this.cacheBytes -= ExactBytes.stringBytes(this.cache[this.cacheIndex]);
        }
        this.cache[this.cacheIndex] = s;
        this.cacheBytes += ExactBytes.stringBytes(s);
        this.cacheIndex = (this.cacheIndex + 1) % this.cacheSize;
        if (this.cacheCount < this.cacheSize) {
            this.cacheCount++;
        }
    }

    /** 低频事件：记录 + 实时推送（事件名翻译 + 动态摘要参数，i18n）。 */
    private void low(String type, String summary) {
        record(type, summary);
        this.pushCount++;
        CommandSupport.pushToChat(Component.translatable("commands.mockplayer.query.event.push",
                Component.translatable("commands.mockplayer.query.event." + type),
                Component.literal(truncate(summary))));
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= this.summaryMaxLength ? s : s.substring(0, this.summaryMaxLength) + "...";
    }

    /** /query events 查询输出：最近 count 条 + 高频计数/最新位置。 */
    public Component formatEvents(int count) {
        List<String> snap = snapshot();
        MutableComponent out = Component.translatable("commands.mockplayer.query.events.header",
                Math.min(count, snap.size()));
        for (int i = 0; i < Math.min(count, snap.size()); i++) {
            String[] parts = snap.get(i).split("\\|", 2);
            String type = parts[0];
            String summary = parts.length > 1 ? parts[1] : "";
            out.append(Component.literal("\n")).append(Component.translatable(
                    "commands.mockplayer.query.events.entry",
                    Component.translatable("commands.mockplayer.query.event." + type),
                    Component.literal(summary)));
        }
        out.append(Component.literal("\n")).append(Component.translatable(
                "commands.mockplayer.query.events.tick_count", this.tickCount));
        out.append(Component.literal("\n")).append(Component.translatable(
                "commands.mockplayer.query.events.move_count", this.moveCount,
                String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f",
                        this.lastMoveX, this.lastMoveY, this.lastMoveZ)));
        return out;
    }

    // ===== BotListener 全部事件 =====

    @Override
    public void onSpawned(Bot bot) {
        low("onSpawned", "");
    }

    @Override
    public void onPlayReady(Bot bot) {
        low("onPlayReady", "");
    }

    @Override
    public void onDisconnected(Bot bot, DisconnectionDetails details) {
        low("onDisconnected", details != null && details.reason() != null
                ? details.reason().getString() : "");
    }

    @Override
    public void onRespawn(Bot bot) {
        low("onRespawn", "");
    }

    @Override
    public void onDimensionChange(Bot bot, ResourceKey<Level> from, ResourceKey<Level> to) {
        low("onDimensionChange", from.identifier() + " -> " + to.identifier());
    }

    @Override
    public void onChat(Bot bot, Component message) {
        low("onChat", message != null ? message.getString() : "");
    }

    @Override
    public void onDamage(Bot bot, DamageSource source, float amount) {
        low("onDamage", (source != null ? source.getLocalizedDeathMessage(bot.getLocalPlayer()) : "?") + " " + amount);
    }

    @Override
    public void onDeath(Bot bot, Component deathMessage) {
        low("onDeath", deathMessage != null ? deathMessage.getString() : "");
    }

    @Override
    public void onHealthChanged(Bot bot, float oldHealth, float newHealth) {
        low("onHealthChanged", oldHealth + " -> " + newHealth);
    }

    @Override
    public void onAttackEntity(Bot bot, Entity target) {
        low("onAttackEntity", target != null ? target.getName().getString() : "?");
    }

    @Override
    public void onEntityAttacked(Bot bot, DamageSource source, float amount) {
        low("onEntityAttacked", (source != null ? source.getLocalizedDeathMessage(bot.getLocalPlayer()) : "?") + " " + amount);
    }

    @Override
    public void onInteractBlock(Bot bot, BlockPos pos, Direction side) {
        low("onInteractBlock", pos + " " + side);
    }

    @Override
    public void onPlaceBlock(Bot bot, BlockPos pos) {
        low("onPlaceBlock", String.valueOf(pos));
    }

    @Override
    public void onBreakBlock(Bot bot, BlockPos pos) {
        low("onBreakBlock", String.valueOf(pos));
    }

    @Override
    public void onUseItem(Bot bot, InteractionHand hand, ItemStack stack) {
        low("onUseItem", hand + " " + (stack != null ? stack.getHoverName().getString() : "?"));
    }

    @Override
    public void onInteractEntity(Bot bot, Entity target) {
        low("onInteractEntity", target != null ? target.getName().getString() : "?");
    }

    @Override
    public void onContainerOpened(Bot bot, MenuType<?> type, int containerId, Component title) {
        low("onContainerOpened", containerId + " " + (title != null ? title.getString() : String.valueOf(type)));
    }

    @Override
    public void onContainerSlotChanged(Bot bot, int containerId, int slot, ItemStack stack) {
        low("onContainerSlotChanged", containerId + " slot=" + slot + " "
                + (stack != null ? stack.getHoverName().getString() : "?"));
    }

    @Override
    public void onContainerClosed(Bot bot, int containerId) {
        low("onContainerClosed", String.valueOf(containerId));
    }

    @Override
    public void onMerchantOffersUpdated(Bot bot, MerchantOffers offers) {
        low("onMerchantOffersUpdated", offers != null ? String.valueOf(offers.size()) : "0");
    }

    @Override
    public void onPlayerJoined(Bot bot, GameProfile profile) {
        low("onPlayerJoined", profile != null ? profile.name() : "?");
    }

    @Override
    public void onPlayerLeft(Bot bot, GameProfile profile) {
        low("onPlayerLeft", profile != null ? profile.name() : "?");
    }

    @Override
    public void onHeldSlotChanged(Bot bot, int slot) {
        low("onHeldSlotChanged", String.valueOf(slot));
    }

    @Override
    public void onItemCooldown(Bot bot, Identifier item, int duration) {
        low("onItemCooldown", item + " " + duration);
    }

    @Override
    public void onPickupItem(Bot bot, ItemStack stack) {
        low("onPickupItem", stack != null ? stack.getHoverName().getString() + " x" + stack.getCount() : "?");
    }

    @Override
    public void onDropItem(Bot bot, ItemStack stack) {
        low("onDropItem", stack != null ? stack.getHoverName().getString() + " x" + stack.getCount() : "?");
    }

    @Override
    public void onSwapHands(Bot bot) {
        low("onSwapHands", "");
    }

    @Override
    public void onSneakToggle(Bot bot, boolean sneaking) {
        low("onSneakToggle", String.valueOf(sneaking));
    }

    @Override
    public void onSprintToggle(Bot bot, boolean sprinting) {
        low("onSprintToggle", String.valueOf(sprinting));
    }

    /** 高频：只计数，每 20 tick 采样一条快照（位置/血量），不推送。 */
    @Override
    public void onTick(Bot bot) {
        this.tickCount++;
        if (++this.ticksSinceSample >= this.tickSampleInterval) {
            this.ticksSinceSample = 0;
            String pos = bot != null && bot.getLocalPlayer() != null
                    ? String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f hp=%.1f",
                    bot.getLocalPlayer().getX(), bot.getLocalPlayer().getY(),
                    bot.getLocalPlayer().getZ(), bot.getLocalPlayer().getHealth())
                    : "?";
            record("onTick", pos);
        }
    }

    /** 高频：只计数，位移 ≥0.5 块才采样，不推送。 */
    @Override
    public void onMove(Bot bot) {
        this.moveCount++;
        if (bot == null || bot.getLocalPlayer() == null) {
            return;
        }
        var p = bot.getLocalPlayer();
        double dx = p.getX() - this.lastMoveX;
        double dy = p.getY() - this.lastMoveY;
        double dz = p.getZ() - this.lastMoveZ;
        if (Math.abs(dx) >= this.moveSampleDistance || Math.abs(dy) >= this.moveSampleDistance
                || Math.abs(dz) >= this.moveSampleDistance) {
            record("onMove", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
        }
        this.lastMoveX = p.getX();
        this.lastMoveY = p.getY();
        this.lastMoveZ = p.getZ();
    }
}

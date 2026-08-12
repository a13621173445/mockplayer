package com.mockplayer.api.event;

import com.mockplayer.api.Bot;

import net.minecraft.core.Direction;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.mojang.authlib.GameProfile;

/**
 * Bot 事件监听器。
 *
 * 所有方法都有默认空实现，外部只实现关心的即可。
 * 事件在主线程（渲染线程）派发，回调内禁止阻塞。
 * 事件采用惰性分发：无人监听的事件零开销。
 */
public interface BotListener {

    // ===== 生命周期 =====

    /** 会话已创建，开始连接（CONNECTING）。 */
    default void onSpawned(Bot bot) {
    }

    /** 登录完成，LocalPlayer/Level 就绪（PLAYING）。 */
    default void onPlayReady(Bot bot) {
    }

    /** 已断开（被踢/主动移除/连接失败）。 */
    default void onDisconnected(Bot bot, DisconnectionDetails details) {
    }

    /** 死亡后重生。 */
    default void onRespawn(Bot bot) {
    }

    /** 维度变化。 */
    default void onDimensionChange(Bot bot, ResourceKey<Level> from, ResourceKey<Level> to) {
    }

    // ===== 聊天 =====

    /** 假人收到聊天消息（系统/玩家/伪装均触发）。 */
    default void onChat(Bot bot, Component message) {
    }

    // ===== 战斗 =====

    /** 假人受到伤害。 */
    default void onDamage(Bot bot, DamageSource source, float amount) {
    }

    /** 假人死亡。 */
    default void onDeath(Bot bot, Component deathMessage) {
    }

    /** 假人血量变化。 */
    default void onHealthChanged(Bot bot, float oldHealth, float newHealth) {
    }

    /** 假人攻击了实体。 */
    default void onAttackEntity(Bot bot, Entity target) {
    }

    /** 假人被实体攻击（服务端回执）。 */
    default void onEntityAttacked(Bot bot, DamageSource source, float amount) {
    }

    // ===== 交互 =====

    /** 假人右键交互了方块。 */
    default void onInteractBlock(Bot bot, BlockPos pos, Direction side) {
    }

    /** 假人放置了方块。 */
    default void onPlaceBlock(Bot bot, BlockPos pos) {
    }

    /** 假人破坏了方块。 */
    default void onBreakBlock(Bot bot, BlockPos pos) {
    }

    /** 假人使用了物品。 */
    default void onUseItem(Bot bot, InteractionHand hand, ItemStack stack) {
    }

    /** 假人右键交互了实体。 */
    default void onInteractEntity(Bot bot, Entity target) {
    }

    // ===== 容器 =====

    /** 假人打开了容器菜单。 */
    default void onContainerOpened(Bot bot, MenuType<?> type, int containerId, Component title) {
    }

    /** 假人容器槽位变化（含背包/快捷栏）。 */
    default void onContainerSlotChanged(Bot bot, int containerId, int slot, ItemStack stack) {
    }

    /** 假人容器菜单关闭。 */
    default void onContainerClosed(Bot bot, int containerId) {
    }

    /** 村民交易报价更新。 */
    default void onMerchantOffersUpdated(Bot bot, MerchantOffers offers) {
    }

    // ===== 杂项 =====

    /** 服务端 Tab 列表有玩家上线。 */
    default void onPlayerJoined(Bot bot, GameProfile profile) {
    }

    /** 服务端 Tab 列表有玩家下线。 */
    default void onPlayerLeft(Bot bot, GameProfile profile) {
    }

    /** 假人切换选中槽位。 */
    default void onHeldSlotChanged(Bot bot, int slot) {
    }

    /** 假人物品进入冷却。 */
    default void onItemCooldown(Bot bot, Identifier item, int duration) {
    }

    /** 假人拾起物品。 */
    default void onPickupItem(Bot bot, ItemStack stack) {
    }

    /** 假人丢弃物品。 */
    default void onDropItem(Bot bot, ItemStack stack) {
    }

    /** 假人交换主副手。 */
    default void onSwapHands(Bot bot) {
    }

    /** 假人潜行状态变化。 */
    default void onSneakToggle(Bot bot, boolean sneaking) {
    }

    /** 假人疾跑状态变化。 */
    default void onSprintToggle(Bot bot, boolean sprinting) {
    }

    // ===== 假人世界（区块/实体/方块数据；供内存记账等可插拔模块监听） =====

    /** 假人 level 载入了一个区块（收到 chunk 包并写入假人 level 后触发）。 */
    default void onChunkLoaded(Bot bot, LevelChunk chunk) {
    }

    /** 假人 level 卸载了一个区块（收到 forget chunk 包后触发）。 */
    default void onChunkUnloaded(Bot bot, ChunkPos pos) {
    }

    /** 假人 level 新增了一个实体（收到 add entity 包并加入后触发）。 */
    default void onEntityAdded(Bot bot, Entity entity) {
    }

    /** 假人 level 移除了一个实体（收到 remove/take item 包移除后触发）。 */
    default void onEntityRemoved(Bot bot, int entityId) {
    }

    /** 假人 level 某个 section 的方块数据变化（序列化字节 old→new；old==new 时模块可跳过）。 */
    default void onSectionDataChanged(Bot bot, ChunkPos pos, int sectionIndex,
                                      long oldSerializedBytes, long newSerializedBytes) {
    }

    /** 假人 level 某个方块实体的 NBT 数据更新（newDataBytes 为新的序列化尺寸）。 */
    default void onBlockEntityData(Bot bot, BlockPos pos, long newDataBytes) {
    }

    // ===== 高频（传参复用，不分配事件对象） =====

    /** 每帧驱动钩子（外部 AI 主循环）。 */
    default void onTick(Bot bot) {
    }

    /** 位置变化（超过阈值才触发，避免高频刷屏）。 */
    default void onMove(Bot bot) {
    }
}

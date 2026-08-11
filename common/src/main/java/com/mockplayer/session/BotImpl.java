package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.action.BotActions;
import com.mockplayer.api.container.BotContainer;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bot 接口的会话实现：包装一个 {@link FakeSession}，提供状态/世界信息/行为/容器会话。
 *
 * 不泄漏任何实现细节到 API 层；外部通过 {@link com.mockplayer.api.Bot} 使用。
 */
public class BotImpl implements Bot {

    private final FakeSession session;
    private final String owner;
    private final BotEventBus events = new BotEventBus();
    private final BotActionsImpl actions;
    /** 当前打开的容器菜单（FakePlayListener handleOpenScreen/Close 维护） */
    private volatile AbstractContainerMenu openMenu;
    /** 当前打开的容器标题 */
    private volatile Component openTitle;

    /** 移动事件阈值（方块）：超过才触发 onMove */
    private static final double MOVE_EPSILON = 0.001;

    public BotImpl(FakeSession session, String owner) {
        this.session = session;
        this.owner = owner;
        this.actions = new BotActionsImpl(this);
    }

    /**
     * 内部：事件总线（FakePlayListener/FakeSession 触发事件用）。
     * 跨包白名单：QueryCommands（同包）、测试套件 ControlCommandsSuite（挂/摘监听器）。
     */
    @com.mockplayer.api.Internal
    public BotEventBus events() {
        return this.events;
    }

    /** 内部：底层会话（BotMemoryEstimator/QueryCommands 同包使用）。 */
    @com.mockplayer.api.Internal
    public FakeSession session() {
        return this.session;
    }

    /** 内部：设置当前打开的容器菜单（handleOpenScreen 调用） */
    public void setOpenMenu(AbstractContainerMenu menu, Component title) {
        this.openMenu = menu;
        this.openTitle = title;
    }

    /** 内部：清除打开的容器菜单（handleContainerClose 调用） */
    public void clearOpenMenu() {
        this.openMenu = null;
        this.openTitle = null;
    }

    /** 内部：驱动持续动作输入 + 位置移动事件（FakeSession.tick 调用，主线程） */
    public void tick() {
        this.actions.applyInput();
        LocalPlayer player = session.getFakePlayer();
        if (player != null && this.events.hasListeners()) {
            double x = player.getX(), y = player.getY(), z = player.getZ();
            if (Math.abs(x - this.lastX) > MOVE_EPSILON || Math.abs(y - this.lastY) > MOVE_EPSILON || Math.abs(z - this.lastZ) > MOVE_EPSILON) {
                this.lastX = x;
                this.lastY = y;
                this.lastZ = z;
                this.events.fire(this, l -> l.onMove(this));
            }
        }
        this.events.fire(this, l -> l.onTick(this));
    }

    private double lastX, lastY, lastZ;

    @Override
    public String getName() {
        return this.session.getName();
    }

    @Override
    public UUID getUUID() {
        GameProfile profile = this.session.getProfile();
        if (profile != null) {
            return profile.id();
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + getName()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getOwner() {
        return this.owner;
    }

    @Override
    public com.mockplayer.api.BotSource source() {
        return this.session.getSource();
    }

    @Override
    public BotLifecycle getLifecycle() {
        if (this.session.getFakePlayer() != null && this.session.isConnected()) {
            return BotLifecycle.PLAYING;
        }
        // 连接中/登录中（TCP 活跃但未 PLAYING）都算 CONNECTING
        if (this.session.isConnecting() || this.session.isConnected()) {
            return BotLifecycle.CONNECTING;
        }
        return BotLifecycle.DISCONNECTED;
    }

    @Override
    public LocalPlayer getLocalPlayer() {
        return this.session.getFakePlayer();
    }

    @Override
    public MultiPlayerGameMode getGameMode() {
        FakePlayListener listener = this.session.getPlayListener();
        return listener != null ? listener.getFakeGameMode() : null;
    }

    @Override
    public ClientLevel getLevel() {
        LocalPlayer player = this.session.getFakePlayer();
        return player != null ? (ClientLevel) player.level() : null;
    }

    @Override
    public List<Entity> getEntitiesNear(double range) {
        return getEntitiesNear(range, e -> true);
    }

    @Override
    public List<Entity> getEntitiesNear(double range, Predicate<Entity> filter) {
        ClientLevel level = getLevel();
        LocalPlayer player = this.session.getFakePlayer();
        if (level == null || player == null) {
            return List.of();
        }
        return level.getEntities(player, player.getBoundingBox().inflate(range), e -> e != player && filter.test(e));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        ClientLevel level = getLevel();
        return level != null ? level.getBlockState(pos) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean isBlockLoaded(BlockPos pos) {
        ClientLevel level = getLevel();
        return level != null && level.isLoaded(pos);
    }

    @Override
    public int getChunkRadius() {
        return this.session.getChunkRadius();
    }

    @Override
    public void setChunkRadius(int radius) {
        this.session.setChunkRadius(radius);
    }

    @Override
    public List<PlayerInfo> getOnlinePlayers() {
        FakePlayListener listener = this.session.getPlayListener();
        if (listener == null) {
            return List.of();
        }
        return new ArrayList<>(listener.getOnlinePlayers());
    }

    @Override
    public BotActions actions() {
        return this.actions;
    }

    @Override
    public Optional<BotContainer> getContainer() {
        AbstractContainerMenu menu = this.openMenu;
        if (menu == null) {
            return Optional.empty();
        }
        return Optional.of(new BotContainerImpl(this, menu, this.openTitle));
    }

    @Override
    @Deprecated
    public Optional<BotContainer> getScreen() {
        return this.getContainer();
    }

    @Override
    public com.mockplayer.api.BotMemoryInfo memoryInfo() {
        return BotMemoryEstimator.estimate(this);
    }

    @Override
    public boolean isAutoRespawn() {
        return this.session.isAutoRespawn();
    }

    @Override
    public void setAutoRespawn(boolean autoRespawn) {
        this.session.setAutoRespawn(autoRespawn);
    }

    // ===== 事件触发辅助（FakePlayListener / FakeSession / BotActionsImpl 调用） =====

    void fireOnSpawned() {
        this.events.fire(this, l -> l.onSpawned(this));
    }

    void fireOnPlayReady() {
        this.events.fire(this, l -> l.onPlayReady(this));
    }

    void fireOnDisconnected(net.minecraft.network.DisconnectionDetails details) {
        this.events.fire(this, l -> l.onDisconnected(this, details));
    }

    void fireOnRespawn() {
        this.events.fire(this, l -> l.onRespawn(this));
    }

    void fireOnDimensionChange(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> from, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> to) {
        this.events.fire(this, l -> l.onDimensionChange(this, from, to));
    }

    void fireOnChat(Component message) {
        this.events.fire(this, l -> l.onChat(this, message));
    }

    void fireOnDamage(net.minecraft.world.damagesource.DamageSource source, float amount) {
        this.events.fire(this, l -> l.onDamage(this, source, amount));
    }

    void fireOnDeath(Component deathMessage) {
        this.events.fire(this, l -> l.onDeath(this, deathMessage));
    }

    void fireOnHealthChanged(float oldHealth, float newHealth) {
        this.events.fire(this, l -> l.onHealthChanged(this, oldHealth, newHealth));
    }

    void fireOnAttackEntity(Entity target) {
        this.events.fire(this, l -> l.onAttackEntity(this, target));
    }

    void fireOnEntityAttacked(net.minecraft.world.damagesource.DamageSource source, float amount) {
        this.events.fire(this, l -> l.onEntityAttacked(this, source, amount));
    }

    void fireOnInteractBlock(BlockPos pos, net.minecraft.core.Direction side) {
        this.events.fire(this, l -> l.onInteractBlock(this, pos, side));
    }

    void fireOnBreakBlock(BlockPos pos) {
        this.events.fire(this, l -> l.onBreakBlock(this, pos));
    }

    void fireOnPlaceBlock(BlockPos pos) {
        this.events.fire(this, l -> l.onPlaceBlock(this, pos));
    }

    void fireOnUseItem(net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack stack) {
        this.events.fire(this, l -> l.onUseItem(this, hand, stack));
    }

    void fireOnInteractEntity(Entity target) {
        this.events.fire(this, l -> l.onInteractEntity(this, target));
    }

    void fireOnContainerOpened(net.minecraft.world.inventory.MenuType<?> type, int containerId, Component title) {
        this.events.fire(this, l -> l.onContainerOpened(this, type, containerId, title));
    }

    void fireOnContainerSlotChanged(int containerId, int slot, net.minecraft.world.item.ItemStack stack) {
        this.events.fire(this, l -> l.onContainerSlotChanged(this, containerId, slot, stack));
    }

    void fireOnContainerClosed(int containerId) {
        this.events.fire(this, l -> l.onContainerClosed(this, containerId));
    }

    void fireOnMerchantOffersUpdated(MerchantOffers offers) {
        this.events.fire(this, l -> l.onMerchantOffersUpdated(this, offers));
    }

    void fireOnPlayerJoined(GameProfile profile) {
        this.events.fire(this, l -> l.onPlayerJoined(this, profile));
    }

    void fireOnPlayerLeft(GameProfile profile) {
        this.events.fire(this, l -> l.onPlayerLeft(this, profile));
    }

    void fireOnHeldSlotChanged(int slot) {
        this.events.fire(this, l -> l.onHeldSlotChanged(this, slot));
    }

    void fireOnItemCooldown(net.minecraft.resources.Identifier item, int duration) {
        this.events.fire(this, l -> l.onItemCooldown(this, item, duration));
    }

    void fireOnPickupItem(net.minecraft.world.item.ItemStack stack) {
        this.events.fire(this, l -> l.onPickupItem(this, stack));
    }

    void fireOnDropItem(net.minecraft.world.item.ItemStack stack) {
        this.events.fire(this, l -> l.onDropItem(this, stack));
    }

    void fireOnSwapHands() {
        this.events.fire(this, l -> l.onSwapHands(this));
    }

    void fireOnSneakToggle(boolean sneaking) {
        this.events.fire(this, l -> l.onSneakToggle(this, sneaking));
    }

    void fireOnSprintToggle(boolean sprinting) {
        this.events.fire(this, l -> l.onSprintToggle(this, sprinting));
    }
}

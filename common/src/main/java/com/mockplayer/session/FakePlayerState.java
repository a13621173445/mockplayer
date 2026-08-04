package com.mockplayer.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.network.chat.Component;

/**
 * 假人的完整状态（无头但完整感知）。
 *
 * 假人不渲染、不弹 UI，但需要维护和真玩家同等的状态数据，
 * 供未来程序化工作（AI）和 /control 接管时读取。
 * 与主玩家的 Minecraft.player 完全独立，互不污染。
 */
public class FakePlayerState {

    /** 假人收到的聊天消息（供未来指令解析/AI 使用） */
    private final List<Component> chatHistory = new CopyOnWriteArrayList<>();

    /** 假人血量（0-20） */
    private float health = 20.0F;
    /** 假人饥饿值 */
    private int foodLevel = 20;
    /** 假人经验等级 */
    private int experienceLevel;
    /** 假人位置（最近一次同步） */
    private double x, y, z;
    /** 假人是否在地上 */
    private boolean onGround = true;

    public void addChat(Component message) {
        this.chatHistory.add(message);
        // 限制历史长度，防内存泄漏
        while (this.chatHistory.size() > 200) {
            this.chatHistory.remove(0);
        }
    }

    public List<Component> getChatHistory() {
        return this.chatHistory;
    }

    public void setHealth(float health) {
        this.health = health;
    }

    public float getHealth() {
        return this.health;
    }

    public void setFoodLevel(int foodLevel) {
        this.foodLevel = foodLevel;
    }

    public int getFoodLevel() {
        return this.foodLevel;
    }

    public void setExperienceLevel(int level) {
        this.experienceLevel = level;
    }

    public int getExperienceLevel() {
        return this.experienceLevel;
    }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return this.x;
    }

    public double getY() {
        return this.y;
    }

    public double getZ() {
        return this.z;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public boolean isOnGround() {
        return this.onGround;
    }

    /** 记录进度更新（假人无头不弹窗，数据存此供 AI/控制读取） */
    public void recordAdvancements(net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket packet) {
        // 简单记录，扩展字段可后续加
        this.lastAdvancementPacket = packet;
    }

    private net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket lastAdvancementPacket;

    public net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket getLastAdvancementPacket() {
        return this.lastAdvancementPacket;
    }

    /** 赢局标记（服务端 WIN_GAME 事件） */
    private boolean wonGame;

    public void recordWinGame() {
        this.wonGame = true;
    }

    public boolean hasWonGame() {
        return this.wonGame;
    }

    /** 最后一条演示事件参数（服务端 DEMO_EVENT，供 AI 感知教程状态） */
    private float lastDemoEvent;

    public void recordDemoEvent(float param) {
        this.lastDemoEvent = param;
    }

    public float getLastDemoEvent() {
        return this.lastDemoEvent;
    }

    /** 在线玩家列表（name → 是否在线），供程序化 AI 感知周围玩家 */
    private final java.util.Map<java.util.UUID, String> onlinePlayers = new java.util.concurrent.ConcurrentHashMap<>();

    public void recordPlayerOnline(java.util.UUID uuid, String name) {
        this.onlinePlayers.put(uuid, name);
    }

    public void removePlayerOnline(java.util.UUID uuid) {
        this.onlinePlayers.remove(uuid);
    }

    /** 最后一条停音效请求（假人不播主玩家音效，记录供 AI 感知环境声音变化） */
    private volatile net.minecraft.network.protocol.game.ClientboundStopSoundPacket lastStopSound;

    public void recordStopSound(net.minecraft.network.protocol.game.ClientboundStopSoundPacket packet) {
        this.lastStopSound = packet;
    }

    public net.minecraft.network.protocol.game.ClientboundStopSoundPacket getLastStopSound() {
        return this.lastStopSound;
    }

    /** 最近一次打开的容器（假人不弹主玩家 UI，记录类型/标题供 AI 感知） */
    private volatile Object lastOpenScreen;

    public void recordOpenScreen(Object menuType, int containerId, net.minecraft.network.chat.Component title) {
        this.lastOpenScreen = new Object() {
            @Override
            public String toString() {
                return "OpenScreen{" + menuType + ", id=" + containerId + ", title=" + title.getString() + "}";
            }
        };
    }

    public Object getLastOpenScreen() {
        return this.lastOpenScreen;
    }

    public java.util.Map<java.util.UUID, String> getOnlinePlayers() {
        return this.onlinePlayers;
    }

    /**
     * 通用「最近收到的包」记录（铁律零丢弃兜底）。
     * 假人无头不渲染 UI 的数据包，完整保存原始 packet，供程序化 AI / /control 读取。
     * key 用 handler 名（如 "handlePlayerCombatKill"）。
     */
    private final java.util.Map<String, Object> lastPackets = new java.util.concurrent.ConcurrentHashMap<>();

    public void recordPacket(String key, Object packet) {
        this.lastPackets.put(key, packet);
    }

    @SuppressWarnings("unchecked")
    public <T> T getLastPacket(String key) {
        return (T) this.lastPackets.get(key);
    }

    public java.util.Map<String, Object> getAllLastPackets() {
        return java.util.Collections.unmodifiableMap(this.lastPackets);
    }

    /** 最近一次 Boss 事件包（假人不显示 Boss 栏，但数据保留供 AI 读取） */
    private volatile net.minecraft.network.protocol.game.ClientboundBossEventPacket lastBossEvent;

    public void recordBossEvent(net.minecraft.network.protocol.game.ClientboundBossEventPacket packet) {
        this.lastBossEvent = packet;
    }

    public net.minecraft.network.protocol.game.ClientboundBossEventPacket getLastBossEvent() {
        return this.lastBossEvent;
    }

    /**
     * 记录假人应感知的音效事件（假人不播放到主玩家音箱，严格零污染）。
     * 存最近 20 条，供 AI / /control 读取环境声音变化。
     */
    private final java.util.List<net.minecraft.network.chat.Component> soundLog = new CopyOnWriteArrayList<>();

    public void recordSound(String description, double x, double y, double z) {
        this.soundLog.add(net.minecraft.network.chat.Component.literal(
                String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f %s", x, y, z, description)));
        while (this.soundLog.size() > 20) {
            this.soundLog.remove(0);
        }
    }

    public java.util.List<net.minecraft.network.chat.Component> getSoundLog() {
        return this.soundLog;
    }

    /**
     * 记录假人应感知的粒子事件（假人不渲染到主玩家屏幕，严格零污染）。
     */
    private final java.util.List<net.minecraft.network.chat.Component> particleLog = new CopyOnWriteArrayList<>();

    public void recordParticle(String description, double x, double y, double z) {
        this.particleLog.add(net.minecraft.network.chat.Component.literal(
                String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f %s", x, y, z, description)));
        while (this.particleLog.size() > 20) {
            this.particleLog.remove(0);
        }
    }

    public java.util.List<net.minecraft.network.chat.Component> getParticleLog() {
        return this.particleLog;
    }
}

package com.mockplayer.session;

import com.mockplayer.config.MockplayerConfig;

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
    /** 聊天历史保留文本的精确字节（记账：addChat 加、淘汰减）。 */
    private long chatBytes;

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
        String text = message.getString();
        this.chatHistory.add(message);
        this.chatBytes += ExactBytes.stringBytes(text);
        // 限制历史长度，防内存泄漏
        int limit = MockplayerConfig.get().getChatHistoryLimit();
        while (this.chatHistory.size() > limit) {
            Component removed = this.chatHistory.remove(0);
            this.chatBytes -= ExactBytes.stringBytes(removed.getString());
        }
    }

    public List<Component> getChatHistory() {
        return this.chatHistory;
    }

    /** 聊天历史保留文本的精确字节（不含 Component 包装对象本身）。 */
    public long getChatBytes() {
        return this.chatBytes;
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

    /** 在线玩家快照（名字/延迟/游戏模式/是否列出），供程序化 AI 感知周围玩家。 */
    public record OnlinePlayerInfo(String name, int latency,
                                   net.minecraft.world.level.GameType gameMode,
                                   boolean listed) {
    }

    /** 在线玩家列表（UUID → 快照）。 */
    private final java.util.Map<java.util.UUID, OnlinePlayerInfo> onlinePlayers =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void recordPlayerOnline(java.util.UUID uuid, String name, int latency,
                                   net.minecraft.world.level.GameType gameMode,
                                   boolean listed) {
        this.onlinePlayers.put(uuid, new OnlinePlayerInfo(name, latency, gameMode, listed));
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

    /** 最近一次打开的容器（假人不弹主玩家 UI，记录类型/标题供 AI 感知）。 */
    private volatile OpenScreenInfo lastOpenScreen;

    /** 打开的容器界面快照（record，类型安全，替代旧匿名 Object）。 */
    public record OpenScreenInfo(net.minecraft.world.inventory.MenuType<?> menuType,
                                 int containerId,
                                 net.minecraft.network.chat.Component title) {
    }

    public void recordOpenScreen(net.minecraft.world.inventory.MenuType<?> menuType,
                                 int containerId,
                                 net.minecraft.network.chat.Component title) {
        this.lastOpenScreen = new OpenScreenInfo(menuType, containerId, title);
    }

    public OpenScreenInfo getLastOpenScreen() {
        return this.lastOpenScreen;
    }

    public java.util.Map<java.util.UUID, OnlinePlayerInfo> getOnlinePlayers() {
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
    /** 音效日志保留文本的精确字节。 */
    private long soundBytes;

    public void recordSound(String description, double x, double y, double z) {
        String text = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f %s", x, y, z, description);
        this.soundLog.add(net.minecraft.network.chat.Component.literal(text));
        this.soundBytes += ExactBytes.stringBytes(text);
        int limit = MockplayerConfig.get().getSoundLogLimit();
        while (this.soundLog.size() > limit) {
            net.minecraft.network.chat.Component removed = this.soundLog.remove(0);
            this.soundBytes -= ExactBytes.stringBytes(removed.getString());
        }
    }

    public java.util.List<net.minecraft.network.chat.Component> getSoundLog() {
        return this.soundLog;
    }

    /** 音效日志保留文本的精确字节。 */
    public long getSoundBytes() {
        return this.soundBytes;
    }

    /**
     * 记录假人应感知的粒子事件（假人不渲染到主玩家屏幕，严格零污染）。
     */
    private final java.util.List<net.minecraft.network.chat.Component> particleLog = new CopyOnWriteArrayList<>();
    /** 粒子日志保留文本的精确字节。 */
    private long particleBytes;

    public void recordParticle(String description, double x, double y, double z) {
        String text = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f %s", x, y, z, description);
        this.particleLog.add(net.minecraft.network.chat.Component.literal(text));
        this.particleBytes += ExactBytes.stringBytes(text);
        int limit = MockplayerConfig.get().getParticleLogLimit();
        while (this.particleLog.size() > limit) {
            net.minecraft.network.chat.Component removed = this.particleLog.remove(0);
            this.particleBytes -= ExactBytes.stringBytes(removed.getString());
        }
    }

    public java.util.List<net.minecraft.network.chat.Component> getParticleLog() {
        return this.particleLog;
    }

    /** 粒子日志保留文本的精确字节。 */
    public long getParticleBytes() {
        return this.particleBytes;
    }

    /**
     * 会话终结时清空全部记录（聊天/音效/粒子/在线玩家/最近包/各类快照），
     * 主动释放持有的引用便于 GC 回收；仅由 FakeSession.disconnect 删除路径调用。
     */
    public void clear() {
        this.chatHistory.clear();
        this.chatBytes = 0;
        this.soundLog.clear();
        this.soundBytes = 0;
        this.particleLog.clear();
        this.particleBytes = 0;
        this.onlinePlayers.clear();
        this.lastPackets.clear();
        this.lastAdvancementPacket = null;
        this.lastStopSound = null;
        this.lastBossEvent = null;
        this.lastOpenScreen = null;
        this.wonGame = false;
        this.lastDemoEvent = 0.0F;
        this.health = 20.0F;
        this.foodLevel = 20;
        this.experienceLevel = 0;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
        this.onGround = true;
    }
}

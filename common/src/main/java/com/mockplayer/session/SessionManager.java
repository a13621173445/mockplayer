package com.mockplayer.session;

import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;

import java.util.Collection;

/**
 * 会话管理器（向后兼容的薄委托层）。
 *
 * 注册表/生命周期/权限逻辑全部在 {@link BotManagerImpl}（即 {@link MockplayerApi#bots()}）。
 * 这里保留旧方法签名供命令/内部清理使用，并完成 MockplayerApi 初始化。
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();
    /** 假人 level 集合（粒子隔离：假人 level 的 addParticle 不播主玩家 particleEngine）。 */
    private static final java.util.Set<net.minecraft.client.multiplayer.ClientLevel> FAKE_LEVELS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final BotManagerImpl manager = new BotManagerImpl();

    private SessionManager() {
        MockplayerApi.init(this.manager);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 创建假人（命令入口，owner="command"）。
     *
     * @param name 假人名字
     * @return true 创建成功，false 重名
     */
    public boolean createFakePlayer(String name) {
        // 命令入口：走内部 CORE 路径（受命令/配置管理）
        return this.manager.createCoreBot(BotProfile.of(name, BotManagerImpl.COMMAND_OWNER)) != null;
    }

    /**
     * 移除假人（命令入口，特权）。
     *
     * @param name 假人名字
     * @return true 移除成功，false 不存在
     */
    public boolean removeFakePlayer(String name) {
        return this.manager.removeBot(name, BotManagerImpl.COMMAND_OWNER) == RemoveResult.REMOVED;
    }

    /**
     * 驱动所有假人连接 tick（渲染线程每 tick 调用）。
     */
    public void tick() {
        this.manager.tick();
        BatchCommands.tick(); // 批量创建队列（tick 驱动）
    }

    /**
     * 全部假人下线（主玩家退出服务器时调用）。
     */
    public void clearAll() {
        this.manager.clearAll();
    }

    /**
     * 当前在线假人名字列表。
     */
    public Collection<String> getFakePlayerNames() {
        return this.manager.getFakePlayerNames();
    }

    /**
     * 按名字取底层会话（内部使用）。
     */
    public FakeSession getSession(String name) {
        return this.manager.getSession(name);
    }

    /** 注册假人 level（FakePlayListener 创建假人 level 后调用）。 */
    public static void registerFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) {
            FAKE_LEVELS.add(level);
        }
    }

    /** 注销假人 level（FakeSession.disconnect 时调用）。 */
    public static void unregisterFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) {
            FAKE_LEVELS.remove(level);
        }
    }

    /** 是否为假人 level（MixinClientLevel 拦截 addParticle 用）。 */
    public static boolean isFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        return FAKE_LEVELS.contains(level);
    }

    /** 假人粒子：记录到对应假人 state，不加入主玩家 particleEngine。 */
    public static void recordFakeParticle(net.minecraft.client.multiplayer.ClientLevel level,
                                          net.minecraft.core.particles.ParticleOptions particle,
                                          double x, double y, double z) {
        for (String name : INSTANCE.getFakePlayerNames()) {
            FakeSession session = INSTANCE.getSession(name);
            if (session != null && session.getFakeLevel() == level) {
                // ParticleType 不重写 toString()，直接 toString 是类名垃圾；用注册表 key（minecraft:block）
                net.minecraft.resources.Identifier key =
                        net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
                session.getState().recordParticle(key != null ? key.toString() : particle.getType().toString(), x, y, z);
                return;
            }
        }
    }

    /** 假人音效：记录到对应假人 state，不播放到主玩家 SoundManager（2001 等 level 事件链路）。 */
    public static void recordFakeSound(net.minecraft.client.multiplayer.ClientLevel level,
                                       String description, double x, double y, double z) {
        for (String name : INSTANCE.getFakePlayerNames()) {
            FakeSession session = INSTANCE.getSession(name);
            if (session != null && session.getFakeLevel() == level) {
                session.getState().recordSound(description, x, y, z);
                return;
            }
        }
    }
}

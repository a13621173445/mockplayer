package com.mockplayer.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.Connection;

/**
 * 假人连接注册表：标记哪些 Connection 属于假人，并关联对应的 FakeSession。
 *
 * 纯静态存储，不侵入 Connection 类。平台 Mixin 用它判断
 * 「这个连接是不是假人的」，从而创建 FakePlayListener。
 */
public class FakeConnectionRegistry {

    private static final Map<Connection, FakeSession> FAKE_SESSIONS = new ConcurrentHashMap<>();

    /**
     * 主玩家是否正在 server transfer（被传送到子服）。
     * 由双端 Mixin 维护：MixinClientCommonPacketListenerImpl 在 handleTransfer 置位，
     * MixinMinecraft 在 disconnect 时读取——true 则跳过 SessionManager.clearAll（主玩家 transfer 不误清假人）。
     * 用静态字段（主玩家只有一条连接，无需按连接区分）。
     */
    private static volatile boolean transferring;

    /**
     * 假人是否正处于配置阶段（登录完成 → 进 play）。
     * neoforge 服务端对假人（TCP）发 FrozenRegistrySyncCompletedPayload，客户端
     * RegistryManager.applySnapshot 会把 BuiltInRegistries.BLOCK 的 tags 覆盖成服务端
     * snapshot（基础态 16），破坏假人本地完整 block tags（395）→ 原版数据包加载缺 tag。
     * 主玩家（内存连接）不走 neoforge 网络同步不受影响。假人配置阶段跳过 applySnapshot。
     */
    private static volatile boolean configuringFake;

    private FakeConnectionRegistry() {
    }

    /** 标记假人进入配置阶段（FakeLoginListener 登录完成时置位） */
    public static void setConfiguringFake(boolean configuring) {
        FakeConnectionRegistry.configuringFake = configuring;
    }

    /** 当前是否有假人正在配置阶段（neoforge RegistryManager.applySnapshot 用它跳过假人） */
    public static boolean isConfiguringFake() {
        return FakeConnectionRegistry.configuringFake;
    }

    /**
     * 假人配置阶段串行锁：Fabric 的 ClientNetworkingImpl.getClientConfigurationAddon()
     * 是全局单例，多个假人并发进配置阶段会互相覆盖/清空，导致 c:version 回调 NPE 被踢。
     * 锁在整个假人配置阶段持有（handleLoginFinished 获取，进 play/断线释放），
     * 使同一时刻只有一个假人处于配置阶段；主玩家不受影响（假人创建时主玩家早已 PLAYING）。
     */
    private static final java.util.concurrent.locks.ReentrantLock CONFIG_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    public static void lockFakeConfig() {
        CONFIG_LOCK.lock();
    }

    public static void unlockFakeConfig() {
        CONFIG_LOCK.unlock();
    }

    /** 主玩家 transfer 置位（双端 Mixin 调用） */
    public static void setTransferring(boolean transferring) {
        FakeConnectionRegistry.transferring = transferring;
    }

    /** 读取并复位 transfer 标志（双端 Mixin 在 disconnect 时调用）；返回读取前的值 */
    public static boolean takeTransferring() {
        boolean was = FakeConnectionRegistry.transferring;
        FakeConnectionRegistry.transferring = false;
        return was;
    }

    /** 标记一个连接为假人连接，并关联其会话 */
    public static void markFake(Connection connection, FakeSession session) {
        FAKE_SESSIONS.put(connection, session);
    }

    /** 判断连接是否为假人连接 */
    public static boolean isFake(Connection connection) {
        return connection != null && FAKE_SESSIONS.containsKey(connection);
    }

    /** 获取连接对应的假人会话 */
    public static FakeSession getSession(Connection connection) {
        return connection == null ? null : FAKE_SESSIONS.get(connection);
    }

    /** 取消假人标记（连接断开时） */
    public static void unmarkFake(Connection connection) {
        FAKE_SESSIONS.remove(connection);
    }
}

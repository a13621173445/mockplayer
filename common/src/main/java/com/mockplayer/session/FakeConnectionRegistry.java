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
     * 正处于配置阶段的假人数量（登录完成 → 进 play）。
     * neoforge 服务端对假人（TCP）发 FrozenRegistrySyncCompletedPayload，客户端
     * RegistryManager.applySnapshot 会把 BuiltInRegistries.BLOCK 的 tags 覆盖成服务端
     * snapshot（基础态 16），破坏假人本地完整 block tags（395）→ 原版数据包加载缺 tag。
     * 主玩家（内存连接）不走 neoforge 网络同步不受影响。假人配置阶段跳过 applySnapshot。
     *
     * 用计数而非 boolean：多个假人并发登录时，A 先进 play 清除标志不能让仍在配置阶段的
     * B 失去保护（否则 B 的 registry snapshot 跳过失效 → registry 崩 / sync_config 失败）。
     * 置位 +1 / 清除 -1，>0 即存在任一假人处于配置阶段。
     */
    private static final java.util.concurrent.atomic.AtomicInteger configuringFakeCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private FakeConnectionRegistry() {
    }

    /** 标记假人进入配置阶段（FakeLoginListener 登录完成时置位，计数 +1） */
    public static void setConfiguringFake(boolean configuring) {
        if (configuring) {
            FakeConnectionRegistry.configuringFakeCount.incrementAndGet();
        } else {
            int remaining = FakeConnectionRegistry.configuringFakeCount.decrementAndGet();
            if (remaining < 0) {
                // 防御：不应出现（配对调用），但负值会让 isConfiguringFake 语义错乱，归零兜底
                FakeConnectionRegistry.configuringFakeCount.set(0);
            }
        }
    }

    /** 当前是否有假人正在配置阶段（neoforge RegistryManager.applySnapshot 用它跳过假人） */
    public static boolean isConfiguringFake() {
        return FakeConnectionRegistry.configuringFakeCount.get() > 0;
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

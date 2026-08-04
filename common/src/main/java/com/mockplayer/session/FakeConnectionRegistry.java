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

    private FakeConnectionRegistry() {
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

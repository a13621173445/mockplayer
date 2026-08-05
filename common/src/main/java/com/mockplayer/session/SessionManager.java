package com.mockplayer.session;

import com.mockplayer.Constants;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 会话管理器：管理所有假人会话。
 *
 * 单例持有，纯客户端逻辑。职责：
 * - 创建/移除假人
 * - 每 tick 驱动所有假人连接（保持在线）
 * - 重名拒绝
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    /** 假人名字 → 会话 */
    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<>();

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 创建假人。重名拒绝。
     *
     * @param name 假人名字
     * @return true 创建成功，false 重名
     */
    public boolean createFakePlayer(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        FakeSession session = new FakeSession(name);
        // 连接失败（如单人未开放局域网、地址解析失败）：回收会话并通知玩家，避免残留「已存在」。
        // 失败提示走语言文件（i18n），key 由 FakeSession 按失败原因传入。
        session.setOnConnectFail(key -> {
            sessions.remove(name);
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(
                            Component.translatable(key, Component.literal(name).withStyle(ChatFormatting.AQUA))
                                    .withStyle(ChatFormatting.RED));
                }
            });
        });
        FakeSession existing = sessions.putIfAbsent(name, session);
        if (existing != null) {
            Constants.LOG.warn("假人 {} 已存在，拒绝创建", name);
            return false;
        }
        Constants.LOG.info("创建假人 {}", name);
        sessions.get(name).connect();
        return true;
    }

    /**
     * 移除假人。
     *
     * @param name 假人名字
     * @return true 移除成功，false 不存在
     */
    public boolean removeFakePlayer(String name) {
        FakeSession session = sessions.remove(name);
        if (session == null) {
            Constants.LOG.warn("假人 {} 不存在", name);
            return false;
        }
        // 若删除的是被控制的假人 → 自动切回主玩家
        ControlManager.onFakePlayerRemoved(name);
        session.disconnect();
        return true;
    }

    /**
     * 驱动所有假人连接 tick（渲染线程每 tick 调用）。
     */
    public void tick() {
        for (FakeSession session : sessions.values()) {
            session.tick();
        }
    }

    /**
     * 全部假人下线（主玩家退出服务器时调用）。
     */
    public void clearAll() {
        for (FakeSession session : sessions.values()) {
            session.disconnect();
        }
        sessions.clear();
        Constants.LOG.info("主玩家退出，全部假人已下线");
    }

    /**
     * 当前在线假人名字列表。
     */
    public java.util.Collection<String> getFakePlayerNames() {
        return sessions.keySet();
    }

    public FakeSession getSession(String name) {
        return sessions.get(name);
    }
}

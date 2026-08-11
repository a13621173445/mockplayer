package com.mockplayer.api;

import com.mockplayer.api.event.BotListener;

import java.util.List;
import java.util.Optional;

/**
 * Mockplayer 对外 API 门面。
 *
 * 外部 mod 通过 {@link #bots()} 获取 {@link BotManager}，即可创建/管理 bot 并监听事件。
 * 实现层在初始化时调用 {@link #init(BotManager)} 注入单例。
 */
public final class MockplayerApi {

    private static BotManager bots;

    private MockplayerApi() {
    }

    /**
     * 注入 BotManager 单例（实现层初始化时调用；外部只读，不要调用）。
     *
     * @param manager BotManager 实现
     */
    @com.mockplayer.api.Internal
    public static void init(BotManager manager) {
        bots = manager;
    }

    /**
     * 获取 BotManager 单例。
     *
     * @return 全局 BotManager
     * @throws IllegalStateException 若尚未初始化（应在 mod 加载后调用）
     */
    public static BotManager bots() {
        if (bots == null) {
            throw new IllegalStateException("Mockplayer API 尚未初始化");
        }
        return bots;
    }

    /**
     * 所有当前存在的 bot（未初始化时为空列表）。
     *
     * @return bot 列表
     */
    public static List<Bot> allBots() {
        return bots == null ? List.of() : bots.getBots();
    }

    /**
     * 给一个 bot 注册监听器（未初始化时静默忽略）。
     *
     * @param listener BotListener
     */
    public static void listen(BotListener listener) {
        if (bots != null) {
            bots.registerListener(listener);
        }
    }

    /**
     * 按名字找 bot（未初始化时为空）。
     *
     * @param name bot 名字
     * @return {@code Optional<Bot>}
     */
    public static Optional<Bot> bot(String name) {
        return bots == null ? Optional.empty() : bots.getBot(name);
    }
}

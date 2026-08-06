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
        return this.manager.createBot(BotProfile.of(name, BotManagerImpl.COMMAND_OWNER)) != null;
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
}

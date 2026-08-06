package com.mockplayer.api;

import com.mockplayer.api.event.BotListener;

import java.util.List;
import java.util.Optional;

/**
 * Bot 管理器：创建/查询/删除 bot 并管理生命周期。
 *
 * 权限模型：
 * - owner="command"（主玩家命令）是特权，可删除任何 bot；
 * - 外部 mod（owner=modId）只能删除自己创建的 bot；
 * - 跨 owner 删除返回 {@link RemoveResult#NOT_OWNER}。
 */
public interface BotManager {

    /**
     * 创建 bot。重名拒绝。
     *
     * @param profile 创建参数（name/owner 必填；host/port 空 = 跟随当前服务器）
     * @return 创建结果：成功返回 Bot，重名返回 null
     */
    Bot createBot(BotProfile profile);

    /**
     * 按名字查找 bot。
     *
     * @param name bot 名字
     * @return {@code Optional<Bot>}
     */
    Optional<Bot> getBot(String name);

    /**
     * 所有 bot。
     *
     * @return bot 列表
     */
    List<Bot> getBots();

    /**
     * 指定创建者的所有 bot。
     *
     * @param ownerId 创建者标识
     * @return bot 列表
     */
    List<Bot> getBots(String ownerId);

    /**
     * 删除 bot（owner 校验）。
     *
     * @param name    bot 名字
     * @param ownerId 调用者标识（"command" 特权可删任何）
     * @return RemoveResult
     */
    RemoveResult removeBot(String name, String ownerId);

    /**
     * 注册全局 Bot 监听器（监听所有 bot 的事件）。
     *
     * @param listener BotListener
     */
    void registerListener(BotListener listener);
}

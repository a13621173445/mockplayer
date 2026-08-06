package com.mockplayer.api;

/**
 * {@link BotManager#removeBot} 的删除结果。
 */
public enum RemoveResult {
    /** 删除成功 */
    REMOVED,
    /** 指定的 bot 不存在 */
    NOT_FOUND,
    /** 调用者不是该 bot 的 owner 且非特权（command） */
    NOT_OWNER
}

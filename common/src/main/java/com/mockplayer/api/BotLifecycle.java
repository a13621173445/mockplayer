package com.mockplayer.api;

/**
 * Bot 生命周期状态。
 *
 * CONNECTING — 会话已创建，TCP/登录进行中；
 * PLAYING    — 登录完成，LocalPlayer/Level 可用；
 * DISCONNECTED — 已断开/被移除。
 */
public enum BotLifecycle {
    /** 会话已创建，正在连接/登录 */
    CONNECTING,
    /** 登录完成，play 阶段，LocalPlayer 就绪 */
    PLAYING,
    /** 已断开或已移除 */
    DISCONNECTED
}

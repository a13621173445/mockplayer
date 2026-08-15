package com.mockplayer.config;

/**
 * 渲染三态（全局配置，2026-08-16 用户拍板：渲染是主玩家视角的事，不做 per-bot 覆盖）。
 *
 * 两个配置项共用本枚举：navigateRenderMode（寻路路径渲染）与
 * debugOverlayMode（假人 F3 信息标签），各自独立存 ModConfig。
 *
 * i18n 值 key：config.mockplayer.renderMode.&lt;name 小写&gt;
 */
public enum RenderMode {
    /** 总是渲染（不看 F3）。 */
    ALWAYS,
    /** 只在 F3 调试界面打开时渲染（默认）。 */
    F3_ONLY,
    /** 关闭渲染。 */
    OFF
}

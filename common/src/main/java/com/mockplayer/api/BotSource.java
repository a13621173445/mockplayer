package com.mockplayer.api;

/**
 * 假人创建来源（管理边界依据，owner 字符串不可作为依据——附属可伪造）。
 *
 * <p>CORE：本 mod 命令创建路径（/newplayer、批量命令）创建的假人，受本 mod
 * 命令与配置管理。
 *
 * <p>API：经 {@link MockplayerApi#bots()} 公共 API 由外部 / 附属 mod 创建的
 * 假人，本 mod 命令与配置一律不管理；公共 {@link BotManager#createBot} 固定
 * 标记 API，即使调用方把 owner 传成 {@code "command"} 也无法伪造。
 */
public enum BotSource {
    CORE,
    API
}

package com.mockplayer.session;


import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;


/**
 * 假人命令的执行逻辑（与平台无关）。
 * 平台端（Fabric/NeoForge）负责把命令注册进聊天框，逻辑统一走这里。
 * 所有反馈消息走语言文件（en_us / zh_cn），颜色在代码端用 {@link ChatFormatting} 设置。
 */
public class FakePlayerCommands {

    /**
     * 执行 /newplayer 命令。
     *
     * @param name 假人名字
     * @return 反馈消息（发给玩家）
     */
    public static Component newPlayer(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Component.translatable("commands.mockplayer.newplayer.not_in_server")
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
        // 命令创建：走内部 CORE 路径（受命令/配置管理）；owner="command"（特权）
        if (((BotManagerImpl) com.mockplayer.api.MockplayerApi.bots()).createCoreBot(
                com.mockplayer.api.BotProfile.of(name, BotManagerImpl.COMMAND_OWNER)) != null) {
            return Component.translatable("commands.mockplayer.newplayer.success", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.SUCCESS_COLOR);
        } else {
            return Component.translatable("commands.mockplayer.newplayer.fail", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
    }

    /**
     * 执行 /delplayer 命令：只管理本 mod 命令创建的假人（source == CORE）。
     * 外部 / 附属 mod 经 API 创建的假人一律不可见、不可删除。
     */
    public static Component delPlayer(String name) {
        var existing = com.mockplayer.api.MockplayerApi.bots().getBot(name);
        if (existing.isEmpty() || existing.get().source() != com.mockplayer.api.BotSource.CORE) {
            return Component.translatable("commands.mockplayer.delplayer.fail", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
        return switch (com.mockplayer.api.MockplayerApi.bots().removeBot(name, BotManagerImpl.COMMAND_OWNER)) {
            case REMOVED -> Component.translatable("commands.mockplayer.delplayer.success", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.SUCCESS_COLOR);
            case NOT_OWNER -> Component.translatable("commands.mockplayer.delplayer.not_owner", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
            case NOT_FOUND -> Component.translatable("commands.mockplayer.delplayer.fail", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
        };
    }

    /** 批量创建假人（性能测试；委托 BatchCommands，只管理 CORE）。 */
    public static Component newPlayerBatch(String prefix, int count, int intervalTicks, int concurrency) {
        return BatchCommands.newPlayerBatch(prefix, count, intervalTicks, concurrency);
    }

    /** 批量删除假人（性能测试；dry=true 只列不删，只管理 CORE）。 */
    public static Component delPlayerBatch(String prefixOrAll, boolean dry) {
        return BatchCommands.delPlayerBatch(prefixOrAll, dry);
    }

    /**
     * 执行 /connect 命令：让已存在的假人直接连接到指定服务器（不存在不新建）。
     *
     * @param name 已存在的假人名字
     * @param host 目标服务器地址（如 127.0.0.1 / localhost / 域名）
     * @param port 目标服务器端口
     * @return 反馈消息（发给玩家）
     */
    public static Component connectPlayer(String name, String host, int port) {
        FakeSession session = SessionManager.getInstance().getSession(name);
        if (session == null) {
            return Component.translatable("commands.mockplayer.connect.not_found", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
        if (session.getSource() != com.mockplayer.api.BotSource.CORE) {
            // 只管理本 mod 命令创建的假人：API 创建的假人（含附属 mod）不响应 /connect
            return Component.translatable("commands.mockplayer.connect.not_found", CommandSupport.playerName(name))
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
        if (port < 1 || port > 65535) {
            return Component.translatable("commands.mockplayer.connect.invalid_port")
                    .withStyle(CommandSupport.FAIL_COLOR);
        }
        // 断开旧连接重连到指定服务器（reconnecting 保护：旧连接断开不算下线，失败才就地下线）
        session.setReconnecting(true);
        session.disconnect();
        session.connectTo(host, port, null);
        return Component.translatable("commands.mockplayer.connect.success", CommandSupport.playerName(name), host, port)
                    .withStyle(CommandSupport.SUCCESS_COLOR);
    }

    /**
     * Tab 补全：当前所有假人名字（用于 /delplayer）。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> fakePlayerNames() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(
                com.mockplayer.api.MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.source() == com.mockplayer.api.BotSource.CORE)
                        .map(com.mockplayer.api.Bot::getName)
                        .toList(), builder);
    }

}

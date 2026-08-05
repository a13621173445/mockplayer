package com.mockplayer.session;

import com.mockplayer.Constants;

import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 假人命令的执行逻辑（与平台无关）。
 * 平台端（Fabric/NeoForge）负责把命令注册进聊天框，逻辑统一走这里。
 * 所有反馈消息走语言文件（en_us / zh_cn），颜色在代码端用 {@link ChatFormatting} 设置。
 */
public class FakePlayerCommands {

    /** 假人名字显示样式：水蓝色高亮（原版玩家名风格）。 */
    private static final ChatFormatting NAME_COLOR = ChatFormatting.AQUA;

    /** 成功消息颜色。 */
    private static final ChatFormatting SUCCESS_COLOR = ChatFormatting.GREEN;

    /** 失败消息颜色。 */
    private static final ChatFormatting FAIL_COLOR = ChatFormatting.RED;

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
                    .withStyle(FAIL_COLOR);
        }
        if (SessionManager.getInstance().createFakePlayer(name)) {
            return Component.translatable("commands.mockplayer.newplayer.success", playerName(name))
                    .withStyle(SUCCESS_COLOR);
        } else {
            return Component.translatable("commands.mockplayer.newplayer.fail", playerName(name))
                    .withStyle(FAIL_COLOR);
        }
    }

    /**
     * 执行 /delplayer 命令。
     */
    public static Component delPlayer(String name) {
        if (SessionManager.getInstance().removeFakePlayer(name)) {
            return Component.translatable("commands.mockplayer.delplayer.success", playerName(name))
                    .withStyle(SUCCESS_COLOR);
        } else {
            return Component.translatable("commands.mockplayer.delplayer.fail", playerName(name))
                    .withStyle(FAIL_COLOR);
        }
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
            return Component.translatable("commands.mockplayer.connect.not_found", playerName(name))
                    .withStyle(FAIL_COLOR);
        }
        if (port < 1 || port > 65535) {
            return Component.translatable("commands.mockplayer.connect.invalid_port")
                    .withStyle(FAIL_COLOR);
        }
        // 断开旧连接重连到指定服务器（reconnecting 保护：旧连接断开不算下线，失败才就地下线）
        session.setReconnecting(true);
        session.disconnect();
        session.connectTo(host, port, null);
        return Component.translatable("commands.mockplayer.connect.success", playerName(name), host, port)
                .withStyle(SUCCESS_COLOR);
    }

    /**
     * 执行 /control 命令（P1 实现切换）。
     */
    public static Component control(String name) {
        // P1: 切换控制权到该假人
        return Component.translatable("commands.mockplayer.control.not_implemented")
                .withStyle(ChatFormatting.YELLOW);
    }

    /**
     * Tab 补全：当前所有假人名字（用于 /delplayer）。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> fakePlayerNames() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(
                SessionManager.getInstance().getFakePlayerNames(), builder);
    }

    /**
     * Tab 补全：主玩家名字 + 当前所有假人名字（用于 /control）。
     * 玩家本体在前，假人名字在后。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> controlTargets() {
        return (ctx, builder) -> {
            List<String> names = new ArrayList<>();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                names.add(mc.player.getGameProfile().name());
            }
            names.addAll(SessionManager.getInstance().getFakePlayerNames());
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    /**
     * 把假人名字包成水蓝色高亮组件，作为翻译参数传入。
     */
    private static MutableComponent playerName(String name) {
        return Component.literal(name).withStyle(NAME_COLOR);
    }
}

package com.mockplayer.session;

import com.mockplayer.Constants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
     * 执行 /control 命令（P1 实现切换）。
     */
    public static Component control(String name) {
        // P1: 切换控制权到该假人
        return Component.translatable("commands.mockplayer.control.not_implemented")
                .withStyle(ChatFormatting.YELLOW);
    }

    /**
     * 列出当前假人。
     */
    public static Component listPlayers() {
        var names = SessionManager.getInstance().getFakePlayerNames();
        if (names.isEmpty()) {
            return Component.translatable("commands.mockplayer.fakelist.empty")
                    .withStyle(ChatFormatting.GRAY);
        }
        Component joined = names.stream()
                .map(FakePlayerCommands::playerName)
                .reduce((a, b) -> a.append(Component.literal(", ").withStyle(ChatFormatting.GRAY)).append(b))
                .orElse(Component.empty());
        return Component.translatable("commands.mockplayer.fakelist.list", joined)
                .withStyle(SUCCESS_COLOR);
    }

    /**
     * 把假人名字包成水蓝色高亮组件，作为翻译参数传入。
     */
    private static MutableComponent playerName(String name) {
        return Component.literal(name).withStyle(NAME_COLOR);
    }
}

package com.mockplayer.session;

import com.mockplayer.Constants;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 假人命令的执行逻辑（与平台无关）。
 * 平台端（Fabric/NeoForge）负责把命令注册进聊天框，逻辑统一走这里。
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
            return Component.literal("§c你不在服务器中，无法创建假人");
        }
        if (SessionManager.getInstance().createFakePlayer(name)) {
            return Component.literal("§a已创建假人 §b" + name);
        } else {
            return Component.literal("§c假人 §b" + name + "§c 已存在或名字无效");
        }
    }

    /**
     * 执行 /delplayer 命令。
     */
    public static Component delPlayer(String name) {
        if (SessionManager.getInstance().removeFakePlayer(name)) {
            return Component.literal("§a已移除假人 §b" + name);
        } else {
            return Component.literal("§c假人 §b" + name + "§c 不存在");
        }
    }

    /**
     * 执行 /control 命令（P1 实现切换）。
     */
    public static Component control(String name) {
        // P1: 切换控制权到该假人
        return Component.literal("§e/control 尚未实现（P1 阶段）");
    }

    /**
     * 列出当前假人。
     */
    public static Component listPlayers() {
        var names = SessionManager.getInstance().getFakePlayerNames();
        if (names.isEmpty()) {
            return Component.literal("§7当前没有假人");
        }
        return Component.literal("§a当前假人: §b" + String.join("§7, §b", names));
    }
}

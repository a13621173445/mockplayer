package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.config.MockplayerConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * 假人 F3 调试信息标签（名字标签下方一行）。
 *
 * 输入：配置开关 debugOverlayEnabled + F3 调试信息可见（DebugScreenOverlay）
 * 输出：多行 Component（每行带颜色）：❤血量 🍗饱食度(饱和度) 同一行，
 * 💾内存(KB/MB)+📡区块半径(chunk) 同一行，🏃速度(m/s)，📦+容器标题（同一行）
 *
 * 只读假人状态，零主玩家污染；无 player 时返回 null（渲染层不显示）。
 * emoji/数值/单位拼接为通用符号（语言无关），容器标题用原版翻译组件。
 */
public final class DebugNameTagInfo {

    /** 渲染路径验证计数（仅测试属性 mockplayer.debugRenderProbe=true 时记录；私有+反射读取）。 */
    private static volatile int renderCount;
    /** 最近注入的 scoreText（仅测试属性开启时记录）。 */
    private static volatile String lastRendered;
    /** 最近渲染布局：信息行相对名字的 Y 偏移（仅测试属性开启时记录）。 */
    private static volatile float lastInfoOffsetY = -1.0F;
    /** 最近渲染布局：名字自身 Y 偏移（布局断言基准）。 */
    private static volatile float lastNameOffsetY = -1.0F;

    private DebugNameTagInfo() {
    }

    /** 渲染注入记录（生产默认零开销：属性未开启时直接返回，不记录任何数据）。 */
    public static void recordRender(Component info) {
        if (!Boolean.getBoolean("mockplayer.debugRenderProbe")) {
            return;
        }
        DebugNameTagInfo.renderCount++;
        DebugNameTagInfo.lastRendered = info.getString();
    }

    /** 渲染布局探针（仅测试属性开启时记录；infoOffsetY > nameOffsetY = 信息行在名字上方）。 */
    public static void recordRenderLayout(float infoOffsetY, float nameOffsetY) {
        if (!Boolean.getBoolean("mockplayer.debugRenderProbe")) {
            return;
        }
        DebugNameTagInfo.lastInfoOffsetY = infoOffsetY;
        DebugNameTagInfo.lastNameOffsetY = nameOffsetY;
    }

    /** F3 调试信息打开且配置启用（渲染 Mixin 调用）。 */
    public static boolean shouldShow() {
        return Minecraft.getInstance().getDebugOverlay().showDebugScreen()
                && MockplayerConfig.get().isDebugOverlayEnabled();
    }

    /**
     * 多行信息（每行一项，\n 分隔；渲染层按行绘制在假人名字下方）。
     * bot 为空 / 未就绪返回 null（不显示）。
     */
    public static Component format(Bot bot) {
        if (bot == null || bot.getLocalPlayer() == null) {
            return null;
        }
        MutableComponent line = Component.literal("");
        // 血量 + 饥饿度(饱和度) 同一行：❤20 🍗20(14)
        MutableComponent stats = Component.literal(
                "❤" + Math.round(bot.getLocalPlayer().getHealth()))
                .withStyle(ChatFormatting.RED);
        stats.append(Component.literal(" 🍗"
                + bot.getLocalPlayer().getFoodData().getFoodLevel()
                + "(" + Math.round(bot.getLocalPlayer().getFoodData().getSaturationLevel()) + ")")
                .withStyle(ChatFormatting.GOLD));
        line.append(stats);
        // 内存 + 区块加载半径同一行：💾1.2 MB 📡2 chunk（内存青色、半径蓝色）
        MutableComponent memory = Component.literal("💾" + CommandSupport.formatBytes(bot.memoryInfo().trackedBytes()))
                .withStyle(ChatFormatting.AQUA);
        memory.append(Component.literal(" 📡" + bot.getChunkRadius() + " chunk")
                .withStyle(ChatFormatting.BLUE));
        line.append(memory);
        double speed = bot.getLocalPlayer().getDeltaMovement().horizontalDistance() * 20.0;
        appendLine(line, "🏃" + String.format(Locale.ROOT, "%.1f", speed) + " m/s", ChatFormatting.GREEN);
        bot.getContainer().ifPresent(container -> {
            // 📦 与容器名称同一行（同一 sibling；渲染层按 sibling 逐行画）
            MutableComponent containerLine = Component.literal("📦").withStyle(ChatFormatting.YELLOW);
            containerLine.append(container.getTitle());
            line.append(containerLine);
        });
        return line;
    }

    /** 追加一行（emoji + 数值 + 单位，带颜色样式）。 */
    private static void appendLine(MutableComponent line, String part, ChatFormatting color) {
        line.append(Component.literal(part).withStyle(color));
    }

}

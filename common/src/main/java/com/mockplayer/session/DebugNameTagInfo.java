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
 * 输出：多行 Component（每行带颜色）：❤血量 🍗饱食度 💾内存(KB/MB) 🏃速度(m/s) 📦容器标题
 *
 * 只读假人状态，零主玩家污染；无 player 时返回 null（渲染层不显示）。
 * emoji/数值/单位拼接为通用符号（语言无关），容器标题用原版翻译组件。
 */
public final class DebugNameTagInfo {

    /** 渲染路径验证计数（仅测试属性 mockplayer.debugRenderProbe=true 时记录；私有+反射读取）。 */
    private static volatile int renderCount;
    /** 最近注入的 scoreText（仅测试属性开启时记录）。 */
    private static volatile String lastRendered;

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

    /** F3 调试信息打开 && 配置启用（渲染 Mixin 调用）。 */
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
        appendLine(line, "❤" + Math.round(bot.getLocalPlayer().getHealth()), ChatFormatting.RED);
        appendLine(line, "🍗" + bot.getLocalPlayer().getFoodData().getFoodLevel(), ChatFormatting.GOLD);
        appendLine(line, "💾" + formatBytes(bot.memoryInfo().trackedBytes()), ChatFormatting.AQUA);
        double speed = bot.getLocalPlayer().getDeltaMovement().horizontalDistance() * 20.0;
        appendLine(line, "🏃" + String.format(Locale.ROOT, "%.1f", speed) + " m/s", ChatFormatting.GREEN);
        bot.getContainer().ifPresent(container -> {
            line.append(Component.literal("📦").withStyle(ChatFormatting.YELLOW));
            line.append(container.getTitle());
        });
        return line;
    }

    /** 追加一行（emoji + 数值 + 单位，带颜色样式）。 */
    private static void appendLine(MutableComponent line, String part, ChatFormatting color) {
        line.append(Component.literal(part).withStyle(color));
    }

    /** 字节 → KB / MB（≥1MB 用 MB，保留 1 位小数；否则 KB）。 */
    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }
}

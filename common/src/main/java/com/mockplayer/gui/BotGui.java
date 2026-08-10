package com.mockplayer.gui;

import com.mockplayer.config.ModCommands;
import com.mockplayer.config.MockplayerConfig;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 假人控制台 GUI 入口与多分辨率纯函数（common，双端平台 tick 共用）。
 *
 * 输入：配置 guiEnabled（总开关，默认 true）+ guiKeyName（GLFW key name，空串 = 禁用）
 * 输出：平台 tick 检测按键按下 → {@link #open(Minecraft)} 打开 {@link BotControlScreen}
 *
 * 渲染路径探针仅测试属性 mockplayer.guiRenderProbe=true 时记录（生产默认零开销）。
 */
public final class BotGui {

    /** 面板逻辑宽度 / 高度 / 外边距（layoutScale 与 BotControlScreen 共用）。 */
    public static final int PANEL_W = 360;
    public static final int PANEL_H = 240;
    public static final int PAD = 24;

    /** 渲染路径探针计数（仅测试属性开启时记录；私有字段 + 测试反射读取）。 */
    private static volatile int openCount;
    private static volatile int frameCount;
    private static volatile int tickCount;
    private static volatile String lastTitle = "";
    /** 按键边沿检测（按下一次只打开一次，防按住连开）。 */
    private static boolean keyDownPrev;

    private BotGui() {
    }

    /** GUI 功能是否可用（配置总开关 && 按键未禁用）。 */
    public static boolean shouldOpen() {
        var cfg = MockplayerConfig.get();
        return cfg.isGuiEnabled() && !ModCommands.isDisabled(cfg.getGuiKeyName());
    }

    /**
     * 打开假人控制台（测试与快捷键共用同一入口）。
     *
     * @param mc Minecraft
     * @return true 已打开（或本来就在控制台）；配置关闭时 false
     */
    public static boolean open(Minecraft mc) {
        if (!shouldOpen()) {
            return false;
        }
        if (mc.gui.screen() instanceof BotControlScreen) {
            return true;
        }
        mc.gui.setScreen(new BotControlScreen());
        recordOpen();
        return true;
    }

    /**
     * 平台 tick 调用：配置按键按下（边沿）→ 打开 GUI。
     * 按键名非法/禁用时直接忽略；主线程调用。
     */
    public static void tick(Minecraft mc) {
        String keyName = MockplayerConfig.get().getGuiKeyName();
        if (ModCommands.isDisabled(keyName)) {
            BotGui.keyDownPrev = false;
            return;
        }
        int key = InputConstants.getKey(keyName).getValue();
        boolean down = InputConstants.isKeyDown(mc.getWindow(), key);
        if (down && !BotGui.keyDownPrev) {
            open(mc);
        }
        BotGui.keyDownPrev = down;
    }

    /**
     * 整体缩放纯函数：小窗口时把面板缩到能放进屏幕（逻辑坐标），大窗口恒为 1.0。
     *
     * @param width  逻辑屏幕宽（guiScaledWidth）
     * @param height 逻辑屏幕高（guiScaledHeight）
     * @return 缩放系数（0 < scale <= 1）
     */
    public static float layoutScale(int width, int height) {
        float sx = (width - PAD * 2) / (float) PANEL_W;
        float sy = (height - PAD * 2) / (float) PANEL_H;
        return Math.min(1.0F, Math.min(sx, sy));
    }

    /** 面板逻辑 X（居中；缩放后面板尺寸 = PANEL_W * scale）。 */
    public static int panelX(int width, int height) {
        return Math.round((width - PANEL_W * layoutScale(width, height)) / 2.0F);
    }

    /** 面板逻辑 Y（居中）。 */
    public static int panelY(int width, int height) {
        return Math.round((height - PANEL_H * layoutScale(width, height)) / 2.0F);
    }

    /** 面板实际宽度（缩放后）。 */
    public static int panelWidth(int width, int height) {
        return Math.round(PANEL_W * layoutScale(width, height));
    }

    /** 面板实际高度（缩放后）。 */
    public static int panelHeight(int width, int height) {
        return Math.round(PANEL_H * layoutScale(width, height));
    }

    // ===== 渲染路径探针（仅测试属性开启时记录；getter 恒可读，生产零开销） =====

    /** 打开次数探针（BotGui.open 调用）。 */
    public static void recordOpen() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.openCount++;
        }
    }

    /** 渲染帧探针（BotControlScreen.extractRenderState 每帧调用）。 */
    public static void recordFrame(Component title) {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.frameCount++;
            BotGui.lastTitle = title.getString();
        }
    }

    /** tick 探针（BotControlScreen.tick 每 tick 调用；仅测试属性开启时记录）。 */
    public static void recordTick() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.tickCount++;
        }
    }

    /** 测试读取：打开次数（0 = 从未打开）。 */
    public static int probeOpenCount() {
        return BotGui.openCount;
    }

    /** 测试读取：真实渲染帧数（0 = 渲染路径从未执行）。 */
    public static int probeFrameCount() {
        return BotGui.frameCount;
    }

    /** 测试读取：界面 tick 次数（0 = tick 路径从未执行）。 */
    public static int probeTickCount() {
        return BotGui.tickCount;
    }

    /** 测试读取：最近渲染帧的标题文本。 */
    public static String probeLastTitle() {
        return BotGui.lastTitle;
    }
}

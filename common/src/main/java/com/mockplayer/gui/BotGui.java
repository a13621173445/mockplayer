package com.mockplayer.gui;

import com.mockplayer.config.ModCommands;
import com.mockplayer.config.MockplayerConfig;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 假人控制台 GUI 入口与多分辨率纯函数（common，双端平台 tick 共用）。
 *
 * 输入：配置 guiEnabled（总开关，默认 true）+ guiKeyName（GLFW key name，空串 = 禁用）
 * 输出：原版 {@link KeyMapping} 注册后由原版键盘链路计数（界面打开不计数），
 *       平台 tick 调 {@link #tick(Minecraft)} 消费点击 → {@link #open(Minecraft)} 打开 {@link BotControlScreen}
 *
 * 渲染路径探针仅测试属性 mockplayer.guiRenderProbe=true 时记录（生产默认零开销）。
 */
public final class BotGui {

    /** 面板逻辑宽度 / 高度 / 外边距（layoutScale 与 BotControlScreen 共用）。 */
    public static final int PANEL_W = 360;
    public static final int PANEL_H = 240;
    public static final int PAD = 24;

    /** 快捷键注册名（语言文件 key.mockplayer.openGui）。 */
    public static final String KEY_NAME = "key.mockplayer.openGui";

    /**
     * 原版按键：聊天/命令等界面打开时原版键盘链路不计数（与原版 handleKeybinds 门禁一致）。
     * 默认绑定 G（与配置 DEFAULT_GUI_KEY_NAME 一致），改键/禁用由 {@link #applyKeyFromConfig()} 同步。
     */
    public static final KeyMapping KEY_BINDING = new KeyMapping(
            KEY_NAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_G,
            KeyMapping.Category.MISC);

    /** 渲染路径探针计数（仅测试属性开启时记录；私有字段 + 测试反射读取）。 */
    private static volatile int openCount;
    private static volatile int frameCount;
    private static volatile int tickCount;
    private static volatile int slotIconCount;
    private static volatile int tooltipCount;
    private static volatile int carriedCount;
    private static volatile int itemDecorationCount;
    private static volatile int xpBarCount;
    private static volatile int xpBarLevel;
    private static volatile float xpBarProgress;
    private static volatile int healthFoodCount;
    private static volatile float lastHealth;
    private static volatile int lastFood;
    private static volatile String lastTitle = "";

    private BotGui() {
    }

    static {
        // 配置热重载（改键/禁用）立即同步原版 KeyMapping；静态块注册保证 Fabric/NeoForge 共用
        MockplayerConfig.onReload(BotGui::applyKeyFromConfig);
        applyKeyFromConfig();
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
     * 平台 tick 调用：消费原版 {@link KeyMapping} 的点击。
     * 有 screen/overlay 时不消费（与原版 {@link Minecraft#handleKeybinds()} 门禁一致，
     * 聊天框里按 G 不会弹 GUI）；点击按原版一次一消费，防按住连开。
     * 主线程调用。
     */
    public static void tick(Minecraft mc) {
        if (mc.gui.screen() != null || mc.gui.overlay() != null) {
            return;
        }
        while (KEY_BINDING.consumeClick()) {
            open(mc);
        }
    }

    /**
     * 把配置 guiKeyName 同步到原版 KeyMapping：空串 = 禁用（UNKNOWN）；
     * 非法键名同样落到 UNKNOWN（不崩客户端）。改键后重建原版按键路由 MAP。
     */
    public static void applyKeyFromConfig() {
        String keyName = MockplayerConfig.get().getGuiKeyName();
        InputConstants.Key key;
        try {
            key = ModCommands.isDisabled(keyName) ? InputConstants.UNKNOWN : InputConstants.getKey(keyName);
        } catch (IllegalArgumentException e) {
            key = InputConstants.UNKNOWN;
        }
        KEY_BINDING.setKey(key);
        KeyMapping.resetMapping();
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

    /** 空槽图标渲染探针（装备/副手槽背景；仅测试属性开启时记录）。 */
    public static void recordSlotIcon() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.slotIconCount++;
        }
    }

    /** 槽位 tooltip 渲染探针（悬停物品时设置原版 tooltip；仅测试属性开启时记录）。 */
    public static void recordTooltip() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.tooltipCount++;
        }
    }

    /** 鼠标携带物品渲染探针（拿起物品后跟随鼠标绘制；仅测试属性开启时记录）。 */
    public static void recordCarried() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.carriedCount++;
        }
    }

    /** 槽位物品数量/角标渲染探针（原版 itemDecorations；仅测试属性开启时记录）。 */
    public static void recordItemDecoration() {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.itemDecorationCount++;
        }
    }

    /** 经验条渲染探针（记录最近一次等级/进度；仅测试属性开启时记录）。 */
    public static void recordXpBar(int level, float progress) {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.xpBarCount++;
            BotGui.xpBarLevel = level;
            BotGui.xpBarProgress = progress;
        }
    }

    /** 血量/饥饿条渲染探针（原版 HUD sprite；仅测试属性开启时记录）。 */
    public static void recordHealthFood(float health, int food) {
        if (Boolean.getBoolean("mockplayer.guiRenderProbe")) {
            BotGui.healthFoodCount++;
            BotGui.lastHealth = health;
            BotGui.lastFood = food;
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

    /** 测试读取：空槽图标渲染次数（0 = 从未绘制图标）。 */
    public static int probeSlotIconCount() {
        return BotGui.slotIconCount;
    }

    /** 测试读取：槽位 tooltip 设置次数（0 = 从未设置）。 */
    public static int probeTooltipCount() {
        return BotGui.tooltipCount;
    }

    /** 测试读取：携带物品渲染次数（0 = 从未绘制）。 */
    public static int probeCarriedCount() {
        return BotGui.carriedCount;
    }

    /** 测试读取：槽位数量角标渲染次数（0 = 从未绘制）。 */
    public static int probeItemDecorationCount() {
        return BotGui.itemDecorationCount;
    }

    /** 测试读取：经验条渲染次数（0 = 从未绘制）。 */
    public static int probeXpBarCount() {
        return BotGui.xpBarCount;
    }

    /** 测试读取：最近一次经验条等级。 */
    public static int probeXpBarLevel() {
        return BotGui.xpBarLevel;
    }

    /** 测试读取：最近一次经验条进度（0-1）。 */
    public static float probeXpBarProgress() {
        return BotGui.xpBarProgress;
    }

    /** 测试读取：血量/饥饿条渲染次数（0 = 从未绘制）。 */
    public static int probeHealthFoodCount() {
        return BotGui.healthFoodCount;
    }

    /** 测试读取：最近一次血量。 */
    public static float probeLastHealth() {
        return BotGui.lastHealth;
    }

    /** 测试读取：最近一次饥饿值。 */
    public static int probeLastFood() {
        return BotGui.lastFood;
    }

    /** 测试读取：最近渲染帧的标题文本。 */
    public static String probeLastTitle() {
        return BotGui.lastTitle;
    }
}

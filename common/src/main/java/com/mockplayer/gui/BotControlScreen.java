package com.mockplayer.gui;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotSource;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.FakePlayerCommands;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 假人控制台（common，纯原版 Screen）。
 *
 * 输入：选中的 CORE 假人（本 mod 命令创建，管理边界与命令一致）
 * 输出：左栏假人列表 + 顶部 Tab（状态/背包/容器/动作）+ 底部反馈；按钮直调
 * BotActions / BotContainer / FakePlayerCommands 真实链路，不绕聊天命令。
 *
 * 布局全部用面板逻辑坐标（BotGui.PANEL_W x PANEL_H），多分辨率缩放由
 * BotGui.layoutScale 纯函数 + pose 变换 + 输入坐标逆变换完成。
 */
public class BotControlScreen extends Screen {

    static final int LIST_X = 8;
    static final int LIST_W = 88;
    private static final int LIST_TITLE_Y = 26;
    static final int LIST_TOP = 34;
    private static final int BOT_ROW_H = 15;
    /** 底部输入区上限：名字输入框一行 + 新建/删除一行（假人多时固定在此，列表区滚动）。 */
    private static final int BOT_INPUT_Y = BotGui.PANEL_H - 14 - 32;
    private static final int BOT_BTN_Y = BOT_INPUT_Y + 16;
    private static final int BOT_INPUT_H = 14;
    /** 左栏列表区底部（滑条轨道范围），输入区固定底部，列表区固定高度。 */
    public static final int LIST_BOTTOM = BOT_INPUT_Y - 4;
    /** 左栏假人列表最大可见槽位数（输入区固定底部，超过则滑条滚动）。 */
    public static final int VISIBLE_BOT_SLOTS = (LIST_BOTTOM - LIST_TOP) / BOT_ROW_H;
    /** 背包容器模式顶部标题行高度（X 按钮 + 容器标题）。 */
    public static final int CONTAINER_HEADER_H = 16;
    static final int CONTENT_X = 104;
    static final int CONTENT_W = BotGui.PANEL_W - CONTENT_X - 8;
    private static final int TAB_Y = 24;
    static final int CONTENT_Y = 44;
    private static final int FEEDBACK_Y = BotGui.PANEL_H - 14;
    static final int CELL = 20;
    static final int SLOT = 18;
    // ===== 半透明面板配色（alpha < 0xFF，透出游戏场景） =====
    public static final int PANEL_BG_TOP = 0xB0253047;
    public static final int PANEL_BG_BOTTOM = 0xB00A0D16;
    public static final int PANEL_HEADER_BG = 0xB0141D2C;
    public static final int PANEL_ACCENT = 0xB03A86FF;
    public static final int PANEL_DIVIDER = 0xB01B2537;
    public static final int PANEL_BORDER = 0x993A4A6A;
    public static final int PANEL_BORDER_INNER = 0x99151C29;
    public static final int SLOT_BG = 0x8F222B3A;
    public static final int SLOT_BG_HOVER = 0x8F3E4C66;
    public static final int SLOT_BORDER = 0x8F0E1420;
    public static final int SLOT_BORDER_HOVER = 0xBF7FB2FF;
    /** 调整型按钮长按重复间隔（毫秒）：视线 200ms、区块 300ms。 */
    public static final int TURN_REPEAT_MS = 200;
    public static final int CHUNK_REPEAT_MS = 300;
    /** 交互行按钮尺寸/间距（逻辑坐标，多分辨率由 sx/sw 缩放）。 */
    public static final int ACT_BTN_W = 74;
    public static final int ACT_BTN_H = 14;
    public static final int ACT_GAP = 76;
    /** 动作 Tab 响应式布局：分区高度常量，按顺序堆叠（标题 = 分区顶 - TITLE_H，不重叠）。 */
    public static final int TAB_BOTTOM = 38;
    public static final int ACT_TOP = CONTENT_Y + 2;
    public static final int BTN_H = 13;
    public static final int BTN_GAP = 1;
    public static final int SECTION_GAP = 3;
    public static final int ROW_H = BTN_H + BTN_GAP;
    public static final int TITLE_H = 8;
    /** 标题行高度（标题文字 + 与按钮间距），标题独立占一行不压按钮。 */
    public static final int TITLE_ROW_H = TITLE_H + 2;
    /** 动作 Tab 十字/列/系统行的逻辑坐标常量（全部相对 CONTENT_X 推导，不写散落数字）。 */
    public static final int CROSS_LEFT_OFF = 4;
    public static final int CROSS_MID_OFF = 40;
    public static final int CROSS_RIGHT_OFF = 76;
    public static final int PAD_BTN_W = 30;
    public static final int SIDE_COL_1_OFF = 130;
    public static final int SIDE_COL_2_OFF = 184;
    public static final int SIDE_BTN_W = 52;
    public static final int ENTITY_W = 44;
    public static final int ENTITY_GAP = 46;
    /** 实体按钮从内容区右缘向左排（宽度/间距推导，永不超出面板）。 */
    public static final int ENTITY_X_OFF = CONTENT_W - ENTITY_W * 2 - ENTITY_GAP - 4;
    public static final int CHUNK_BTN_W = 40;
    public static final int CHUNK_GAP = 4;
    public static final int RESPAWN_W = 44;
    public static final int RESPAWN_X_OFF = CHUNK_BTN_W * 2 + CHUNK_GAP * 2;
    public static final int AUTO_X_OFF = RESPAWN_X_OFF + RESPAWN_W + CHUNK_GAP;
    public static final int AUTO_W = 66;
    public static final int CHAT_W = 180;
    public static final int SEND_X_OFF = 182;
    public static final int SEND_W = 66;
    /** 开关按钮状态色：开启绿 / 关闭红（文字颜色表示状态，不再拼 开/关 后缀）。 */
    public static final int TOGGLE_ON_COLOR = 0xFF55FF55;
    public static final int TOGGLE_OFF_COLOR = 0xFFFF5555;

    /** 当前选中假人（null = 未选中）。 */
    Bot selected;
    /** 当前 Tab：0 状态 / 1 背包 / 2 容器 / 3 动作。 */
    private int tab;
    /** 底部反馈行（i18n 组件，动作结果/错误）。 */
    private Component feedback = Component.literal("");
    /** 反馈是否错误（错误红 / 成功绿 / 空灰）。 */
    private boolean feedbackError;

    // ===== 控件（init 重建，tick 更新状态） =====
    private Button closeButton;
    private final List<Button> botButtons = new ArrayList<>();
    /** 新建/删除共用的假人名字输入框。 */
    private EditBox nameBox;
    private Button newButton;
    private Button delButton;
    /** 左栏假人列表滚动偏移（0 = 显示第一个）。 */
    int botScrollOffset;
    /** 左栏滑条是否正在拖动。 */
    private boolean scrollbarDragging;
    /** 原版 HUD 血量渲染状态（闪烁动画，与 Hud 同逻辑）。 */
    int tickCount;
    long lastHealthTime;
    int displayHealth = -1;
    int lastHealth = -1;
    long healthBlinkTime;
    final java.util.Random heartRandom = new java.util.Random();
    private Button tabStatus;
    private Button tabInventory;
    private Button tabActions;
    private RepeatHoldButton turnLeft;
    private RepeatHoldButton turnRight;
    private RepeatHoldButton turnUp;
    private RepeatHoldButton turnDown;
    private HoldButton moveForward;
    private HoldButton moveBackward;
    private HoldButton moveLeft;
    private HoldButton moveRight;
    private Button stopButton;
    private Button sneakButton;
    private Button sprintButton;
    private Button jumpButton;
    private ModeTapHoldButton attackLookButton;
    private ModeTapHoldButton useLookButton;
    /** 动作按钮跨 GUI 开关的状态（类似疾跑：关闭 GUI 不停止，停止按钮全停）。 */
    private static final ModeTapHoldButton.State ATTACK_BUTTON_STATE = new ModeTapHoldButton.State();
    private static final ModeTapHoldButton.State USE_BUTTON_STATE = new ModeTapHoldButton.State();
    private RepeatHoldButton chunkMinusButton;
    private RepeatHoldButton chunkPlusButton;
    private Button respawnButton;
    private Button autoRespawnButton;
    private Button closeContainerButton;
    private EditBox chatBox;
    private Button sendButton;
    private final List<Button> entityButtons = new ArrayList<>();
    /** 实体按钮当前绑定的实体（防重建时取到已死实体）。 */
    private final List<Entity> entityTargets = new ArrayList<>();
    /** 长按重复按钮（turn/chunk 等调整型控件，tick 驱动重复触发）。 */
    private final List<RepeatHoldButton> repeatButtons = new ArrayList<>();
    /** 动作 Tab 分区标题逻辑 y（init 响应式计算，BotActionsPanel 渲染用）。 */
    int lookTitleY;
    int moveTitleY;
    int interactTitleY;
    int systemTitleY;

    public BotControlScreen() {
        super(Component.translatable("gui.mockplayer.title"));
    }

    @Override
    public boolean isPauseScreen() {
        // 控制台不是暂停界面：单机不开 LAN 时打开 GUI 也不能把世界停住（假人还要继续动）
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 背景由 extractRenderState 自绘半透明面板，面板外直接透出游戏画面；
        // 高斯模糊按 guiBlur 配置（>0 时触发原版 blur，强度由临时设置的主玩家选项决定）
        if (MockplayerConfig.get().getGuiBlur() > 0) {
            graphics.blurBeforeThisStratum();
        }
    }

    @Override
    public void removed() {
        // 关闭 GUI 不停锁存的长按/连点动作（类似疾跑的开关状态，由停止按钮 stop() 全停）
        super.removed();
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.botButtons.clear();
        this.entityButtons.clear();
        this.entityTargets.clear();
        this.repeatButtons.clear();
        this.selected = firstSelectableBot();
        if (this.tab > 2) {
            this.tab = 0;
        }
        this.feedback = Component.literal("");

        // ===== 顶栏：标题 + 关闭（按钮底边让出蓝色分隔线 y=17，不压线） =====
        this.closeButton = this.addButton(BotGui.PANEL_W - 48, 2, 40, 14,
                "gui.mockplayer.close", () -> this.onClose());

        // ===== 左栏：假人列表（10 个可见槽位，多假人滚轮/▲▼ 滚动） =====
        for (int i = 0; i < VISIBLE_BOT_SLOTS; i++) {
            int index = i;
            Button b = Button.builder(Component.literal(""), btn -> {
                List<Bot> bots = coreBots();
                int target = index + this.botScrollOffset;
                if (target < bots.size()) {
                    this.select(bots.get(target));
                }
            }).bounds(sx(LIST_X), sy(LIST_TOP + i * BOT_ROW_H), sw(LIST_W), sh(BOT_ROW_H - 1)).build();
            b.setAlpha(this.currentButtonAlpha());
            this.addRenderableWidget(b);
            this.botButtons.add(b);
        }
        // 初始列表可见性：只显示已有假人（防止打开首帧在 tick 前闪出空槽按钮）
        List<Bot> initialBots = coreBots();
        for (int i = 0; i < this.botButtons.size(); i++) {
            this.botButtons.get(i).visible = i < Math.min(initialBots.size(), VISIBLE_BOT_SLOTS);
        }
        // 左栏底部：名字输入框一行，下面并排「+ 新建 / - 删除」
        this.nameBox = new EditBox(this.font, sx(LIST_X), sy(BOT_INPUT_Y), sw(LIST_W), sh(BOT_INPUT_H),
                Component.translatable("gui.mockplayer.name_hint"));
        this.nameBox.setMaxLength(16);
        this.addRenderableWidget(this.nameBox);
        this.newButton = this.addButton(LIST_X, BOT_BTN_Y, 43, BOT_INPUT_H, "gui.mockplayer.new_bot",
                () -> this.tryCreate());
        this.delButton = this.addButton(LIST_X + 45, BOT_BTN_Y, 43, BOT_INPUT_H, "gui.mockplayer.delete_bot",
                () -> this.tryDelete());

        // ===== 顶部 Tab =====
        int tabW = (CONTENT_W - 2 * 2) / 3;
        this.tabStatus = this.addButton(CONTENT_X, TAB_Y, tabW, 14,
                "gui.mockplayer.tab.status", () -> this.switchTab(0));
        this.tabInventory = this.addButton(CONTENT_X + (tabW + 2), TAB_Y, tabW, 14,
                "gui.mockplayer.tab.inventory", () -> this.switchTab(1));
        this.tabActions = this.addButton(CONTENT_X + (tabW + 2) * 2, TAB_Y, tabW, 14,
                "gui.mockplayer.tab.actions", () -> this.switchTab(2));

        // ===== 动作 Tab（响应式：分区按顺序堆叠，标题 = 分区顶 - TITLE_H，改常量自动重排） =====
        int actY = ACT_TOP;
        int rowH = ROW_H;
        int crossX = CONTENT_X + CROSS_MID_OFF;
        int leftX = CONTENT_X + CROSS_LEFT_OFF;
        int rightX = CONTENT_X + CROSS_RIGHT_OFF;
        this.lookTitleY = actY;
        actY += TITLE_ROW_H;
        this.turnUp = this.addRepeat(crossX, actY, PAD_BTN_W, BTN_H, "gui.mockplayer.action.turn_up",
                () -> this.act(b -> b.actions().turn(0.0F, -8.0F), "gui.mockplayer.action.turn_up"),
                TURN_REPEAT_MS);
        this.turnLeft = this.addRepeat(leftX, actY + rowH, PAD_BTN_W, BTN_H, "gui.mockplayer.action.turn_left",
                () -> this.act(b -> b.actions().turn(-15.0F, 0.0F), "gui.mockplayer.action.turn_left"),
                TURN_REPEAT_MS);
        this.turnRight = this.addRepeat(rightX, actY + rowH, PAD_BTN_W, BTN_H, "gui.mockplayer.action.turn_right",
                () -> this.act(b -> b.actions().turn(15.0F, 0.0F), "gui.mockplayer.action.turn_right"),
                TURN_REPEAT_MS);
        this.turnDown = this.addRepeat(crossX, actY + rowH * 2, PAD_BTN_W, BTN_H, "gui.mockplayer.action.turn_down",
                () -> this.act(b -> b.actions().turn(0.0F, 8.0F), "gui.mockplayer.action.turn_down"),
                TURN_REPEAT_MS);
        int lookTop = actY;
        actY += rowH * 3 + SECTION_GAP;

        this.moveTitleY = actY;
        actY += TITLE_ROW_H;
        this.moveForward = this.addHold(crossX, actY, PAD_BTN_W, BTN_H, "gui.mockplayer.action.move_forward",
                () -> this.act(b -> b.actions().setForward(1.0F), "gui.mockplayer.action.move_forward"),
                () -> this.actQuiet(b -> b.actions().setForward(0.0F)));
        this.moveLeft = this.addHold(leftX, actY + rowH, PAD_BTN_W, BTN_H, "gui.mockplayer.action.move_left",
                () -> this.act(b -> b.actions().setStrafe(1.0F), "gui.mockplayer.action.move_left"),
                () -> this.actQuiet(b -> b.actions().setStrafe(0.0F)));
        this.moveRight = this.addHold(rightX, actY + rowH, PAD_BTN_W, BTN_H, "gui.mockplayer.action.move_right",
                () -> this.act(b -> b.actions().setStrafe(-1.0F), "gui.mockplayer.action.move_right"),
                () -> this.actQuiet(b -> b.actions().setStrafe(0.0F)));
        this.moveBackward = this.addHold(crossX, actY + rowH * 2, PAD_BTN_W, BTN_H, "gui.mockplayer.action.move_backward",
                () -> this.act(b -> b.actions().setForward(-1.0F), "gui.mockplayer.action.move_backward"),
                () -> this.actQuiet(b -> b.actions().setForward(0.0F)));
        this.stopButton = this.addButton(CONTENT_X + SIDE_COL_1_OFF, actY, SIDE_BTN_W, BTN_H, "gui.mockplayer.action.stop",
                () -> {
                    this.act(b -> b.actions().stop(), "gui.mockplayer.action.stop");
                    // 停止按钮：连点/长按的锁存也全部清掉（动作在 BotActions，已由 stop() 停止）
                    this.attackLookButton.stopLatched();
                    this.useLookButton.stopLatched();
                });
        this.sneakButton = this.addButton(CONTENT_X + SIDE_COL_2_OFF, actY, SIDE_BTN_W, BTN_H, "gui.mockplayer.action.sneak",
                () -> this.toggleSneak());
        this.sprintButton = this.addButton(CONTENT_X + SIDE_COL_1_OFF, actY + rowH, SIDE_BTN_W, BTN_H, "gui.mockplayer.action.sprint",
                () -> this.toggleSprint());
        this.jumpButton = this.addButton(CONTENT_X + SIDE_COL_2_OFF, actY + rowH, SIDE_BTN_W, BTN_H, "gui.mockplayer.action.jump",
                () -> this.toggleJump());
        actY += rowH * 3 + SECTION_GAP;

        this.interactTitleY = actY;
        actY += TITLE_ROW_H;
        this.attackLookButton = this.addModeTapHold(CONTENT_X, actY, ACT_BTN_W, ACT_BTN_H,
                "gui.mockplayer.action.attack_look", "gui.mockplayer.action.attack_hold",
                "gui.mockplayer.action.attack_rapid",
                () -> this.actQuiet(b -> b.actions().attackLook()),
                () -> this.actQuiet(b -> b.actions().sustainedAttackLook()),
                () -> this.actQuiet(b -> b.actions().stopSustained()),
                ATTACK_BUTTON_STATE,
                value -> this.actQuiet(b -> b.actions().setRapidAttack(value)));
        this.useLookButton = this.addModeTapHold(CONTENT_X + ACT_GAP, actY, ACT_BTN_W, ACT_BTN_H,
                "gui.mockplayer.action.use_look", "gui.mockplayer.action.use_hold",
                "gui.mockplayer.action.use_rapid",
                () -> this.actQuiet(b -> b.actions().useLook()),
                () -> this.actQuiet(b -> b.actions().sustainedUseLook()),
                () -> this.actQuiet(b -> b.actions().stopSustained()),
                USE_BUTTON_STATE,
                value -> this.actQuiet(b -> b.actions().setRapidUse(value)));
        actY += ACT_BTN_H + SECTION_GAP;

        this.systemTitleY = actY;
        actY += TITLE_ROW_H;
        this.chunkMinusButton = this.addRepeat(CONTENT_X, actY, CHUNK_BTN_W, BTN_H,
                "gui.mockplayer.action.chunk_minus", () -> this.changeChunk(-1), CHUNK_REPEAT_MS);
        this.chunkPlusButton = this.addRepeat(CONTENT_X + CHUNK_BTN_W + CHUNK_GAP, actY, CHUNK_BTN_W, BTN_H,
                "gui.mockplayer.action.chunk_plus", () -> this.changeChunk(1), CHUNK_REPEAT_MS);
        this.respawnButton = this.addButton(CONTENT_X + RESPAWN_X_OFF, actY, RESPAWN_W, BTN_H,
                "gui.mockplayer.action.respawn",
                () -> this.act(Bot::actions, "gui.mockplayer.action.respawn", actions -> actions.respawn()));
        this.autoRespawnButton = this.addButton(CONTENT_X + AUTO_X_OFF, actY, AUTO_W, BTN_H,
                "gui.mockplayer.action.auto_respawn", () -> this.toggleAutoRespawn());
        actY += BTN_H + SECTION_GAP;

        this.chatBox = new EditBox(this.font, sx(CONTENT_X), sy(actY), sw(CHAT_W), sh(BTN_H),
                Component.translatable("gui.mockplayer.action.chat_hint"));
        this.chatBox.setMaxLength(256);
        this.addRenderableWidget(this.chatBox);
        this.sendButton = this.addButton(CONTENT_X + SEND_X_OFF, actY, SEND_W, BTN_H, "gui.mockplayer.action.send",
                () -> this.sendChat());

        // 关闭容器 X 按钮：仅背包 Tab 容器模式显示（动作 Tab 不再有关闭按钮）
        this.closeContainerButton = this.addLiteralButton(CONTENT_X, CONTENT_Y, 12, 12, "×",
                () -> this.actQuiet(b -> b.getContainer().ifPresent(BotContainer::close)));

        // ===== 附近实体（视线区右侧单行 2 个，点击 = bot 转头；实体再多也只显示最近 2 个） =====
        for (int i = 0; i < 2; i++) {
            int index = i;
            Button b = Button.builder(Component.literal(""), btn -> {
                if (index < this.entityTargets.size()) {
                    Entity target = this.entityTargets.get(index);
                    this.act(bot -> bot.actions().lookAt(target), "gui.mockplayer.action.look_at");
                }
            }).bounds(sx(CONTENT_X + ENTITY_X_OFF + index * ENTITY_GAP), sy(lookTop),
                    sw(ENTITY_W), sh(BTN_H)).build();
            b.setAlpha(this.currentButtonAlpha());
            this.addRenderableWidget(b);
            this.entityButtons.add(b);
        }
        // 初始可见性立即生效（防止打开 GUI 第一帧闪出全部按钮）
        this.refreshActionTabVisibility();
    }

    // ===== 控件工厂 =====

    private Button addButton(int x, int y, int w, int h, String key, Runnable action) {
        Button b = Button.builder(Component.translatable(key), btn -> action.run())
                .bounds(sx(x), sy(y), sw(w), sw(h)).build();
        b.setAlpha(this.currentButtonAlpha());
        this.addRenderableWidget(b);
        return b;
    }

    /** 字面文本按钮（× 等符号，无需 i18n）。 */
    private Button addLiteralButton(int x, int y, int w, int h, String text, Runnable action) {
        Button b = Button.builder(Component.literal(text), btn -> action.run())
                .bounds(sx(x), sy(y), sw(w), sw(h)).build();
        b.setAlpha(this.currentButtonAlpha());
        this.addRenderableWidget(b);
        return b;
    }

    /** 当前按钮 alpha（由 guiOpacity 配置推导，热重载后重开 GUI 生效）。 */
    private float currentButtonAlpha() {
        return BotControlHud.buttonAlpha(MockplayerConfig.get().getGuiOpacity());
    }

    /** 按住持续按钮：按下执行 start，松开执行 end（移动/长按交互）。 */
    private HoldButton addHold(int x, int y, int w, int h, String key,
                               Runnable start, Runnable end) {
        HoldButton b = new HoldButton(sx(x), sy(y), sw(w), sw(h),
                Component.translatable(key), start, end);
        b.setAlpha(this.currentButtonAlpha());
        this.addRenderableWidget(b);
        return b;
    }

    /**
     * 动作模式按钮：默认 = 单击 + 按住持续（松开停止，原版行为）；
     * 右键循环切换 长按 → 连点 → 默认；长按/连点模式左键开关锁存（激活变红，激活时禁止右键切换）。
     * 锁存状态跨 GUI 开关持久（类似疾跑的开关状态，关闭 GUI 不停止，停止按钮全停）。
     */
    private ModeTapHoldButton addModeTapHold(int x, int y, int w, int h,
                                             String baseKey, String holdKey, String rapidKey,
                                             Runnable tap, Runnable holdStart, Runnable holdEnd,
                                             ModeTapHoldButton.State state,
                                             java.util.function.Consumer<Boolean> rapidSetter) {
        ModeTapHoldButton b = new ModeTapHoldButton(sx(x), sy(y), sw(w), sw(h),
                baseKey, holdKey, rapidKey, state, tap, holdStart, holdEnd, rapidSetter);
        b.setAlpha(this.currentButtonAlpha());
        this.addRenderableWidget(b);
        return b;
    }

    /** 长按重复按钮：单击立即执行，按住后按 intervalMs 重复（视线/区块调整）。 */
    private RepeatHoldButton addRepeat(int x, int y, int w, int h, String key,
                                       Runnable action, long intervalMs) {
        RepeatHoldButton b = new RepeatHoldButton(sx(x), sy(y), sw(w), sw(h),
                Component.translatable(key), action, intervalMs);
        b.setAlpha(this.currentButtonAlpha());
        this.addRenderableWidget(b);
        this.repeatButtons.add(b);
        return b;
    }

    /** 按住持续按钮（原版 Button 子类：onClick 触发 start，onRelease 触发 end）。 */
    private static final class HoldButton extends Button {
        private final Runnable holdStart;
        private final Runnable holdEnd;

        HoldButton(int x, int y, int w, int h, Component message, Runnable start, Runnable end) {
            super(x, y, w, h, message, btn -> {
            }, DEFAULT_NARRATION);
            this.holdStart = start;
            this.holdEnd = end;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.holdStart.run();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            this.holdEnd.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            this.extractDefaultSprite(graphics);
            this.extractDefaultLabel(graphics.textRendererForWidget(
                    this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }

    /**
     * 动作模式按钮（attack/use 共用）：默认 = 单击 + 按住持续；
     * 右键切 长按/连点/默认；长按/连点左键开关锁存，激活变红且禁止右键切换。
     * 状态存 State（跨 GUI 开关持久）：HOLD 调 holdStart/holdEnd，RAPID 调 rapidSetter，
     * 实际动作在 BotActions（类似疾跑），关闭 GUI 不停、停止按钮 stop() 全停。
     */
    private static final class ModeTapHoldButton extends Button {
        private enum Mode { TAP, HOLD, RAPID }

        /** 跨 GUI 开关持久的状态（每类按钮一份静态实例）。 */
        static final class State {
            Mode mode = Mode.TAP;
            boolean latched;
        }

        private final Runnable tap;
        private final Runnable holdStart;
        private final Runnable holdEnd;
        private final java.util.function.Consumer<Boolean> rapidSetter;
        private final String baseKey;
        private final String holdKey;
        private final String rapidKey;
        private final State state;
        private Mode mode;
        private boolean latched;

        ModeTapHoldButton(int x, int y, int w, int h,
                          String baseKey, String holdKey, String rapidKey,
                          State state, Runnable tap, Runnable holdStart, Runnable holdEnd,
                          java.util.function.Consumer<Boolean> rapidSetter) {
            super(x, y, w, h, Component.translatable(baseKey), btn -> {
            }, DEFAULT_NARRATION);
            this.baseKey = baseKey;
            this.holdKey = holdKey;
            this.rapidKey = rapidKey;
            this.state = state;
            this.mode = state.mode;
            this.latched = state.latched;
            this.tap = tap;
            this.holdStart = holdStart;
            this.holdEnd = holdEnd;
            this.rapidSetter = rapidSetter;
            this.applyMessage();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.isActive() && this.isMouseOver(event.x(), event.y())
                    && event.buttonInfo().button() == 1) {
                // 右键切换模式；激活（长按/连点进行中）时禁止切换
                if (!this.latched) {
                    this.cycleMode();
                }
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            switch (this.mode) {
                case TAP -> {
                    this.tap.run();
                    this.holdStart.run();
                }
                case HOLD -> {
                    if (this.latched) {
                        this.latched = false;
                        this.holdEnd.run();
                    } else {
                        this.latched = true;
                        this.holdStart.run();
                    }
                    this.applyMessage();
                }
                case RAPID -> {
                    this.latched = !this.latched;
                    this.rapidSetter.accept(this.latched);
                    this.applyMessage();
                }
            }
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            // 默认模式保持原版「按住持续/松开停止」；长按/连点是开关锁存，不随松开结束
            if (this.mode == Mode.TAP) {
                this.holdEnd.run();
            }
        }

        private void cycleMode() {
            this.mode = switch (this.mode) {
                case TAP -> Mode.HOLD;
                case HOLD -> Mode.RAPID;
                case RAPID -> Mode.TAP;
            };
            this.applyMessage();
        }

        /** 停止按钮用：清锁存 + 停掉对应动作（不随 GUI 关闭调用——关闭 GUI 时状态保持）。 */
        void stopLatched() {
            if (this.latched) {
                this.latched = false;
                if (this.mode == Mode.HOLD) {
                    this.holdEnd.run();
                } else if (this.mode == Mode.RAPID) {
                    this.rapidSetter.accept(false);
                }
                this.applyMessage();
            }
        }

        /** 选中假人变化时按 bot 真实动作状态校准锁存（避免 UI 与 BotActions 脱节）。 */
        void syncLatched(boolean active) {
            if (this.latched != active) {
                this.latched = active;
                this.applyMessage();
            }
        }

        private void applyMessage() {
            String key = switch (this.mode) {
                case TAP -> this.baseKey;
                case HOLD -> this.holdKey;
                case RAPID -> this.rapidKey;
            };
            Component message = Component.translatable(key);
            if (this.latched) {
                message = message.copy().withStyle(net.minecraft.ChatFormatting.RED);
            }
            this.state.mode = this.mode;
            this.state.latched = this.latched;
            this.setMessage(message);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            this.extractDefaultSprite(graphics);
            this.extractDefaultLabel(graphics.textRendererForWidget(
                    this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }

    /**
     * 长按重复按钮：onClick 立即执行一次，按住后按 intervalMs 重复（调整型按钮，
     * 时间基于 {@link Util#getMillis()}，与 TPS/分辨率无关）。
     */
    private static final class RepeatHoldButton extends Button {
        private final Runnable repeat;
        private final long intervalMs;
        private boolean pressed;
        private long lastMillis;

        RepeatHoldButton(int x, int y, int w, int h, Component message,
                         Runnable repeat, long intervalMs) {
            super(x, y, w, h, message, btn -> {
            }, DEFAULT_NARRATION);
            this.repeat = repeat;
            this.intervalMs = Math.max(1L, intervalMs);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.pressed = true;
            this.lastMillis = Util.getMillis();
            this.repeat.run();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            this.pressed = false;
        }

        /** tick 驱动：按住时按间隔重复触发。 */
        void onTick(long now) {
            if (this.pressed && now - this.lastMillis >= this.intervalMs) {
                this.lastMillis = now;
                this.repeat.run();
            }
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            this.extractDefaultSprite(graphics);
            this.extractDefaultLabel(graphics.textRendererForWidget(
                    this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }

    // ===== 数据/选择 =====

    /** 只列 CORE 假人（管理边界：GUI 与本 mod 命令一致，不管理 API/附属创建）。 */
    static List<Bot> coreBots() {
        return MockplayerApi.bots().getBots().stream()
                .filter(b -> b.source() == BotSource.CORE)
                .toList();
    }

    private static Bot firstSelectableBot() {
        return coreBots().stream().findFirst().orElse(null);
    }

    private void select(Bot bot) {
        this.selected = bot;
        // 锁存状态按新选中假人的真实动作状态校准（关闭 GUI 后状态仍持续，重开时正确显示）
        if (bot != null && this.attackLookButton != null && this.useLookButton != null) {
            this.attackLookButton.syncLatched(
                    bot.actions().isSustainedAttacking() || bot.actions().isRapidAttacking());
            this.useLookButton.syncLatched(
                    bot.actions().isSustainedUsing() || bot.actions().isRapidUsing());
        }
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.selected", bot.getName()));
    }

    /** 左栏列表滚动（delta = ±1），偏移量钳制在可见范围内。 */
    private void scrollBotList(int delta) {
        this.botScrollOffset = clampBotScroll(this.botScrollOffset + delta,
                coreBots().size(), VISIBLE_BOT_SLOTS);
    }

    /** 列表滚动偏移钳制（纯函数，滚轮/▲▼/测试共用）。 */
    public static int clampBotScroll(int offset, int total, int visible) {
        if (total <= visible) {
            return 0;
        }
        return Math.max(0, Math.min(offset, total - visible));
    }

    /** 是否显示滑条（假人数量超过可见槽位时）。 */
    public static boolean shouldShowScrollbar(int total, int visible) {
        return total > visible;
    }

    /** 滑条区域命中（左栏列表右侧窄条）。 */
    private boolean scrollbarHit(double lx, double ly) {
        return lx >= LIST_X + LIST_W - 5 && lx <= LIST_X + LIST_W + 1
                && ly >= LIST_TOP && ly <= LIST_BOTTOM;
    }

    /** 按滑条位置（逻辑 y）定位列表偏移。 */
    private void scrollBotTo(double lx, double ly) {
        List<Bot> bots = coreBots();
        if (bots.size() <= VISIBLE_BOT_SLOTS) {
            return;
        }
        int trackH = LIST_BOTTOM - LIST_TOP;
        float ratio = (float) (ly - LIST_TOP) / trackH;
        this.botScrollOffset = clampBotScroll(
                Math.round(ratio * (bots.size() - VISIBLE_BOT_SLOTS)),
                bots.size(), VISIBLE_BOT_SLOTS);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double lx = this.localX(x);
        double ly = this.localY(y);
        // 滚轮在左栏列表区域 → 滚动假人列表（向上滚 = 显示更早的假人）
        if (lx >= LIST_X && lx <= LIST_X + LIST_W && ly >= LIST_TOP && ly <= LIST_BOTTOM) {
            this.scrollBotList(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private void switchTab(int tab) {
        this.tab = tab;
        // 立即刷新动作控件可见性：不等 tick，切 Tab 不闪一帧
        this.refreshActionTabVisibility();
    }

    /** 按当前 Tab/容器状态立即设置动作控件 visible（切 Tab 与 tick 共用）。 */
    /** 按当前 Tab/容器状态立即刷新动作控件（active/visible/Tab 高亮/开关/实体），
     *  init、switchTab、tick 共用，杜绝「等下一帧 tick 才隐藏」的闪烁。 */
    private void refreshActionTabVisibility() {
        boolean ready = this.requireBotSilent();
        boolean containerOpen = this.selected != null && this.selected.getContainer().isPresent();
        boolean actionsTab = this.tab == 2;
        this.turnLeft.active = ready && actionsTab;
        this.turnLeft.visible = actionsTab;
        this.turnRight.active = ready && actionsTab;
        this.turnRight.visible = actionsTab;
        this.turnUp.active = ready && actionsTab;
        this.turnUp.visible = actionsTab;
        this.turnDown.active = ready && actionsTab;
        this.turnDown.visible = actionsTab;
        this.moveForward.active = ready && actionsTab;
        this.moveForward.visible = actionsTab;
        this.moveBackward.active = ready && actionsTab;
        this.moveBackward.visible = actionsTab;
        this.moveLeft.active = ready && actionsTab;
        this.moveLeft.visible = actionsTab;
        this.moveRight.active = ready && actionsTab;
        this.moveRight.visible = actionsTab;
        this.stopButton.active = ready && actionsTab;
        this.stopButton.visible = actionsTab;
        this.sneakButton.active = ready && actionsTab;
        this.sneakButton.visible = actionsTab;
        this.sprintButton.active = ready && actionsTab;
        this.sprintButton.visible = actionsTab;
        this.jumpButton.active = ready && actionsTab;
        this.jumpButton.visible = actionsTab;
        this.attackLookButton.active = ready && actionsTab;
        this.attackLookButton.visible = actionsTab;
        this.useLookButton.active = ready && actionsTab;
        this.useLookButton.visible = actionsTab;
        this.chunkMinusButton.active = ready && actionsTab;
        this.chunkMinusButton.visible = actionsTab;
        this.chunkPlusButton.active = ready && actionsTab;
        this.chunkPlusButton.visible = actionsTab;
        this.respawnButton.active = ready && actionsTab;
        this.respawnButton.visible = actionsTab;
        this.autoRespawnButton.active = ready && actionsTab;
        this.autoRespawnButton.visible = actionsTab;
        this.closeContainerButton.active = ready && containerOpen && this.tab == 1;
        this.closeContainerButton.visible = containerOpen && this.tab == 1;
        this.sendButton.active = ready && actionsTab;
        this.sendButton.visible = actionsTab;
        this.chatBox.active = ready && actionsTab;
        this.chatBox.visible = actionsTab;
        this.newButton.active = true;
        this.delButton.active = true;
        // Tab 高亮：当前 Tab 全亮，其余半透明（切 Tab 立即生效）
        float opacity = MockplayerConfig.get().getGuiOpacity();
        this.tabStatus.setAlpha(this.tab == 0 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        this.tabInventory.setAlpha(this.tab == 1 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        this.tabActions.setAlpha(this.tab == 2 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        this.refreshToggleLabels();
        this.refreshEntityButtons();
    }

    /** 开关按钮文字颜色刷新（开启绿 / 关闭红；tick / 切 Tab 共用）。 */
    private void refreshToggleLabels() {
        if (!this.requireBotSilent()) {
            return;
        }
        this.sneakButton.setMessage(Component.translatable("gui.mockplayer.action.sneak")
                .withColor(this.selected.actions().isSneaking() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
        this.sprintButton.setMessage(Component.translatable("gui.mockplayer.action.sprint")
                .withColor(this.selected.actions().isSprinting() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
        this.jumpButton.setMessage(Component.translatable("gui.mockplayer.action.jump")
                .withColor(this.selected.actions().isJumping() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
        this.autoRespawnButton.setMessage(Component.translatable("gui.mockplayer.action.auto_respawn")
                .withColor(this.selected.isAutoRespawn() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
    }

    /** 附近实体按钮刷新（tick / 切 Tab 共用：实体列表 + 截断文字 + 可见性）。 */
    private void refreshEntityButtons() {
        boolean ready = this.requireBotSilent();
        boolean actionsTab = this.tab == 2;
        this.entityTargets.clear();
        List<Entity> near = ready ? this.selected.getEntitiesNear(12.0).stream()
                .filter(e -> !(e instanceof net.minecraft.world.entity.player.Player))
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(this.selected.getLocalPlayer())))
                .limit(2).toList() : List.of();
        for (int i = 0; i < this.entityButtons.size(); i++) {
            Button b = this.entityButtons.get(i);
            if (i < near.size()) {
                Entity e = near.get(i);
                this.entityTargets.add(e);
                b.visible = actionsTab;
                b.active = ready;
                String label = e.getName().getString() + "·"
                        + String.format(Locale.ROOT, "%.0f",
                        Math.sqrt(e.distanceToSqr(this.selected.getLocalPlayer()))) + "m";
                // 按按钮像素宽度截断：附近实体多/名字长时文字不溢出到旁边按钮
                b.setMessage(Component.literal(
                        this.font.plainSubstrByWidth(label, Math.max(4, this.sw(ENTITY_W) - 4))));
            } else {
                b.visible = false;
            }
        }
    }

    private void setFeedback(Component message) {
        this.feedback = message;
        this.feedbackError = false;
    }

    /** 错误反馈（红色；失败原因显示在面板内，不弹异常）。 */
    private void setError(Component message) {
        this.feedback = message;
        this.feedbackError = true;
    }

    /** 最近一次反馈（测试/查询用，i18n 组件）。 */
    public Component lastFeedback() {
        return this.feedback;
    }

    // ===== 动作执行（带就绪校验 + 反馈） =====

    private boolean requireBot() {
        if (this.selected == null) {
            this.setError(Component.translatable("gui.mockplayer.feedback.no_bot"));
            return false;
        }
        if (this.selected.getLifecycle() != BotLifecycle.PLAYING) {
            this.setError(Component.translatable("gui.mockplayer.feedback.not_playing"));
            return false;
        }
        return true;
    }

    /** 无参数动作：执行 + 反馈动作名。 */
    private void act(java.util.function.Consumer<Bot> action, String actionKey) {
        if (!this.requireBot()) {
            return;
        }
        try {
            action.accept(this.selected);
            this.setFeedback(Component.translatable("gui.mockplayer.feedback.executed",
                    Component.translatable(actionKey)));
        } catch (Exception e) {
            this.setError(Component.translatable("gui.mockplayer.feedback.error", e.getMessage()));
        }
    }

    /** 需要 BotActions 的带参动作（攻击/使用/重生等）。 */
    private void act(java.util.function.Function<Bot, com.mockplayer.api.action.BotActions> getter,
                     String actionKey,
                     java.util.function.Consumer<com.mockplayer.api.action.BotActions> action) {
        if (!this.requireBot()) {
            return;
        }
        try {
            action.accept(getter.apply(this.selected));
            this.setFeedback(Component.translatable("gui.mockplayer.feedback.executed",
                    Component.translatable(actionKey)));
        } catch (Exception e) {
            this.setError(Component.translatable("gui.mockplayer.feedback.error", e.getMessage()));
        }
    }

    /** 静默动作（按住/松开，不刷反馈）。 */
    private void actQuiet(java.util.function.Consumer<Bot> action) {
        if (!this.requireBot()) {
            return;
        }
        try {
            action.accept(this.selected);
        } catch (Exception ignored) {
            // 按住期间假人掉线等：静默忽略，下一次 tick 的 active 状态会禁用按钮
        }
    }

    private void toggleSneak() {
        if (!this.requireBot()) {
            return;
        }
        boolean next = !this.selected.actions().isSneaking();
        this.selected.actions().setSneak(next);
    }

    private void toggleSprint() {
        if (!this.requireBot()) {
            return;
        }
        boolean next = !this.selected.actions().isSprinting();
        this.selected.actions().setSprint(next);
    }

    private void toggleJump() {
        if (!this.requireBot()) {
            return;
        }
        boolean next = !this.selected.actions().isJumping();
        if (next) {
            this.selected.actions().jump();
        } else {
            this.selected.actions().stop();
        }
    }

    private void toggleAutoRespawn() {
        if (!this.requireBot()) {
            return;
        }
        boolean next = !this.selected.isAutoRespawn();
        this.selected.setAutoRespawn(next);
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.auto_respawn",
                Component.translatable(next ? "gui.mockplayer.value.on" : "gui.mockplayer.value.off")));
    }

    private void changeChunk(int delta) {
        if (!this.requireBot()) {
            return;
        }
        int next = Math.max(1, Math.min(32, this.selected.getChunkRadius() + delta));
        this.selected.setChunkRadius(next);
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.chunk_radius", next));
    }

    private void sendChat() {
        if (!this.requireBot()) {
            return;
        }
        String message = this.chatBox.getValue();
        if (message.isBlank()) {
            this.setError(Component.translatable("gui.mockplayer.feedback.invalid_message"));
            return;
        }
        this.selected.actions().chat(message);
        this.chatBox.setValue("");
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.sent"));
    }

    private void tryCreate() {
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty() || name.length() > 16) {
            this.setError(Component.translatable("gui.mockplayer.feedback.invalid_name"));
            return;
        }
        Component result = FakePlayerCommands.newPlayer(name);
        this.setFeedback(result);
        this.nameBox.setValue("");
    }

    private void tryDelete() {
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            this.setError(Component.translatable("gui.mockplayer.feedback.invalid_name"));
            return;
        }
        Component result = FakePlayerCommands.delPlayer(name);
        this.setFeedback(result);
        this.nameBox.setValue("");
        if (this.selected != null && this.selected.getName().equals(name)) {
            this.selected = null;
        }
    }

    // ===== tick：状态刷新 =====

    @Override
    public void tick() {
        BotGui.recordTick();
        this.tickCount++;
        long now = Util.getMillis();
        for (RepeatHoldButton b : this.repeatButtons) {
            b.onTick(now);
        }
        // 选中假人掉线/删除 → 自动切到第一个可用
        if (this.selected != null && (this.selected.getLifecycle() != BotLifecycle.PLAYING
                || !coreBots().contains(this.selected))) {
            this.selected = firstSelectableBot();
        }
        List<Bot> bots = coreBots();
        this.botScrollOffset = clampBotScroll(this.botScrollOffset, bots.size(), VISIBLE_BOT_SLOTS);
        for (int i = 0; i < this.botButtons.size(); i++) {
            Button b = this.botButtons.get(i);
            int index = i + this.botScrollOffset;
            if (index < bots.size()) {
                Bot bot = bots.get(index);
                float hp = bot.getLocalPlayer() != null ? bot.getLocalPlayer().getHealth() : 0.0F;
                b.visible = true;
                b.active = true;
                b.setMessage(Component.literal(bot.getName() + " ❤" + Math.round(hp)));
                // 当前选中假人高亮（复用按钮 highlighted 贴图）
                b.setOverrideRenderHighlightedSprite(() -> this.selected == bot);
            } else {
                b.visible = false;
            }
        }
        boolean ready = this.requireBotSilent();
        boolean containerOpen = this.selected != null && this.selected.getContainer().isPresent();
        boolean actionsTab = this.tab == 2;
        // 动作 Tab 控件：切到其他 Tab 时必须隐藏（visible=false），防止与内容重叠
        this.turnLeft.active = ready && actionsTab;
        this.turnLeft.visible = actionsTab;
        this.turnRight.active = ready && actionsTab;
        this.turnRight.visible = actionsTab;
        this.turnUp.active = ready && actionsTab;
        this.turnUp.visible = actionsTab;
        this.turnDown.active = ready && actionsTab;
        this.turnDown.visible = actionsTab;
        this.moveForward.active = ready && actionsTab;
        this.moveForward.visible = actionsTab;
        this.moveBackward.active = ready && actionsTab;
        this.moveBackward.visible = actionsTab;
        this.moveLeft.active = ready && actionsTab;
        this.moveLeft.visible = actionsTab;
        this.moveRight.active = ready && actionsTab;
        this.moveRight.visible = actionsTab;
        this.stopButton.active = ready && actionsTab;
        this.stopButton.visible = actionsTab;
        this.sneakButton.active = ready && actionsTab;
        this.sneakButton.visible = actionsTab;
        this.sprintButton.active = ready && actionsTab;
        this.sprintButton.visible = actionsTab;
        this.jumpButton.active = ready && actionsTab;
        this.jumpButton.visible = actionsTab;
        this.attackLookButton.active = ready && actionsTab;
        this.attackLookButton.visible = actionsTab;
        this.useLookButton.active = ready && actionsTab;
        this.useLookButton.visible = actionsTab;
        this.chunkMinusButton.active = ready && actionsTab;
        this.chunkMinusButton.visible = actionsTab;
        this.chunkPlusButton.active = ready && actionsTab;
        this.chunkPlusButton.visible = actionsTab;
        this.respawnButton.active = ready && actionsTab;
        this.respawnButton.visible = actionsTab;
        this.autoRespawnButton.active = ready && actionsTab;
        this.autoRespawnButton.visible = actionsTab;
        this.closeContainerButton.active = ready && containerOpen && this.tab == 1;
        this.closeContainerButton.visible = containerOpen && this.tab == 1;
        this.sendButton.active = ready && actionsTab;
        this.sendButton.visible = actionsTab;
        this.chatBox.active = ready && actionsTab;
        this.chatBox.visible = actionsTab;
        this.newButton.active = true;
        this.delButton.active = true;
        // Tab 高亮：当前 Tab 全亮，其余半透明（视觉上明显区分选中状态）
        float opacity = MockplayerConfig.get().getGuiOpacity();
        this.tabStatus.setAlpha(this.tab == 0 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        this.tabInventory.setAlpha(this.tab == 1 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        this.tabActions.setAlpha(this.tab == 2 ? 1.0F : BotControlHud.buttonAlpha(opacity));
        // 开关回显（on/off 状态写进按钮文字）
        if (ready) {
            this.sneakButton.setMessage(Component.translatable("gui.mockplayer.action.sneak")
                    .withColor(this.selected.actions().isSneaking() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
            this.sprintButton.setMessage(Component.translatable("gui.mockplayer.action.sprint")
                    .withColor(this.selected.actions().isSprinting() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
            this.jumpButton.setMessage(Component.translatable("gui.mockplayer.action.jump")
                    .withColor(this.selected.actions().isJumping() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
            this.autoRespawnButton.setMessage(Component.translatable("gui.mockplayer.action.auto_respawn")
                    .withColor(this.selected.isAutoRespawn() ? TOGGLE_ON_COLOR : TOGGLE_OFF_COLOR));
        }
        // 附近实体按钮（动作 Tab）
        this.entityTargets.clear();
        List<Entity> near = ready ? this.selected.getEntitiesNear(12.0).stream()
                .filter(e -> !(e instanceof net.minecraft.world.entity.player.Player))
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(this.selected.getLocalPlayer())))
                .limit(2).toList() : List.of();
        for (int i = 0; i < this.entityButtons.size(); i++) {
            Button b = this.entityButtons.get(i);
            if (i < near.size()) {
                Entity e = near.get(i);
                this.entityTargets.add(e);
                b.visible = actionsTab;
                b.active = ready;
                String label = e.getName().getString() + "·"
                        + String.format(Locale.ROOT, "%.0f",
                        Math.sqrt(e.distanceToSqr(this.selected.getLocalPlayer()))) + "m";
                // 按按钮像素宽度截断：附近实体多/名字长时文字不溢出到旁边按钮
                b.setMessage(Component.literal(
                        this.font.plainSubstrByWidth(label, Math.max(4, this.sw(44) - 4))));
            } else {
                b.visible = false;
            }
        }
        // Tab 文字（当前 Tab 用 highlighted 贴图高亮，不再用文字前缀）
        this.tabStatus.setMessage(Component.translatable("gui.mockplayer.tab.status"));
        this.tabInventory.setMessage(Component.translatable("gui.mockplayer.tab.inventory"));
        this.tabActions.setMessage(Component.translatable("gui.mockplayer.tab.actions"));
        this.tabStatus.setOverrideRenderHighlightedSprite(() -> this.tab == 0);
        this.tabInventory.setOverrideRenderHighlightedSprite(() -> this.tab == 1);
        this.tabActions.setOverrideRenderHighlightedSprite(() -> this.tab == 2);
    }

    /** 静默就绪检查（tick 里不刷反馈）。 */
    private boolean requireBotSilent() {
        return this.selected != null && this.selected.getLifecycle() == BotLifecycle.PLAYING;
    }

    /** 当前 Tab（测试/查询用）：0 状态 / 1 背包（含容器模式）/ 2 动作。 */
    public int currentTab() {
        return this.tab;
    }

    // ===== 输入（屏幕坐标 → 面板逻辑坐标） =====

    private float scale() {
        return BotGui.layoutScale(this.width, this.height);
    }

    private int panelX() {
        return BotGui.panelX(this.width, this.height);
    }

    private int panelY() {
        return BotGui.panelY(this.width, this.height);
    }

    /** 面板逻辑 X → 屏幕 X（多分辨率缩放：panelX + 逻辑坐标 * scale）。 */
    int sx(int localX) {
        return this.panelX() + Math.round(localX * this.scale());
    }

    /** 面板逻辑 Y → 屏幕 Y。 */
    int sy(int localY) {
        return this.panelY() + Math.round(localY * this.scale());
    }

    /** 逻辑尺寸 → 屏幕尺寸（缩放，最小 1px 防消失）。 */
    int sw(int localSize) {
        return Math.max(1, Math.round(localSize * this.scale()));
    }

    /** 逻辑高度 → 屏幕高度（与 sw 同缩放）。 */
    int sh(int localSize) {
        return this.sw(localSize);
    }

    /**
     * 包级字体访问器：拆出的面板组件（BotStatusPanel 等）需要读 Screen.protected font，
     * 由宿主 Screen 提供只读入口，避免组件继承 Screen。
     */
    Font font() {
        return this.font;
    }

    double localX(double screenX) {
        return (screenX - this.panelX()) / this.scale();
    }

    double localY(double screenY) {
        return (screenY - this.panelY()) / this.scale();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double lx = this.localX(event.x());
        double ly = this.localY(event.y());
        // 左栏滑条：按下即定位并进入拖动
        if (this.scrollbarHit(lx, ly)) {
            this.scrollbarDragging = true;
            this.scrollBotTo(lx, ly);
            return true;
        }
        // 控件已按屏幕坐标直排：原样交给 super 命中；网格（非控件）再按逻辑坐标换算
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return this.handleContentClick(lx, ly, event.buttonInfo());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.scrollbarDragging) {
            this.scrollBotTo(this.localX(event.x()), this.localY(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.scrollbarDragging = false;
        return super.mouseReleased(event);
    }

    // ===== 背包/容器网格点击 =====

    private boolean handleContentClick(double mx, double my, MouseButtonInfo info) {
        if (!this.requireBotSilent()) {
            return false;
        }
        if (this.tab == 1) {
            Optional<BotContainer> container = this.selected.getContainer();
            if (container.isPresent()) {
                int slot = BotContainerPanel.containerSlotAt(this, mx, my,
                        BotContainerPanel.containerSlotCount(this, container.get()));
                if (slot >= 0) {
                    this.containerClick(container.get(), slot, info);
                    return true;
                }
            } else {
                net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
                int slot = BotInventoryPanel.inventorySlotAt(mx, my);
                if (slot == net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                    this.discardClick(info);
                    return true;
                }
                if (slot >= 0) {
                    if (slot >= 36 && slot < 45) {
                        // 右键空手点快捷栏 = 切换选中槽；左键/携带时保持原版物品交互
                        // （拿起/放下/交换），物品选择完全不受干扰
                        if (player != null && info.button() == 1
                                && player.containerMenu.getCarried().isEmpty()) {
                            this.selectHotbarSlot(slot - 36);
                        } else if (player != null) {
                            this.inventoryClick(slot, info);
                        }
                    } else {
                        this.inventoryClick(slot, info);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 红色丢弃格子：原版点击菜单外（-999）——携带物品左键整组/右键 1 个丢弃；空手无害。 */
    private void discardClick(MouseButtonInfo info) {
        net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = this.selected.getGameMode();
        if (player == null || gameMode == null) {
            return;
        }
        net.minecraft.world.item.ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            this.setFeedback(Component.translatable("gui.mockplayer.feedback.discard_empty"));
            return;
        }
        gameMode.handleContainerInput(player.containerMenu.containerId,
                net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
                info.button(), ContainerInput.PICKUP, player);
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.discard", carried.getHoverName()));
    }

    private void inventoryClick(int slot, MouseButtonInfo info) {
        net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = this.selected.getGameMode();
        if (player == null || gameMode == null) {
            return;
        }
        ContainerInput input = info.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        int button = info.hasShiftDown() ? 0 : info.button();
        gameMode.handleContainerInput(player.containerMenu.containerId, slot, button, input, player);
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.inventory_click",
                Component.translatable(input == ContainerInput.QUICK_MOVE
                        ? "gui.mockplayer.value.shift" : "gui.mockplayer.value.pickup")));
    }

    /** 点击背包快捷栏格子 → 假人选中该槽位（服务端同步，原版按 1-9 等价）。 */
    private void selectHotbarSlot(int hotbar) {
        if (!this.requireBot()) {
            return;
        }
        try {
            this.selected.actions().setSelectedSlot(hotbar);
            this.setFeedback(Component.translatable("gui.mockplayer.feedback.hotbar_selected", hotbar + 1));
        } catch (Exception e) {
            this.setError(Component.translatable("gui.mockplayer.feedback.error", e.getMessage()));
        }
    }

    private void containerClick(BotContainer container, int slot, MouseButtonInfo info) {
        ContainerInput input = info.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        int button = info.hasShiftDown() ? 0 : info.button();
        try {
            container.click(slot, button, input);
            this.setFeedback(Component.translatable("gui.mockplayer.feedback.container_click",
                    Component.translatable(input == ContainerInput.QUICK_MOVE
                            ? "gui.mockplayer.value.shift" : "gui.mockplayer.value.pickup")));
        } catch (Exception e) {
            this.setError(Component.translatable("gui.mockplayer.feedback.error", e.getMessage()));
        }
    }

    // ===== 渲染 =====

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        BotGui.recordFrame(this.getTitle());
        float opacity = MockplayerConfig.get().getGuiOpacity();
        int px = this.panelX();
        int py = this.panelY();
        int pw = BotGui.panelWidth(this.width, this.height);
        int ph = BotGui.panelHeight(this.width, this.height);
        // 面板背景：半透明渐变 + 双层边框 + 顶栏 + 左栏分隔线（alpha < 0xFF 透出游戏场景）
        graphics.fillGradient(px, py, px + pw, py + ph,
                BotControlHud.withAlpha(PANEL_BG_TOP, opacity), BotControlHud.withAlpha(PANEL_BG_BOTTOM, opacity));
        graphics.outline(px, py, pw, ph, BotControlHud.withAlpha(PANEL_BORDER, opacity));
        graphics.outline(px + 1, py + 1, pw - 2, ph - 2, BotControlHud.withAlpha(PANEL_BORDER_INNER, opacity));
        int headerH = this.sh(18);
        graphics.fill(px, py, px + pw, py + headerH, BotControlHud.withAlpha(PANEL_HEADER_BG, opacity));
        graphics.fill(px, py + headerH - 1, px + pw, py + headerH, BotControlHud.withAlpha(PANEL_ACCENT, opacity));
        int dividerX = this.sx(LIST_X + LIST_W + 6);
        graphics.fill(dividerX, py + headerH, dividerX + 1, py + ph, BotControlHud.withAlpha(PANEL_DIVIDER, opacity));
        BotListPanel.render(this, graphics);
        // 控件全部按屏幕坐标直排（init 时 sx/sy/sw 换算好），命中与渲染同一坐标系
        super.extractRenderState(graphics, mouseX, mouseY, a);
        this.drawContent(graphics, mouseX, mouseY);
        if (this.tab == 1 && this.selected != null) {
            this.drawCarried(graphics, mouseX, mouseY);
        }
    }

    /** 鼠标携带物品（拿起后跟随鼠标绘制，原版背包同款；数量用原版 itemDecorations）。 */
    private void drawCarried(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
        if (player == null) {
            return;
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        graphics.item(carried, mouseX - 8, mouseY - 8);
        graphics.itemDecorations(this.font, carried, mouseX - 8, mouseY - 8);
        com.mockplayer.gui.BotGui.recordCarried();
    }

    /** 面板内手动绘制内容（状态文本/网格/反馈，全部换算为屏幕坐标）。 */
    private void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 顶栏标题（居中）
        Component title = Component.literal(this.font.plainSubstrByWidth(
                this.getTitle().getString(), this.sw(BotGui.PANEL_W - 96)));
        graphics.centeredText(this.font, title,
                this.panelX() + BotGui.panelWidth(this.width, this.height) / 2,
                this.sy(5), 0xFFFFFF);
        // 反馈行（居中，限宽避免与右侧选中文字重叠）
        int feedbackColor = this.feedbackError ? 0xFFFF5555
                : this.feedback.getString().isEmpty() ? 0xFFAAAAAA : 0xFF55FF55;
        graphics.centeredText(this.font,
                Component.literal(this.font.plainSubstrByWidth(
                        this.feedback.getString(), this.sw(BotGui.PANEL_W - 16 - 150))),
                this.sx(BotGui.PANEL_W / 2), this.sy(FEEDBACK_Y), feedbackColor);
        // 底部常驻：当前选中假人（右对齐 + 最小左边界，左右都有边距，不贴边）
        String selectedText = BotControlHud.selectedTextDisplay(this.selected, this.font, 150);
        int selectedLeft = Math.max(BotGui.PANEL_W / 2 + 8,
                BotGui.PANEL_W - 8 - this.font.width(selectedText));
        graphics.text(this.font, selectedText,
                this.sx(selectedLeft),
                this.sy(FEEDBACK_Y), 0xFFB0C4DE);
        // 左栏标题
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.bots"),
                this.sx(LIST_X), this.sy(LIST_TITLE_Y), 0xFFA8C8FF);
        if (this.selected == null) {
            graphics.text(this.font, Component.translatable("gui.mockplayer.status.no_bot"),
                    this.sx(CONTENT_X), this.sy(CONTENT_Y), 0xAAAAAA);
            return;
        }
        switch (this.tab) {
            case 0 -> BotStatusPanel.render(this, graphics);
            case 1 -> {
                // 背包 Tab 合并容器：开容器自动切容器布局，关容器回 46 槽背包
                if (this.selected.getContainer().isPresent()) {
                    BotContainerPanel.render(this, graphics, mouseX, mouseY);
                } else {
                    BotInventoryPanel.render(this, graphics, mouseX, mouseY);
                }
            }
            default -> BotActionsPanel.render(this, graphics);
        }
    }

}

package com.mockplayer.gui;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotSource;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.session.FakePlayerCommands;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;

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

    private static final int LIST_X = 8;
    private static final int LIST_W = 88;
    private static final int CONTENT_X = 104;
    private static final int CONTENT_W = BotGui.PANEL_W - CONTENT_X - 8;
    private static final int TAB_Y = 24;
    private static final int CONTENT_Y = 44;
    private static final int FEEDBACK_Y = BotGui.PANEL_H - 14;
    private static final int CELL = 20;
    private static final int SLOT = 18;

    /** 当前选中假人（null = 未选中）。 */
    private Bot selected;
    /** 当前 Tab：0 状态 / 1 背包 / 2 容器 / 3 动作。 */
    private int tab;
    /** 底部反馈行（i18n 组件，动作结果/错误）。 */
    private Component feedback = Component.literal("");
    /** 反馈是否错误（错误红 / 成功绿 / 空灰）。 */
    private boolean feedbackError;

    // ===== 控件（init 重建，tick 更新状态） =====
    private Button closeButton;
    private final List<Button> botButtons = new ArrayList<>();
    private EditBox newName;
    private EditBox delName;
    private Button newButton;
    private Button delButton;
    private Button tabStatus;
    private Button tabInventory;
    private Button tabActions;
    private Button turnLeft;
    private Button turnRight;
    private Button turnUp;
    private Button turnDown;
    private HoldButton moveForward;
    private HoldButton moveBackward;
    private HoldButton moveLeft;
    private HoldButton moveRight;
    private Button stopButton;
    private Button sneakButton;
    private Button sprintButton;
    private Button jumpButton;
    private final List<Button> hotbarButtons = new ArrayList<>();
    private Button attackLookButton;
    private Button useLookButton;
    private HoldButton holdAttackButton;
    private HoldButton holdUseButton;
    private Button chunkMinusButton;
    private Button chunkPlusButton;
    private Button respawnButton;
    private Button autoRespawnButton;
    private Button closeContainerButton;
    private EditBox chatBox;
    private Button sendButton;
    private final List<Button> entityButtons = new ArrayList<>();
    /** 实体按钮当前绑定的实体（防重建时取到已死实体）。 */
    private final List<Entity> entityTargets = new ArrayList<>();

    public BotControlScreen() {
        super(Component.translatable("gui.mockplayer.title"));
    }

    @Override
    public boolean isPauseScreen() {
        // 控制台不是暂停界面：单机不开 LAN 时打开 GUI 也不能把世界停住（假人还要继续动）
        return false;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.botButtons.clear();
        this.hotbarButtons.clear();
        this.entityButtons.clear();
        this.entityTargets.clear();
        this.selected = firstSelectableBot();
        if (this.tab > 2) {
            this.tab = 0;
        }
        this.feedback = Component.literal("");

        // ===== 顶栏：标题 + 关闭 =====
        this.closeButton = this.addButton(BotGui.PANEL_W - 48, 4, 40, 14,
                "gui.mockplayer.close", () -> this.onClose());

        // ===== 左栏：假人列表（8 个固定槽位，tick 更新） =====
        for (int i = 0; i < 8; i++) {
            int index = i;
            Button b = Button.builder(Component.literal(""), btn -> {
                List<Bot> bots = coreBots();
                if (index < bots.size()) {
                    this.select(bots.get(index));
                }
            }).bounds(sx(LIST_X), sy(34 + i * 16), sw(LIST_W), sh(15)).build();
            this.addRenderableWidget(b);
            this.botButtons.add(b);
        }
        this.newName = new EditBox(this.font, sx(LIST_X), sy(164), sw(LIST_W), sh(12),
                Component.translatable("gui.mockplayer.new_hint"));
        this.newName.setMaxLength(16);
        this.addRenderableWidget(this.newName);
        this.newButton = this.addButton(LIST_X, 178, LIST_W, 12, "gui.mockplayer.new_bot",
                () -> this.tryCreate());
        this.delName = new EditBox(this.font, sx(LIST_X), sy(192), sw(LIST_W), sh(12),
                Component.translatable("gui.mockplayer.delete_hint"));
        this.delName.setMaxLength(16);
        this.addRenderableWidget(this.delName);
        this.delButton = this.addButton(LIST_X, 206, LIST_W, 12, "gui.mockplayer.delete_bot",
                () -> this.tryDelete());

        // ===== 顶部 Tab =====
        int tabW = (CONTENT_W - 2 * 2) / 3;
        this.tabStatus = this.addButton(CONTENT_X, TAB_Y, tabW, 14,
                "gui.mockplayer.tab.status", () -> this.switchTab(0));
        this.tabInventory = this.addButton(CONTENT_X + (tabW + 2), TAB_Y, tabW, 14,
                "gui.mockplayer.tab.inventory", () -> this.switchTab(1));
        this.tabActions = this.addButton(CONTENT_X + (tabW + 2) * 2, TAB_Y, tabW, 14,
                "gui.mockplayer.tab.actions", () -> this.switchTab(2));

        // ===== 动作 Tab 控件 =====
        this.turnLeft = this.addButton(CONTENT_X, 54, 36, 16, "gui.mockplayer.action.turn_left",
                () -> this.act(b -> b.actions().turn(-15.0F, 0.0F), "gui.mockplayer.action.turn_left"));
        this.turnRight = this.addButton(CONTENT_X + 38, 54, 36, 16, "gui.mockplayer.action.turn_right",
                () -> this.act(b -> b.actions().turn(15.0F, 0.0F), "gui.mockplayer.action.turn_right"));
        this.turnUp = this.addButton(CONTENT_X + 76, 54, 36, 16, "gui.mockplayer.action.turn_up",
                () -> this.act(b -> b.actions().turn(0.0F, -8.0F), "gui.mockplayer.action.turn_up"));
        this.turnDown = this.addButton(CONTENT_X + 114, 54, 36, 16, "gui.mockplayer.action.turn_down",
                () -> this.act(b -> b.actions().turn(0.0F, 8.0F), "gui.mockplayer.action.turn_down"));

        this.moveForward = this.addHold(CONTENT_X, 80, 36, 16, "gui.mockplayer.action.move_forward",
                () -> this.act(b -> b.actions().setForward(1.0F), "gui.mockplayer.action.move_forward"),
                () -> this.actQuiet(b -> b.actions().setForward(0.0F)));
        this.moveBackward = this.addHold(CONTENT_X + 38, 80, 36, 16, "gui.mockplayer.action.move_backward",
                () -> this.act(b -> b.actions().setForward(-1.0F), "gui.mockplayer.action.move_backward"),
                () -> this.actQuiet(b -> b.actions().setForward(0.0F)));
        this.moveLeft = this.addHold(CONTENT_X + 76, 80, 36, 16, "gui.mockplayer.action.move_left",
                () -> this.act(b -> b.actions().setStrafe(-1.0F), "gui.mockplayer.action.move_left"),
                () -> this.actQuiet(b -> b.actions().setStrafe(0.0F)));
        this.moveRight = this.addHold(CONTENT_X + 114, 80, 36, 16, "gui.mockplayer.action.move_right",
                () -> this.act(b -> b.actions().setStrafe(1.0F), "gui.mockplayer.action.move_right"),
                () -> this.actQuiet(b -> b.actions().setStrafe(0.0F)));
        this.stopButton = this.addButton(CONTENT_X + 152, 80, 42, 16, "gui.mockplayer.action.stop",
                () -> this.act(b -> b.actions().stop(), "gui.mockplayer.action.stop"));
        this.sneakButton = this.addButton(CONTENT_X + 196, 80, 52, 16, "gui.mockplayer.action.sneak",
                () -> this.toggleSneak());
        this.sprintButton = this.addButton(CONTENT_X, 96, 52, 16, "gui.mockplayer.action.sprint",
                () -> this.toggleSprint());
        this.jumpButton = this.addButton(CONTENT_X + 54, 96, 52, 16, "gui.mockplayer.action.jump",
                () -> this.toggleJump());

        for (int i = 0; i < 9; i++) {
            int slot = i;
            Button b = Button.builder(Component.literal(String.valueOf(slot + 1)), btn -> this.act(
                            bot -> bot.actions().setSelectedSlot(slot),
                            "gui.mockplayer.action.hotbar"))
                    .bounds(sx(CONTENT_X + i * 22), sy(122), sw(20), sh(14)).build();
            this.addRenderableWidget(b);
            this.hotbarButtons.add(b);
        }
        this.attackLookButton = this.addButton(CONTENT_X, 148, 64, 16, "gui.mockplayer.action.attack_look",
                () -> this.act(Bot::actions, "gui.mockplayer.action.attack_look",
                        actions -> actions.attackLook()));
        this.useLookButton = this.addButton(CONTENT_X + 66, 148, 64, 16, "gui.mockplayer.action.use_look",
                () -> this.act(Bot::actions, "gui.mockplayer.action.use_look",
                        actions -> actions.useLook()));
        this.holdAttackButton = this.addHold(CONTENT_X + 132, 148, 74, 16,
                "gui.mockplayer.action.hold_attack",
                () -> this.actQuiet(b -> b.actions().sustainedAttackLook()),
                () -> this.actQuiet(b -> b.actions().stopSustained()));
        this.holdUseButton = this.addHold(CONTENT_X + 208, 148, 40, 16,
                "gui.mockplayer.action.hold_use",
                () -> this.actQuiet(b -> b.actions().sustainedUseLook()),
                () -> this.actQuiet(b -> b.actions().stopSustained()));

        this.chunkMinusButton = this.addButton(CONTENT_X, 176, 30, 14,
                "gui.mockplayer.action.chunk_minus", () -> this.changeChunk(-1));
        this.chunkPlusButton = this.addButton(CONTENT_X + 32, 176, 30, 14,
                "gui.mockplayer.action.chunk_plus", () -> this.changeChunk(1));
        this.respawnButton = this.addButton(CONTENT_X + 66, 176, 44, 14,
                "gui.mockplayer.action.respawn",
                () -> this.act(Bot::actions, "gui.mockplayer.action.respawn", actions -> actions.respawn()));
        this.autoRespawnButton = this.addButton(CONTENT_X + 112, 176, 66, 14,
                "gui.mockplayer.action.auto_respawn", () -> this.toggleAutoRespawn());
        this.closeContainerButton = this.addButton(CONTENT_X + 180, 176, 68, 14,
                "gui.mockplayer.action.close_container",
                () -> this.actQuiet(b -> b.getContainer().ifPresent(BotContainer::close)));

        this.chatBox = new EditBox(this.font, sx(CONTENT_X), sy(192), sw(180), sh(14),
                Component.translatable("gui.mockplayer.action.chat_hint"));
        this.chatBox.setMaxLength(256);
        this.addRenderableWidget(this.chatBox);
        this.sendButton = this.addButton(CONTENT_X + 182, 192, 66, 14, "gui.mockplayer.action.send",
                () -> this.sendChat());

        // ===== 附近实体（动作 Tab 顶部，点击 = bot 转头） =====
        for (int i = 0; i < 4; i++) {
            int index = i;
            Button b = Button.builder(Component.literal(""), btn -> {
                if (index < this.entityTargets.size()) {
                    Entity target = this.entityTargets.get(index);
                    this.act(bot -> bot.actions().lookAt(target), "gui.mockplayer.action.look_at");
                }
            }).bounds(sx(CONTENT_X + 156 + (index % 2) * 46), sy(54 + (index / 2) * 17),
                    sw(44), sh(16)).build();
            this.addRenderableWidget(b);
            this.entityButtons.add(b);
        }
    }

    // ===== 控件工厂 =====

    private Button addButton(int x, int y, int w, int h, String key, Runnable action) {
        Button b = Button.builder(Component.translatable(key), btn -> action.run())
                .bounds(sx(x), sy(y), sw(w), sw(h)).build();
        this.addRenderableWidget(b);
        return b;
    }

    /** 按住持续按钮：按下执行 start，松开执行 end（移动/长按交互）。 */
    private HoldButton addHold(int x, int y, int w, int h, String key,
                               Runnable start, Runnable end) {
        HoldButton b = new HoldButton(sx(x), sy(y), sw(w), sw(h),
                Component.translatable(key), start, end);
        this.addRenderableWidget(b);
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

    // ===== 数据/选择 =====

    /** 只列 CORE 假人（管理边界：GUI 与本 mod 命令一致，不管理 API/附属创建）。 */
    private static List<Bot> coreBots() {
        return MockplayerApi.bots().getBots().stream()
                .filter(b -> b.source() == BotSource.CORE)
                .toList();
    }

    private static Bot firstSelectableBot() {
        return coreBots().stream().findFirst().orElse(null);
    }

    private void select(Bot bot) {
        this.selected = bot;
        this.setFeedback(Component.translatable("gui.mockplayer.feedback.selected", bot.getName()));
    }

    private void switchTab(int tab) {
        this.tab = tab;
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
        String name = this.newName.getValue().trim();
        if (name.isEmpty() || name.length() > 16) {
            this.setError(Component.translatable("gui.mockplayer.feedback.invalid_name"));
            return;
        }
        Component result = FakePlayerCommands.newPlayer(name);
        this.setFeedback(result);
        this.newName.setValue("");
    }

    private void tryDelete() {
        String name = this.delName.getValue().trim();
        if (name.isEmpty()) {
            this.setError(Component.translatable("gui.mockplayer.feedback.invalid_name"));
            return;
        }
        Component result = FakePlayerCommands.delPlayer(name);
        this.setFeedback(result);
        this.delName.setValue("");
        if (this.selected != null && this.selected.getName().equals(name)) {
            this.selected = null;
        }
    }

    // ===== tick：状态刷新 =====

    @Override
    public void tick() {
        BotGui.recordTick();
        // 选中假人掉线/删除 → 自动切到第一个可用
        if (this.selected != null && (this.selected.getLifecycle() != BotLifecycle.PLAYING
                || !coreBots().contains(this.selected))) {
            this.selected = firstSelectableBot();
        }
        List<Bot> bots = coreBots();
        for (int i = 0; i < this.botButtons.size(); i++) {
            Button b = this.botButtons.get(i);
            if (i < bots.size()) {
                Bot bot = bots.get(i);
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
        this.turnLeft.active = ready && actionsTab;
        this.turnRight.active = ready && actionsTab;
        this.turnUp.active = ready && actionsTab;
        this.turnDown.active = ready && actionsTab;
        this.moveForward.active = ready && actionsTab;
        this.moveBackward.active = ready && actionsTab;
        this.moveLeft.active = ready && actionsTab;
        this.moveRight.active = ready && actionsTab;
        this.stopButton.active = ready && actionsTab;
        this.sneakButton.active = ready && actionsTab;
        this.sprintButton.active = ready && actionsTab;
        this.jumpButton.active = ready && actionsTab;
        this.attackLookButton.active = ready && actionsTab;
        this.useLookButton.active = ready && actionsTab;
        this.holdAttackButton.active = ready && actionsTab;
        this.holdUseButton.active = ready && actionsTab;
        this.chunkMinusButton.active = ready && actionsTab;
        this.chunkPlusButton.active = ready && actionsTab;
        this.respawnButton.active = ready && actionsTab;
        this.autoRespawnButton.active = ready && actionsTab;
        this.closeContainerButton.active = ready && containerOpen && actionsTab;
        this.sendButton.active = ready && actionsTab;
        this.chatBox.active = ready && actionsTab;
        this.newButton.active = true;
        this.delButton.active = true;
        this.closeContainerButton.visible = actionsTab;
        for (Button b : this.hotbarButtons) {
            b.active = ready && actionsTab;
        }
        // 开关回显（on/off 状态写进按钮文字）
        if (ready) {
            this.sneakButton.setMessage(Component.translatable(
                    this.selected.actions().isSneaking() ? "gui.mockplayer.action.sneak_on" : "gui.mockplayer.action.sneak"));
            this.sprintButton.setMessage(Component.translatable(
                    this.selected.actions().isSprinting() ? "gui.mockplayer.action.sprint_on" : "gui.mockplayer.action.sprint"));
            this.jumpButton.setMessage(Component.translatable(
                    this.selected.actions().isJumping() ? "gui.mockplayer.action.jump_on" : "gui.mockplayer.action.jump"));
            this.autoRespawnButton.setMessage(Component.translatable(
                    this.selected.isAutoRespawn() ? "gui.mockplayer.action.auto_respawn_on" : "gui.mockplayer.action.auto_respawn_off"));
        }
        // 附近实体按钮（动作 Tab）
        this.entityTargets.clear();
        List<Entity> near = ready ? this.selected.getEntitiesNear(12.0).stream()
                .filter(e -> !(e instanceof net.minecraft.world.entity.player.Player))
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(this.selected.getLocalPlayer())))
                .limit(4).toList() : List.of();
        for (int i = 0; i < this.entityButtons.size(); i++) {
            Button b = this.entityButtons.get(i);
            if (i < near.size()) {
                Entity e = near.get(i);
                this.entityTargets.add(e);
                b.visible = actionsTab;
                b.active = ready;
                b.setMessage(Component.literal(e.getName().getString() + "·"
                        + String.format(Locale.ROOT, "%.0f",
                        Math.sqrt(e.distanceToSqr(this.selected.getLocalPlayer()))) + "m"));
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
    private int sx(int localX) {
        return this.panelX() + Math.round(localX * this.scale());
    }

    /** 面板逻辑 Y → 屏幕 Y。 */
    private int sy(int localY) {
        return this.panelY() + Math.round(localY * this.scale());
    }

    /** 逻辑尺寸 → 屏幕尺寸（缩放，最小 1px 防消失）。 */
    private int sw(int localSize) {
        return Math.max(1, Math.round(localSize * this.scale()));
    }

    /** 逻辑高度 → 屏幕高度（与 sw 同缩放）。 */
    private int sh(int localSize) {
        return this.sw(localSize);
    }

    private double localX(double screenX) {
        return (screenX - this.panelX()) / this.scale();
    }

    private double localY(double screenY) {
        return (screenY - this.panelY()) / this.scale();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 控件已按屏幕坐标直排：原样交给 super 命中；网格（非控件）再按逻辑坐标换算
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return this.handleContentClick(this.localX(event.x()), this.localY(event.y()),
                event.buttonInfo());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
                int slot = this.containerSlotAt(mx, my, this.containerSlotCount(container.get()));
                if (slot >= 0) {
                    this.containerClick(container.get(), slot, info);
                    return true;
                }
            } else {
                int slot = this.inventorySlotAt(mx, my);
                if (slot >= 0) {
                    this.inventoryClick(slot, info);
                    return true;
                }
            }
        }
        return false;
    }

    /** 假人背包格子（inventoryMenu 槽位布局：盔甲 5-8 / 主背包 9-35 / 快捷栏 36-44 / 副手 45）。 */
    private int inventorySlotAt(double mx, double my) {
        double bx = mx - CONTENT_X;
        double by = my - CONTENT_Y;
        int gx = (int) (bx / CELL);
        int gy = (int) (by / CELL);
        // 盔甲列（x=0）
        if (gx == 0 && gy >= 0 && gy < 4) {
            return 5 + gy;
        }
        // 主背包 3 行（x=24 起，y=0..2）
        int gxMain = (int) ((bx - 24) / CELL);
        if (gxMain >= 0 && gxMain < 9 && gy >= 0 && gy < 3) {
            return 9 + gy * 9 + gxMain;
        }
        // 快捷栏 1 行（y=3）+ 副手（x=24+9*CELL）
        if (gy == 3) {
            if (gxMain >= 0 && gxMain < 9) {
                return 36 + gxMain;
            }
            int gxOff = (int) ((bx - 24 - 9 * CELL) / CELL);
            if (gxOff == 0) {
                return 45;
            }
        }
        return -1;
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

    /** 容器菜单格子：容器槽（每行 9）+ 下方假人背包（菜单末尾 36 槽）。 */
    private int containerSlotAt(double mx, double my, int containerSize) {
        double bx = mx - CONTENT_X;
        double by = my - CONTENT_Y;
        int rows = (containerSize + 8) / 9;
        int gx = (int) (bx / CELL);
        int gy = (int) (by / CELL);
        if (gx >= 0 && gx < 9 && gy >= 0 && gy < rows && gy * 9 + gx < containerSize) {
            return gy * 9 + gx;
        }
        int playerY = rows * CELL + 8;
        int py = (int) ((by - playerY) / CELL);
        if (gx >= 0 && gx < 9 && py >= 0 && py < 4) {
            int offset = containerSize + py * 9 + gx;
            Optional<BotContainer> container = this.selected.getContainer();
            if (container.isPresent() && offset < container.get().getSize()) {
                return offset;
            }
        }
        return -1;
    }

    /** 容器菜单的「容器槽」数量：slots 中连续的前段、container 不是假人背包的部分（箱子=27）。 */
    private int containerSlotCount(BotContainer container) {
        net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (net.minecraft.world.inventory.Slot slot : container.raw().slots) {
            if (slot.container == player.getInventory()) {
                break;
            }
            count++;
        }
        return count;
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
        int px = this.panelX();
        int py = this.panelY();
        int pw = BotGui.panelWidth(this.width, this.height);
        int ph = BotGui.panelHeight(this.width, this.height);
        // 面板背景：渐变 + 双层边框 + 顶栏 + 左栏分隔线
        graphics.fillGradient(px, py, px + pw, py + ph, 0xE8253047, 0xE80A0D16);
        graphics.outline(px, py, pw, ph, 0xFF3A4A6A);
        graphics.outline(px + 1, py + 1, pw - 2, ph - 2, 0xFF151C29);
        int headerH = this.sh(18);
        graphics.fill(px, py, px + pw, py + headerH, 0xFF141D2C);
        graphics.fill(px, py + headerH - 1, px + pw, py + headerH, 0xFF3A86FF);
        int dividerX = this.sx(LIST_X + LIST_W + 6);
        graphics.fill(dividerX, py + headerH, dividerX + 1, py + ph, 0xFF1B2537);
        // 控件全部按屏幕坐标直排（init 时 sx/sy/sw 换算好），命中与渲染同一坐标系
        super.extractRenderState(graphics, mouseX, mouseY, a);
        this.drawContent(graphics, mouseX, mouseY);
    }

    /** 面板内手动绘制内容（状态文本/网格/反馈，全部换算为屏幕坐标）。 */
    private void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 顶栏标题（居中）
        graphics.centeredText(this.font, this.getTitle(),
                this.panelX() + BotGui.panelWidth(this.width, this.height) / 2,
                this.sy(5), 0xFFFFFF);
        // 反馈行
        int feedbackColor = this.feedbackError ? 0xFFFF5555
                : this.feedback.getString().isEmpty() ? 0xFFAAAAAA : 0xFF55FF55;
        graphics.centeredText(this.font, this.feedback,
                this.sx(BotGui.PANEL_W / 2), this.sy(FEEDBACK_Y), feedbackColor);
        // 左栏标题
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.bots"),
                this.sx(LIST_X), this.sy(26), 0xFF7FB2FF);
        if (this.selected == null) {
            graphics.text(this.font, Component.translatable("gui.mockplayer.status.no_bot"),
                    this.sx(CONTENT_X), this.sy(CONTENT_Y), 0xAAAAAA);
            return;
        }
        switch (this.tab) {
            case 0 -> this.drawStatus(graphics);
            case 1 -> {
                // 背包 Tab 合并容器：开容器自动切容器布局，关容器回 46 槽背包
                if (this.selected.getContainer().isPresent()) {
                    this.drawContainer(graphics, mouseX, mouseY);
                } else {
                    this.drawInventory(graphics, mouseX, mouseY);
                }
            }
            default -> this.drawActions(graphics);
        }
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.status"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y), 0xFF7FB2FF);
        List<Component> lines = statusLines(this.selected);
        int x = this.sx(CONTENT_X);
        int y = this.sy(CONTENT_Y + 12);
        int step = this.sh(11);
        for (int i = 0; i < lines.size(); i++) {
            // 首行血量/饱食度自带颜色，其余浅灰
            graphics.text(this.font, lines.get(i), x, y, i == 0 ? 0xFFFFFF : 0xFFD7D7D7);
            y += step;
        }
    }

    /** 状态面板文本行（公共静态，GUI 渲染与测试共用同一数据源）。 */
    public static List<Component> statusLines(Bot bot) {
        List<Component> lines = new ArrayList<>();
        if (bot == null) {
            lines.add(Component.translatable("gui.mockplayer.status.no_bot"));
            return lines;
        }
        net.minecraft.client.player.LocalPlayer player = bot.getLocalPlayer();
        if (player == null) {
            lines.add(Component.translatable("gui.mockplayer.status.not_playing"));
            return lines;
        }
        MutableComponent stats = Component.literal("❤" + Math.round(player.getHealth()))
                .withStyle(ChatFormatting.RED);
        stats.append(Component.literal(" 🍗" + player.getFoodData().getFoodLevel()
                + "(" + Math.round(player.getFoodData().getSaturationLevel()) + ")")
                .withStyle(ChatFormatting.GOLD));
        lines.add(stats);
        Direction dir = Direction.fromYRot(player.getYRot());
        lines.add(Component.translatable("gui.mockplayer.status.pos",
                String.format(Locale.ROOT, "%.1f / %.1f / %.1f",
                        player.getX(), player.getY(), player.getZ()),
                dir.getName()));
        double speed = player.getDeltaMovement().horizontalDistance() * 20.0;
        lines.add(Component.translatable("gui.mockplayer.status.speed",
                String.format(Locale.ROOT, "%.1f", speed),
                bot.memoryInfo().trackedBytes() >= 1024L * 1024L
                        ? String.format(Locale.ROOT, "%.1f MB",
                        bot.memoryInfo().trackedBytes() / (1024.0 * 1024.0))
                        : String.format(Locale.ROOT, "%.1f KB",
                        bot.memoryInfo().trackedBytes() / 1024.0),
                bot.getChunkRadius()));
        ItemStack mainHand = player.getMainHandItem();
        lines.add(Component.translatable("gui.mockplayer.status.slot",
                player.getInventory().getSelectedSlot(),
                mainHand.isEmpty() ? Component.translatable("gui.mockplayer.value.empty")
                        : mainHand.getHoverName()));
        Optional<BotContainer> container = bot.getContainer();
        lines.add(Component.translatable("gui.mockplayer.status.container",
                container.map(c -> (Component) c.getTitle())
                        .orElseGet(() -> Component.translatable("gui.mockplayer.value.none"))));
        lines.add(Component.translatable("gui.mockplayer.status.auto_respawn",
                Component.translatable(bot.isAutoRespawn()
                        ? "gui.mockplayer.value.on" : "gui.mockplayer.value.off")));
        // 运行中状态
        List<Component> running = new ArrayList<>();
        Vec2 move = player.input.getMoveVector();
        if (move.y > 0.01F) {
            running.add(Component.translatable("gui.mockplayer.status.running_forward"));
        } else if (move.y < -0.01F) {
            running.add(Component.translatable("gui.mockplayer.status.running_backward"));
        }
        if (move.x > 0.01F) {
            running.add(Component.translatable("gui.mockplayer.status.running_right"));
        } else if (move.x < -0.01F) {
            running.add(Component.translatable("gui.mockplayer.status.running_left"));
        }
        if (bot.actions().isSneaking()) {
            running.add(Component.translatable("gui.mockplayer.status.running_sneak"));
        }
        if (bot.actions().isSprinting()) {
            running.add(Component.translatable("gui.mockplayer.status.running_sprint"));
        }
        if (bot.actions().isJumping()) {
            running.add(Component.translatable("gui.mockplayer.status.running_jump"));
        }
        if (bot.actions().isMining()) {
            running.add(Component.translatable("gui.mockplayer.status.running_mining"));
        }
        if (bot.actions().isSustainedAttacking()) {
            running.add(Component.translatable("gui.mockplayer.status.running_attack"));
        }
        if (bot.actions().isSustainedUsing()) {
            running.add(Component.translatable("gui.mockplayer.status.running_use"));
        }
        MutableComponent runningLine = Component.translatable("gui.mockplayer.status.running");
        if (running.isEmpty()) {
            runningLine.append(Component.translatable("gui.mockplayer.value.none"));
        } else {
            for (int i = 0; i < running.size(); i++) {
                if (i > 0) {
                    runningLine.append(Component.literal(" / "));
                }
                runningLine.append(running.get(i));
            }
        }
        lines.add(runningLine);
        return lines;
    }

    private void drawInventory(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        net.minecraft.client.player.LocalPlayer player = this.selected.getLocalPlayer();
        if (player == null) {
            return;
        }
        double mx = this.localX(mouseX);
        double my = this.localY(mouseY);
        int hovered = this.inventorySlotAt(mx, my);
        // 注意：显示与点击共用「菜单槽」语义（盔甲 5-8 / 主背包 9-35 / 快捷栏 36-44 / 副手 45），
        // 不能直接拿菜单槽号去 Inventory.getItem（那里 0-8 才是快捷栏，36-40 是装备/副手）
        // 盔甲列（槽 5-8）
        for (int i = 0; i < 4; i++) {
            this.drawSlot(graphics, CONTENT_X, CONTENT_Y + i * CELL,
                    inventoryItem(player, 5 + i), hovered == 5 + i, slotIcon(player, 5 + i));
        }
        // 主背包 27 格（槽 9-35）
        for (int i = 0; i < 27; i++) {
            this.drawSlot(graphics, CONTENT_X + 24 + (i % 9) * CELL, CONTENT_Y + (i / 9) * CELL,
                    inventoryItem(player, 9 + i), hovered == 9 + i, slotIcon(player, 9 + i));
        }
        // 快捷栏 9 格（槽 36-44）
        for (int i = 0; i < 9; i++) {
            this.drawSlot(graphics, CONTENT_X + 24 + i * CELL, CONTENT_Y + 3 * CELL,
                    inventoryItem(player, 36 + i), hovered == 36 + i, slotIcon(player, 36 + i));
        }
        // 副手（槽 45）
        this.drawSlot(graphics, CONTENT_X + 24 + 9 * CELL, CONTENT_Y + 3 * CELL,
                inventoryItem(player, 45), hovered == 45, slotIcon(player, 45));
        // 选中槽高亮
        int sel = player.getInventory().getSelectedSlot();
        graphics.outline(this.sx(CONTENT_X + 24 + sel * CELL), this.sy(CONTENT_Y + 3 * CELL),
                this.sw(SLOT + 2), this.sw(SLOT + 2), 0xFFFFFF00);
    }

    /**
     * 背包 Tab 显示物品 = inventoryMenu 菜单槽（与 {@link #inventoryClick} 的
     * handleContainerInput 用同一槽位语义；越界返回空）。
     */
    public static ItemStack inventoryItem(net.minecraft.client.player.LocalPlayer player, int menuSlot) {
        if (player == null || menuSlot < 0 || menuSlot >= player.inventoryMenu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return player.inventoryMenu.getSlot(menuSlot).getItem();
    }

    /**
     * 背包槽位空图标（原版装备/副手槽背景，如 container/slot/helmet、shield；
     * 普通槽无图标返回 null）。绘制与测试共用同一数据源。
     */
    public static Identifier slotIcon(net.minecraft.client.player.LocalPlayer player, int menuSlot) {
        if (player == null || menuSlot < 0 || menuSlot >= player.inventoryMenu.slots.size()) {
            return null;
        }
        return player.inventoryMenu.getSlot(menuSlot).getNoItemIcon();
    }

    private void drawContainer(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Optional<BotContainer> container = this.selected.getContainer();
        if (container.isEmpty()) {
            graphics.text(this.font, Component.translatable("gui.mockplayer.status.no_container"),
                    this.sx(CONTENT_X), this.sy(CONTENT_Y), 0xAAAAAA);
            return;
        }
        BotContainer c = container.get();
        AbstractContainerMenu menu = c.raw();
        int containerSize = this.containerSlotCount(c);
        int rows = (containerSize + 8) / 9;
        double mx = this.localX(mouseX);
        double my = this.localY(mouseY);
        int hovered = this.containerSlotAt(mx, my, containerSize);
        for (int i = 0; i < containerSize; i++) {
            this.drawSlot(graphics, CONTENT_X + (i % 9) * CELL, CONTENT_Y + (i / 9) * CELL,
                    c.getSlot(i), hovered == i, menu.getSlot(i).getNoItemIcon());
        }
        // 假人背包部分（菜单末尾 36 槽）
        int playerY = rows * CELL + 8;
        int playerCount = Math.min(36, c.getSize() - containerSize);
        for (int i = 0; i < playerCount; i++) {
            int idx = containerSize + i;
            if (idx < c.getSize()) {
                this.drawSlot(graphics, CONTENT_X + (i % 9) * CELL,
                        CONTENT_Y + playerY + (i / 9) * CELL, c.getSlot(idx), hovered == idx,
                        menu.getSlot(idx).getNoItemIcon());
            }
        }
    }

    /** 动作 Tab：分区标题（按钮本身由控件渲染）。 */
    private void drawActions(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.look"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y), 0xFF7FB2FF);
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.move"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y + 26), 0xFF7FB2FF);
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.hotbar"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y + 68), 0xFF7FB2FF);
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.interact"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y + 94), 0xFF7FB2FF);
        graphics.text(this.font, Component.translatable("gui.mockplayer.section.system"),
                this.sx(CONTENT_X), this.sy(CONTENT_Y + 122), 0xFF7FB2FF);
    }

    /** 画一个槽位（逻辑坐标入参，内部换算屏幕坐标；边框 + 空槽图标 + 物品图标 + 数量 + 悬停高亮）。 */
    private void drawSlot(GuiGraphicsExtractor graphics, int lx, int ly, ItemStack stack,
                          boolean hovered, Identifier emptyIcon) {
        int x = this.sx(lx);
        int y = this.sy(ly);
        int cell = this.sw(CELL);
        int slot = Math.max(1, cell - 2);
        graphics.fill(x, y, x + cell, y + cell, hovered ? 0xFF3E4C66 : 0xFF222B3A);
        graphics.outline(x, y, cell, cell, hovered ? 0xFF7FB2FF : 0xFF0E1420);
        // 原版语义：槽位为空时画装备/副手背景图标（物品存在则不画）
        if (stack.isEmpty() && emptyIcon != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, emptyIcon, x + 1, y + 1,
                    this.sw(16), this.sw(16));
            com.mockplayer.gui.BotGui.recordSlotIcon();
        }
        if (!stack.isEmpty()) {
            graphics.item(stack, x + 1, y + 1);
            if (stack.getCount() > 1) {
                graphics.text(this.font, String.valueOf(stack.getCount()),
                        x + this.sw(10), y + this.sw(10), 0xFFFFFF, true);
            }
        }
    }
}

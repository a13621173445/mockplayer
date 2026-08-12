package com.mockplayer.gui;

import com.mockplayer.api.Bot;
import com.mockplayer.api.container.BotContainer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * BotControlScreen 的纯静态 HUD 辅助（P4-6 拆组件第一步）。
 *
 * 只放不依赖界面实例状态的成员：sprite 常量、透明度工具、选中假人文本、
 * 状态面板文本行、背包/容器槽位查询与 tooltip。渲染与测试共用同一数据源。
 */
public final class BotControlHud {

    /** 原版 HUD 经验条 sprite（与主玩家 ExperienceBar 同源）。 */
    public static final Identifier XP_BAR_BACKGROUND =
            Identifier.withDefaultNamespace("hud/experience_bar_background");
    public static final Identifier XP_BAR_PROGRESS =
            Identifier.withDefaultNamespace("hud/experience_bar_progress");
    /** 原版 HUD 血量条 sprite（主玩家 Hud.HeartType 同源）。 */
    public static final Identifier HEART_CONTAINER =
            Identifier.withDefaultNamespace("hud/heart/container");
    public static final Identifier HEART_FULL =
            Identifier.withDefaultNamespace("hud/heart/full");
    public static final Identifier HEART_HALF =
            Identifier.withDefaultNamespace("hud/heart/half");
    /** 原版 HUD 饥饿条 sprite（主玩家 Hud 同源）。 */
    public static final Identifier FOOD_EMPTY =
            Identifier.withDefaultNamespace("hud/food_empty");
    public static final Identifier FOOD_HALF =
            Identifier.withDefaultNamespace("hud/food_half");
    public static final Identifier FOOD_FULL =
            Identifier.withDefaultNamespace("hud/food_full");
    /** 原版 HUD 盔甲条 sprite。 */
    public static final Identifier ARMOR_EMPTY = Identifier.withDefaultNamespace("hud/armor_empty");
    public static final Identifier ARMOR_HALF = Identifier.withDefaultNamespace("hud/armor_half");
    public static final Identifier ARMOR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
    /** 原版 HUD 饥饿（饥饿效果）sprite。 */
    public static final Identifier FOOD_EMPTY_HUNGER =
            Identifier.withDefaultNamespace("hud/food_empty_hunger");
    public static final Identifier FOOD_HALF_HUNGER =
            Identifier.withDefaultNamespace("hud/food_half_hunger");
    public static final Identifier FOOD_FULL_HUNGER =
            Identifier.withDefaultNamespace("hud/food_full_hunger");

    private BotControlHud() {
    }

    /** 按配置不透明度合成颜色：alpha = 基础 alpha × opacity（0-1）。 */
    public static int withAlpha(int color, float opacity) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * opacity);
        return (color & 0xFFFFFF) | (alpha << 24);
    }

    /** 按钮 alpha：保证至少 35% 可读（面板越透明按钮也不至于看不清）。 */
    public static float buttonAlpha(float opacity) {
        return Math.max(0.35F, opacity);
    }

    /** 底部常驻「当前选中假人」文本（绘制与测试共用）。 */
    public static Component selectedText(Bot selected) {
        return Component.translatable("gui.mockplayer.feedback.selected_bot",
                selected == null
                        ? Component.translatable("gui.mockplayer.value.none")
                        : Component.literal(selected.getName()));
    }

    /** 右下角选中假人显示文本（按右侧固定宽度截断，名字长短自适应不溢出）。 */
    public static String selectedTextDisplay(Bot selected, net.minecraft.client.gui.Font font, int maxWidth) {
        return font.plainSubstrByWidth(selectedText(selected).getString(), Math.max(4, maxWidth));
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
        long displayBytes = bot.memoryInfo().displayBytes();
        lines.add(Component.translatable("gui.mockplayer.status.speed",
                String.format(Locale.ROOT, "%.1f", speed),
                displayBytes >= 1024L * 1024L
                        ? String.format(Locale.ROOT, "%.1f MB",
                        displayBytes / (1024.0 * 1024.0))
                        : String.format(Locale.ROOT, "%.1f KB",
                        displayBytes / 1024.0),
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

    /**
     * 背包 Tab 显示物品 = inventoryMenu 菜单槽（与 {@code inventoryClick} 的
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

    /**
     * 背包槽位悬停 tooltip：原版物品信息 + 数量行（空槽返回 null）。
     * 绘制与测试共用同一数据源。
     */
    public static List<Component> slotTooltip(net.minecraft.client.player.LocalPlayer player, int menuSlot) {
        return itemTooltip(inventoryItem(player, menuSlot));
    }

    /**
     * 容器菜单槽位悬停 tooltip：原版物品信息 + 数量行（空槽/越界返回 null）。
     */
    public static List<Component> containerSlotTooltip(BotContainer container, int menuSlot) {
        if (container == null || menuSlot < 0 || menuSlot >= container.getSize()) {
            return null;
        }
        return itemTooltip(container.getSlot(menuSlot));
    }

    /** 原版物品 tooltip lines；空物品返回 null（不额外拼数量行）。 */
    private static List<Component> itemTooltip(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // 纯原版 tooltip（名称/附魔/组件）
        return new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
    }
}

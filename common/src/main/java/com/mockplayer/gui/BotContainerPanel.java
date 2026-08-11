package com.mockplayer.gui;

import com.mockplayer.api.container.BotContainer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;
import java.util.Optional;

/**
 * 容器面板渲染（P4-6 拆组件）：容器槽 + 假人背包区 + 悬停 tooltip。
 *
 * 纯搬移自 BotControlScreen，行为零变化；容器槽位命中（containerSlotAt）与
 * 容器槽数量（containerSlotCount）随渲染逻辑搬入，供 BotControlScreen 点击
 * 处理复用，保证显示/点击同一套几何。
 */
public final class BotContainerPanel {

    private BotContainerPanel() {
    }

    /** 容器 Tab 合并背包：顶部容器槽（每行 9）+ 下方假人背包（菜单末尾 36 槽）。 */
    public static void render(BotControlScreen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Optional<BotContainer> container = screen.selected.getContainer();
        if (container.isEmpty()) {
            graphics.text(screen.font(), Component.translatable("gui.mockplayer.status.no_container"),
                    screen.sx(BotControlScreen.CONTENT_X), screen.sy(BotControlScreen.CONTENT_Y), 0xAAAAAA);
            return;
        }
        BotContainer c = container.get();
        AbstractContainerMenu menu = c.raw();
        int containerSize = containerSlotCount(screen, c);
        int rows = (containerSize + 8) / 9;
        // 顶部标题行：X 按钮（控件）+ 容器标题
        graphics.text(screen.font(), screen.font().plainSubstrByWidth(
                        c.getTitle().getString(), screen.sw(BotControlScreen.CONTENT_W - 20)),
                screen.sx(BotControlScreen.CONTENT_X + 16), screen.sy(BotControlScreen.CONTENT_Y + 2), 0xFFD7D7D7);
        double mx = screen.localX(mouseX);
        double my = screen.localY(mouseY);
        int hovered = containerSlotAt(screen, mx, my, containerSize);
        for (int i = 0; i < containerSize; i++) {
            BotInventoryPanel.drawSlot(screen, graphics, BotControlScreen.CONTENT_X + (i % 9) * BotControlScreen.CELL,
                    BotControlScreen.CONTENT_Y + BotControlScreen.CONTAINER_HEADER_H
                            + (i / 9) * BotControlScreen.CELL,
                    c.getSlot(i), hovered == i, menu.getSlot(i).getNoItemIcon());
        }
        // 假人背包部分（菜单末尾 36 槽）
        int playerY = rows * BotControlScreen.CELL + 8;
        int playerCount = Math.min(36, c.getSize() - containerSize);
        for (int i = 0; i < playerCount; i++) {
            int idx = containerSize + i;
            if (idx < c.getSize()) {
                BotInventoryPanel.drawSlot(screen, graphics, BotControlScreen.CONTENT_X + (i % 9) * BotControlScreen.CELL,
                        BotControlScreen.CONTENT_Y + BotControlScreen.CONTAINER_HEADER_H
                                + playerY + (i / 9) * BotControlScreen.CELL,
                        c.getSlot(idx), hovered == idx,
                        menu.getSlot(idx).getNoItemIcon());
            }
        }
        // 悬停物品信息（原版 tooltip + 数量行）
        if (hovered >= 0) {
            List<Component> lines = BotControlHud.containerSlotTooltip(c, hovered);
            if (lines != null) {
                graphics.setTooltipForNextFrame(screen.font(),
                        lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
                BotGui.recordTooltip();
            }
        }
    }

    /**
     * 容器菜单格子：容器槽（每行 9）+ 下方假人背包（菜单末尾 36 槽）。
     * 供 BotControlScreen 点击处理与渲染共用，保证显示/点击同一套几何。
     */
    static int containerSlotAt(BotControlScreen screen, double mx, double my, int containerSize) {
        double bx = mx - BotControlScreen.CONTENT_X;
        double by = my - BotControlScreen.CONTENT_Y - BotControlScreen.CONTAINER_HEADER_H;
        int rows = (containerSize + 8) / 9;
        int gx = (int) (bx / BotControlScreen.CELL);
        int gy = (int) (by / BotControlScreen.CELL);
        if (gx >= 0 && gx < 9 && gy >= 0 && gy < rows && gy * 9 + gx < containerSize) {
            return gy * 9 + gx;
        }
        int playerY = rows * BotControlScreen.CELL + 8;
        int py = (int) ((by - playerY) / BotControlScreen.CELL);
        if (gx >= 0 && gx < 9 && py >= 0 && py < 4) {
            int offset = containerSize + py * 9 + gx;
            Optional<BotContainer> container = screen.selected.getContainer();
            if (container.isPresent() && offset < container.get().getSize()) {
                return offset;
            }
        }
        return -1;
    }

    /** 容器菜单的「容器槽」数量：slots 中连续的前段、container 不是假人背包的部分（箱子=27）。 */
    static int containerSlotCount(BotControlScreen screen, BotContainer container) {
        net.minecraft.client.player.LocalPlayer player = screen.selected.getLocalPlayer();
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
}

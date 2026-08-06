package com.mockplayer.session;

import com.mockplayer.api.container.BotContainer;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * BotContainer 实现：包装假人当前打开的 {@link AbstractContainerMenu}。
 *
 * click/setSlot 复用原版菜单逻辑（clicked 自动生成 ServerboundContainerClickPacket 发到假人连接）。
 */
public class BotContainerImpl implements BotContainer {

    protected final BotImpl bot;
    protected final AbstractContainerMenu menu;
    protected final Component title;

    public BotContainerImpl(BotImpl bot, AbstractContainerMenu menu, Component title) {
        this.bot = bot;
        this.menu = menu;
        this.title = title;
    }

    @Override
    public int getContainerId() {
        return this.menu.containerId;
    }

    @Override
    public MenuType<?> getMenuType() {
        return this.menu.getType();
    }

    @Override
    public Component getTitle() {
        return this.title;
    }

    @Override
    public int getSize() {
        return this.menu.slots.size();
    }

    @Override
    public ItemStack getSlot(int slot) {
        if (slot < 0 || slot >= this.menu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return this.menu.getSlot(slot).getItem();
    }

    @Override
    public void click(int slot, int button, ContainerInput input) {
        if (this.bot.getLocalPlayer() == null) {
            return;
        }
        this.menu.clicked(slot, button, input, this.bot.getLocalPlayer());
    }

    @Override
    public void setSlot(int slot, ItemStack stack) {
        // 本地乐观写入（服务端会以回包为准修正）；完整拖拽/移动请用 click。
        this.menu.setItem(slot, 0, stack);
    }

    @Override
    public void close() {
        // 本地立即清容器会话（不等服务端回 ContainerClose 包），并走 LocalPlayer.closeContainer()
        // 发 ServerboundContainerClosePacket + containerMenu 设回 inventoryMenu（与原版玩家关容器一致）
        this.bot.clearOpenMenu();
        if (this.bot.getLocalPlayer() != null) {
            this.bot.getLocalPlayer().closeContainer();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends AbstractContainerMenu> T raw() {
        return (T) this.menu;
    }
}

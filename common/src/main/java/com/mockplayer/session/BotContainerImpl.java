package com.mockplayer.session;

import com.mockplayer.api.container.BotContainer;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
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
        // 骑乘类菜单（HorseInventoryMenu/NautilusInventoryMenu）MenuType 为 null，
        // 原版 AbstractContainerMenu.getType() 此时抛 UnsupportedOperationException，这里归一为 null
        try {
            return this.menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
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
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 受控异常代替原版 IndexOutOfBounds（原版 AbstractContainerMenu.clicked 越界会崩客户端）：
        // 调用方（ControlCommands 命令层已前置校验）应捕获并转成用户可读错误。
        if (slot < -1 || slot >= this.menu.slots.size()) {
            throw new IllegalArgumentException("slot out of bounds: " + slot
                    + " (menu slots=" + this.menu.slots.size() + ")");
        }
        // 复用原版发包方法：handleContainerInput 内部调 menu.clicked（本地移动）+ 生成并发送
        // ServerboundContainerClickPacket（含 stateId/changedSlots/carried）。只调 menu.clicked 会本地改
        // 但服务端容器不更新（点击包没发出去），测试实测容器操作不生效。
        MultiPlayerGameMode gameMode = this.bot.getGameMode();
        if (gameMode != null) {
            gameMode.handleContainerInput(this.menu.containerId, slot, button, input, player);
        } else {
            this.menu.clicked(slot, button, input, player);
        }
    }

    @Override
    public void clickButton(int buttonId) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 菜单按钮点击（附魔选附魔等）：发 ServerboundContainerButtonClickPacket，服务端按菜单类型处理
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket(this.menu.containerId, buttonId));
    }

    @Override
    public ItemStack getCarried() {
        return this.menu.getCarried();
    }

    @Override
    public void selectTrade(int index) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 交易选择：发 ServerboundSelectTradePacket，服务端选中报价（交易菜单有效，其他菜单无害）
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(index));
    }

    @Override
    public void setSlot(int slot, ItemStack stack) {
        // 受控异常代替原版 IndexOutOfBounds：调用方应先校验槽位范围
        if (slot < 0 || slot >= this.menu.slots.size()) {
            throw new IllegalArgumentException("slot out of bounds: " + slot
                    + " (menu slots=" + this.menu.slots.size() + ")");
        }
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

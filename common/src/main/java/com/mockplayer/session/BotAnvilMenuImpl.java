package com.mockplayer.session;

import com.mockplayer.api.container.BotAnvilMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * BotAnvilMenu 实现：包装铁砧菜单。
 * 槽位：0 = 输入，1 = 附加，2 = 产物。
 */
public class BotAnvilMenuImpl extends BotContainerImpl implements BotAnvilMenu {

    private final AnvilMenu anvilMenu;

    public BotAnvilMenuImpl(BotImpl bot, AbstractContainerMenu menu, Component title, AnvilMenu anvilMenu) {
        super(bot, menu, title);
        this.anvilMenu = anvilMenu;
    }

    @Override
    public int getCost() {
        return this.anvilMenu.getCost();
    }

    @Override
    public void takeOutput() {
        // 点击产物槽（2）取出
        this.click(AnvilMenu.RESULT_SLOT, 0, ContainerInput.PICKUP);
    }
}

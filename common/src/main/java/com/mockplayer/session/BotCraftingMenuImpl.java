package com.mockplayer.session;

import com.mockplayer.api.container.BotCraftingMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * BotCraftingMenu 实现：包装工作台合成菜单。
 * 槽位：0 = 结果，1-9 = 合成格。
 */
public class BotCraftingMenuImpl extends BotContainerImpl implements BotCraftingMenu {

    public BotCraftingMenuImpl(BotImpl bot, AbstractContainerMenu menu, Component title) {
        super(bot, menu, title);
    }

    @Override
    public ItemStack getResult() {
        return this.getSlot(0);
    }
}

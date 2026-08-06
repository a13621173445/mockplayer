package com.mockplayer.session;

import com.mockplayer.api.container.BotFurnaceMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;

/**
 * BotFurnaceMenu 实现：包装熔炉类菜单（AbstractFurnaceMenu）。
 * 进度用菜单 data slots（0=litTime, 1=litDuration, 2=cookingProgress, 3=cookingTotalTime）。
 */
public class BotFurnaceMenuImpl extends BotContainerImpl implements BotFurnaceMenu {

    public BotFurnaceMenuImpl(BotImpl bot, AbstractContainerMenu menu, Component title) {
        super(bot, menu, title);
    }

    @Override
    public float getProgress() {
        net.minecraft.world.inventory.ContainerData data =
                ((com.mockplayer.session.accessor.MockplayerAbstractFurnaceMenuAccessor) (Object) this.menu).mockplayer$getData();
        int total = data.get(3);
        return total == 0 ? 0.0F : (float) data.get(2) / total;
    }

    @Override
    public ItemStack getResult() {
        return this.getSlot(2);
    }
}

package com.mockplayer.api.container;

import net.minecraft.world.item.ItemStack;

/**
 * 合成菜单（CraftingMenu：工作台）特化视图。
 *
 * 通过 {@link com.mockplayer.api.Bot#getCrafting()} 获取。
 * 槽位：0 = 结果，1-9 = 合成格（3x3）。
 */
public interface BotCraftingMenu extends BotContainer {

    /**
     * 当前结果槽物品（合成产物）。
     *
     * @return ItemStack（无产物为空）
     */
    ItemStack getResult();
}

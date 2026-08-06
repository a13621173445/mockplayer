package com.mockplayer.api.container;

import net.minecraft.world.item.ItemStack;

/**
 * 熔炉类菜单（AbstractFurnaceMenu：熔炉/高炉/烟熏炉/营火）特化视图。
 *
 * 通过 {@link com.mockplayer.api.Bot#getFurnace()} 获取。
 * 槽位：0 = 原料，1 = 燃料，2 = 产物。
 */
public interface BotFurnaceMenu extends BotContainer {

    /**
     * 当前烧制进度。
     *
     * @return 0.0（未开始）~ 1.0（完成）
     */
    float getProgress();

    /**
     * 当前产物槽物品。
     *
     * @return ItemStack
     */
    ItemStack getResult();
}

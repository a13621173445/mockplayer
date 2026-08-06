package com.mockplayer.api.container;

import net.minecraft.world.item.trading.MerchantOffers;

/**
 * 村民交易菜单特化视图（当前菜单为 MenuType.MERCHANT 时可用）。
 *
 * 通过 {@link com.mockplayer.api.Bot#getMerchant()} 获取。
 */
public interface BotMerchantMenu extends BotContainer {

    /**
     * 当前交易报价列表。
     *
     * @return MerchantOffers
     */
    MerchantOffers getOffers();

    /**
     * 当前选中的交易槽位索引（-1 表示未选中）。
     *
     * @return 索引
     */
    int getSelectedOffer();

    /**
     * 选中指定交易（显示报价/库存，还需 {@link #trade} 或点槽位完成）。
     *
     * @param index 报价索引
     */
    void selectOffer(int index);

    /**
     * 执行交易（购买指定报价）。
     *
     * @param index 报价索引
     * @param times 交易次数（1 = 一次）
     */
    void trade(int index, int times);
}

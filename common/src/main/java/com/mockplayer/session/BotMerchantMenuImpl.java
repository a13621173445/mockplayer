package com.mockplayer.session;

import com.mockplayer.api.container.BotMerchantMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * BotMerchantMenu 实现：村民交易菜单特化视图。
 *
 * 交易流程与原版客户端一致：selectOffer 发 ServerboundSelectTradePacket 选中报价 →
 * 服务端更新 result 槽 → trade 点击购买槽完成交易。
 * 购买槽索引硬编码 2（MerchantMenu.RESULT_SLOT，原版固定布局；该常量是 protected 无法直接访问）。
 */
public class BotMerchantMenuImpl extends BotContainerImpl implements BotMerchantMenu {

    /** 购买（result）槽位索引：原版 MerchantMenu 固定布局 PAYMENT1=0, PAYMENT2=1, RESULT=2 */
    private static final int RESULT_SLOT = 2;

    private final MerchantMenu merchant;
    /** 最近一次 selectOffer 的报价索引（服务端可能拒绝/调整，仅作提示） */
    private int selectedIndex = -1;

    public BotMerchantMenuImpl(BotImpl bot, AbstractContainerMenu menu, Component title, MerchantMenu merchant) {
        super(bot, menu, title);
        this.merchant = merchant;
    }

    @Override
    public MerchantOffers getOffers() {
        return this.merchant.getOffers();
    }

    @Override
    public int getSelectedOffer() {
        return this.selectedIndex;
    }

    @Override
    public void selectOffer(int index) {
        this.selectedIndex = index;
        if (this.bot.getLocalPlayer() != null) {
            // 等价原版客户端选中交易：发 ServerboundSelectTradePacket，服务端回包更新 result 槽
            this.bot.getLocalPlayer().connection.send(new ServerboundSelectTradePacket(index));
        }
    }

    @Override
    public void trade(int index, int times) {
        if (index != this.selectedIndex) {
            selectOffer(index);
        }
        for (int i = 0; i < times; i++) {
            // 购买 = 点击 result 槽（原版点购买按钮同路径）
            this.click(RESULT_SLOT, 0, ContainerInput.PICKUP);
        }
    }
}

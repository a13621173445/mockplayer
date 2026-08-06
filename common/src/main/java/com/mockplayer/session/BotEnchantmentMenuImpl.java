package com.mockplayer.session;

import com.mockplayer.api.container.BotEnchantmentMenu;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;

/**
 * BotEnchantmentMenu 实现：包装附魔台菜单。
 * 附魔执行走原版按钮点击包（ServerboundContainerButtonClickPacket），服务端校验材料/经验。
 */
public class BotEnchantmentMenuImpl extends BotContainerImpl implements BotEnchantmentMenu {

    private final EnchantmentMenu enchantMenu;

    public BotEnchantmentMenuImpl(BotImpl bot, AbstractContainerMenu menu, Component title, EnchantmentMenu enchantMenu) {
        super(bot, menu, title);
        this.enchantMenu = enchantMenu;
    }

    @Override
    public int[] getCosts() {
        return this.enchantMenu.costs.clone();
    }

    @Override
    public void enchant(int slot) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.connection.send(new ServerboundContainerButtonClickPacket(this.enchantMenu.containerId, slot));
        }
    }
}

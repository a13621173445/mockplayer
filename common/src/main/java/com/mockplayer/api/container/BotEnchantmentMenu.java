package com.mockplayer.api.container;

/**
 * 附魔台（EnchantmentMenu）特化视图（当前菜单为 MenuType.ENCHANTMENT 时可用）。
 *
 * 通过 {@link com.mockplayer.api.Bot#getEnchantment()} 获取。
 * 附魔成本（经验等级）与可选附魔由服务端根据物品/材料计算。
 */
public interface BotEnchantmentMenu extends BotContainer {

    /**
     * 3 个可选附魔的等级成本（经验）。
     *
     * @return int[3]（元素 0 = 无效/未解锁）
     */
    int[] getCosts();

    /**
     * 执行附魔（点击附魔按钮）。
     *
     * @param slot 附魔槽（0-2）；服务端校验材料/经验是否足够
     */
    void enchant(int slot);
}

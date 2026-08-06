package com.mockplayer.api.container;

/**
 * 铁砧（AnvilMenu）特化视图（当前菜单为 MenuType.ANVIL 时可用）。
 *
 * 通过 {@link com.mockplayer.api.Bot#getAnvil()} 获取。
 * 槽位：0 = 输入，1 = 附加（修复材料），2 = 产物。
 */
public interface BotAnvilMenu extends BotContainer {

    /**
     * 当前改名/修复/合成的经验成本。
     *
     * @return 成本（经验等级）
     */
    int getCost();

    /**
     * 取出产物（槽 2）。
     */
    void takeOutput();
}

package com.mockplayer.api.container;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 容器菜单会话视图（对应假人当前打开的 AbstractContainerMenu）。
 *
 * 由服务端 openScreen 后创建、ContainerContent/ContainerSetSlot 驱动更新。
 * click/setSlot 会生成对应 ServerboundContainerClickPacket 发到假人连接。
 * 对第三方 mod 的自定义菜单：用 {@link #raw()} 逃生舱 cast 到对方菜单类操作。
 *
 * 所有方法必须在主线程调用。
 */
public interface BotContainer {

    /**
     * 菜单 id（服务端分配）。
     *
     * @return container id
     */
    int getContainerId();

    /**
     * 菜单类型（原版或 mod 注册的 MenuType）。
     *
     * @return MenuType
     */
    MenuType<?> getMenuType();

    /**
     * 菜单标题。
     *
     * @return Component
     */
    Component getTitle();

    /**
     * 槽位总数。
     *
     * @return 槽位数
     */
    int getSize();

    /**
     * 读取指定槽位的物品。
     *
     * @param slot 槽位索引
     * @return ItemStack
     */
    ItemStack getSlot(int slot);

    /**
     * 点击槽位（拿取/放置/换位/拖拽等，参数与 AbstractContainerMenu.clicked 一致）。
     *
     * @param slot   槽位（SLOT_CLICKED_OUTSIDE 表示菜单外）
     * @param button 按钮/鼠标键
     * @param input  点击类型（PICKUP/QUICK_MOVE/SWAP 等）
     */
    void click(int slot, int button, ContainerInput input);

    /**
     * 用指定物品覆盖槽位（服务端校验后回包确认）。
     *
     * @param slot  槽位索引
     * @param stack 物品
     */
    void setSlot(int slot, ItemStack stack);

    /**
     * 关闭容器菜单。
     */
    void close();

    /**
     * 逃生舱：返回底层 AbstractContainerMenu，外部可 cast 到具体菜单类
     * （如 mod 的菜单子类）做特化操作。
     *
     * @param <T> 菜单具体类型
     * @return 底层菜单
     * @throws ClassCastException 若 cast 到错误类型（由调用方负责）
     */
    <T extends AbstractContainerMenu> T raw();
}

package com.mockplayer.api;

import com.mockplayer.api.action.BotActions;
import com.mockplayer.api.container.BotAnvilMenu;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.api.container.BotCraftingMenu;
import com.mockplayer.api.container.BotEnchantmentMenu;
import com.mockplayer.api.container.BotFurnaceMenu;
import com.mockplayer.api.container.BotMerchantMenu;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 假人（bot）的统一抽象。
 *
 * 一个 Bot 对应一个独立客户端会话（真实 TCP 连接，服务端视角 = 普通玩家）。
 * 所有方法应只在主线程（渲染线程）调用。
 */
public interface Bot {

    /**
     * 假人名字（唯一）。
     *
     * @return 名字
     */
    String getName();

    /**
     * 假人离线 UUID。
     *
     * @return UUID
     */
    UUID getUUID();

    /**
     * 创建者标识（与 {@link BotProfile#owner()} 一致）。
     *
     * @return owner id
     */
    String getOwner();

    /**
     * 当前生命周期状态。
     *
     * @return BotLifecycle
     */
    BotLifecycle getLifecycle();

    /**
     * 逃生舱：底层 LocalPlayer（PLAYING 后才可用，之前为 null）。
     * 需要更底层控制（背包/能力/骑乘）时使用。
     *
     * @return LocalPlayer 或 null
     */
    LocalPlayer getLocalPlayer();

    /**
     * 逃生舱：假人自己的 MultiPlayerGameMode（PLAYING 后才可用）。
     * 需要原版游戏模式操作时使用。
     *
     * @return MultiPlayerGameMode 或 null
     */
    MultiPlayerGameMode getGameMode();

    /**
     * 假人自己的 ClientLevel（独立于主玩家 level）。
     *
     * @return ClientLevel 或 null（未 PLAYING）
     */
    ClientLevel getLevel();

    /**
     * 获取假人周围指定半径内的实体（不含假人自己）。
     *
     * @param range 半径（方块）
     * @return 实体列表
     */
    List<Entity> getEntitiesNear(double range);

    /**
     * 获取假人周围指定半径内、满足过滤条件的实体。
     *
     * @param range  半径（方块）
     * @param filter 过滤条件
     * @return 实体列表
     */
    List<Entity> getEntitiesNear(double range, Predicate<Entity> filter);

    /**
     * 读取假人 level 中某位置的方块状态。
     *
     * @param pos 方块位置
     * @return BlockState（区块未加载返回 air）
     */
    BlockState getBlockState(BlockPos pos);

    /**
     * 判断某位置的区块是否已加载（假人 level）。
     *
     * @param pos 方块位置
     * @return true 已加载
     */
    boolean isBlockLoaded(BlockPos pos);

    /**
     * 服务端 Tab 列表中的在线玩家（假人收到的 PlayerInfo 更新）。
     *
     * @return 在线玩家列表
     */
    List<PlayerInfo> getOnlinePlayers();

    /**
     * 行为原语入口（移动/攻击/交互等）。
     *
     * @return BotActions
     */
    BotActions actions();

    /**
     * 当前打开的容器菜单（服务端 openScreen 后才有值）。
     *
     * @return {@code Optional<BotContainer>}，未打开容器则为 empty
     */
    Optional<BotContainer> getContainer();

    /**
     * 当前打开的界面（服务端 openScreen 的任意菜单类型，通用视图）。
     * 等价于 {@link #getContainer()}，语义化命名；特化能力见 getEnchantment/getAnvil/getFurnace/getCrafting/getMerchant。
     *
     * @return {@code Optional<BotContainer>}，未打开界面则为 empty
     */
    Optional<BotContainer> getScreen();

    /**
     * 当前打开的是附魔台菜单时的附魔视图。
     *
     * @return {@code Optional<BotEnchantmentMenu>}，非附魔菜单则为 empty
     */
    Optional<BotEnchantmentMenu> getEnchantment();

    /**
     * 当前打开的是铁砧菜单时的铁砧视图。
     *
     * @return {@code Optional<BotAnvilMenu>}，非铁砧菜单则为 empty
     */
    Optional<BotAnvilMenu> getAnvil();

    /**
     * 当前打开的是熔炉类菜单（熔炉/高炉/烟熏炉/营火）时的视图。
     *
     * @return {@code Optional<BotFurnaceMenu>}，非熔炉菜单则为 empty
     */
    Optional<BotFurnaceMenu> getFurnace();

    /**
     * 当前打开的是工作台合成菜单时的视图。
     *
     * @return {@code Optional<BotCraftingMenu>}，非合成菜单则为 empty
     */
    Optional<BotCraftingMenu> getCrafting();

    /**
     * 当前打开的是村民交易菜单时的交易视图。
     *
     * @return {@code Optional<BotMerchantMenu>}，非交易菜单则为 empty
     */
    Optional<BotMerchantMenu> getMerchant();
}

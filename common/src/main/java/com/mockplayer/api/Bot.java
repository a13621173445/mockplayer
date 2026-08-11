package com.mockplayer.api;

import com.mockplayer.api.action.BotActions;
import com.mockplayer.api.container.BotContainer;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
     * 创建来源：CORE = 本 mod 命令创建（受命令/配置管理）；API = 外部/附属
     * mod 经公共 API 创建（不受本 mod 命令/配置管理）。
     *
     * @return BotSource
     */
    BotSource source();

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
     * 假人当前区块加载半径（客户端 chunk 缓存 + 服务端 requestedViewDistance）。
     *
     * @return 当前半径
     */
    int getChunkRadius();

    /**
     * 设置假人区块加载半径（范围 1-32，只作用于假人 level 与服务端对该假人的
     * 区块跟踪，主玩家 level / options 零影响）。
     *
     * @param radius 区块加载半径
     */
    void setChunkRadius(int radius);

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
     * 死亡后是否自动重生（默认 true；GUI/命令可关闭后由 {@link BotActions#respawn()} 手动重生）。
     *
     * @return true 自动重生
     */
    boolean isAutoRespawn();

    /**
     * 设置死亡后是否自动重生（只影响该假人，与主玩家无关）。
     *
     * @param autoRespawn true 自动重生
     */
    void setAutoRespawn(boolean autoRespawn);

    /**
     * 当前打开的容器菜单（服务端 openScreen 后才有值）。
     *
     * @return {@code Optional<BotContainer>}，未打开容器则为 empty
     */
    Optional<BotContainer> getContainer();

    /**
     * 假人内存信息（JVM 堆真实值 + Mod 侧跟踪估算 + level 实体/区块数）。
     *
     * 口径：JVM 字段来自 Runtime（真实值）；per-bot 字段只统计本 mod 管理的结构，
     * 是估算，不包含原版 ClientLevel 内部对象（详见 {@link BotMemoryInfo}）。
     *
     * @return 内存信息（未 PLAYING 时 level 统计为 0，其余字段仍有效）
     */
    BotMemoryInfo memoryInfo();
}

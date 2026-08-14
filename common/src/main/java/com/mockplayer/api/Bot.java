package com.mockplayer.api;

import com.mockplayer.api.action.BotActions;
import com.mockplayer.api.container.BotContainer;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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

    /**
     * 假人连接收到的 mod payload 元信息快照（入站拦截记录，最新在前）。
     * 只含 namespace/typeId/modName/tick/字节估算，不含原始对象引用。
     *
     * @return 入站 mod payload 记录（不可变；未 PLAYING 或没有记录时为空列表）
     */
    List<ModPayloadInfo> getReceivedModPayloads();

    /**
     * 入站记录按 typeId（"namespace:path"）过滤。
     *
     * @param typeId 完整 typeId，如 "yes_steve_model:sync"
     * @return 匹配的记录（不可变）
     */
    List<ModPayloadInfo> getReceivedModPayloads(String typeId);

    /**
     * 假人连接发出的 mod payload 元信息快照（出站记录，只记录不阻止发送）。
     *
     * @return 出站 mod payload 记录（不可变）
     */
    List<ModPayloadInfo> getSentModPayloads();

    /**
     * 出站记录按 typeId 过滤。
     *
     * @param typeId 完整 typeId
     * @return 匹配的记录（不可变）
     */
    List<ModPayloadInfo> getSentModPayloads(String typeId);

    /**
     * 双向清空 mod payload 记录（AI 消费后清理用）。
     */
    void clearModPayloads();

    /**
     * 逃生舱（登记理由：AI 需要解码 mod 数据时使用）：入站最近一次该 typeId 的原始
     * payload 对象（网络层已解码）。只读引用，不要修改；可 cast 到 mod 的 payload 类。
     *
     * @param typeId 完整 typeId
     * @return 原始对象或 null（无记录）
     */
    Object getLastRawModPayload(String typeId);

    /**
     * 入站最近一次该 typeId 的反射 dump（JSON 字符串，见 PayloadInspector）。
     * 调试用：人排查污染/验证拦截时看「这个包是什么」。
     *
     * @param typeId 完整 typeId
     * @return JSON 字符串或 null（无记录）
     */
    String getLastModPayloadDump(String typeId);

    /**
     * 发送自定义 serverbound payload（逃生舱，登记理由：AI 扩展 mod 玩法时使用）。
     *
     * 前置检查：payload 类型须已注册（fabric PayloadTypeRegistry / neoforge NetworkRegistry），
     * 未注册返回 false——26.1.2 原版 codec 只注册 brand，未注册类型会编码成 DiscardedPayload
     * 内容丢失。发送走假人自己的连接（服务端视角 = 假人发出），出站记录自动联动。
     *
     * @param payload 目标 payload 实例（AI 依赖 mod jar 构造）
     * @return true 已发送；false 未注册或假人连接不可用
     */
    boolean sendModPayload(CustomPacketPayload payload);
}

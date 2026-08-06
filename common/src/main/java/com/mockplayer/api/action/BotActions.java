package com.mockplayer.api.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Bot 行为原语。
 *
 * 持续状态方法（setForward/setStrafe/setSneak/setSprint/look/lookAt/jump）会保存状态并
 * 在每个 tick 应用到假人输入（写 fakePlayer.input / 设置朝向），直到调用 {@link #stop()} 重置。
 * 一次性动作（attack/mine/use 等）立即执行一次（走 fakePlayer.gameMode，复用原版客户端逻辑）。
 *
 * 所有方法必须在主线程调用。
 */
public interface BotActions {

    // ===== 持续状态（每 tick 应用，直至 stop()） =====

    /**
     * 设置前后移动（-1 后退 ~ 1 前进，0 停止）。
     *
     * @param value 移动幅度
     * @return this
     */
    BotActions setForward(float value);

    /**
     * 设置左右平移（-1 左 ~ 1 右，0 停止）。
     *
     * @param value 移动幅度
     * @return this
     */
    BotActions setStrafe(float value);

    /**
     * 设置潜行。
     *
     * @param sneaking true 潜行
     * @return this
     */
    BotActions setSneak(boolean sneaking);

    /**
     * 设置疾跑。
     *
     * @param sprinting true 疾跑
     * @return this
     */
    BotActions setSprint(boolean sprinting);

    /**
     * 设置朝向（yaw 水平，pitch 垂直，钳制 -90~90）。
     *
     * @param yaw   水平角
     * @param pitch 垂直角
     * @return this
     */
    BotActions look(float yaw, float pitch);

    /**
     * 看向世界坐标。
     *
     * @param position 目标坐标
     * @return this
     */
    BotActions lookAt(Vec3 position);

    /**
     * 看向实体。
     *
     * @param entity 目标实体
     * @return this
     */
    BotActions lookAt(Entity entity);

    /**
     * 相对转向（在当前朝向基础上叠加，用于巡逻/扫视）。
     *
     * @param yaw   水平角增量
     * @param pitch 垂直角增量（自动钳制 -90~90）
     * @return this
     */
    BotActions turn(float yaw, float pitch);

    /**
     * 持续跳跃（按住空格）。
     *
     * @return this
     */
    BotActions jump();

    /**
     * 持续攻击目标（每 tick 自动 attack 一次，目标死亡自动停止；配合 {@link #stopSustained()}）。
     *
     * @param target 攻击目标
     * @return this
     */
    BotActions sustainedAttack(Entity target);

    /**
     * 持续右键目标（每 tick 自动 interact 一次，目标死亡自动停止；配合 {@link #stopSustained()}）。
     *
     * @param target 交互目标
     * @return this
     */
    BotActions sustainedUse(Entity target);

    /**
     * 停止持续攻击/使用（sustainedAttack/sustainedUse）。
     *
     * @return this
     */
    BotActions stopSustained();

    /**
     * 停止所有持续状态（移动/潜行/疾跑/跳跃/持续攻击/持续使用归零）。
     *
     * @return this
     */
    BotActions stop();

    // ===== 一次性动作（立即执行一次） =====

    /**
     * 攻击实体（挥拳/武器）。
     *
     * @param target 目标实体
     */
    void attack(Entity target);

    /**
     * 左键戳刺（矛等穿刺武器 PIERCING_WEAPON 的近战戳刺：发 ServerboundPlayerActionPacket(STAB) +
     * 假人本地 onAttack/postPiercingAttack）。注意：原版 MultiPlayerGameMode.piercingAttack 内部用
     * 主玩家（this.minecraft.player），假人不能直接调——本方法照它逻辑写假人版。
     * 需要攻击蓄力满（MINIMUM_ATTACK_CHARGE）；普通 attack 对穿刺武器会被服务端 handleAttack 跳过。
     */
    void stab();

    /**
     * 右键交互实体（村民交易/喂食/骑乘/开门等）。
     *
     * @param target 目标实体
     */
    void interact(Entity target);

    /**
     * 左键打方块（一次破坏进度）。
     *
     * @param pos 方块位置
     */
    void attackBlock(BlockPos pos);

    /**
     * 开始挖掘方块（持续挖掘由 gameMode 自动累积进度并发 START/STOP 包）。
     *
     * @param pos 方块位置
     */
    void mineBlock(BlockPos pos);

    /**
     * 使用手中物品（吃/拉弓/扔/喝药水等）。
     *
     * @param hand 使用的手
     */
    void useItem(InteractionHand hand);

    /**
     * 右键交互方块（开箱/点门/放方块前的位置）。
     *
     * @param pos  方块位置
     * @param side 交互的面
     */
    void useItemOn(BlockPos pos, Direction side);

    /**
     * 放置方块（手持方块对准 pos 的 side 面放置；与 useItemOn 同通道，独立语义原语）。
     *
     * @param pos  相邻方块位置
     * @param side 放置的面
     */
    void placeBlock(BlockPos pos, Direction side);

    /**
     * 丢弃当前选中槽位的物品（1 个）。
     */
    void dropSelected();

    /**
     * 指定快捷栏槽位丢弃（1 个或整组）。
     *
     * @param slot    快捷栏槽位（0-8，越界钳制）
     * @param dropAll true 整组，false 1 个
     */
    void drop(int slot, boolean dropAll);

    /**
     * 交换主手/副手物品。
     */
    void swapHands();

    /**
     * 骑乘附近最近的坐骑（马/船/矿车；可选的只骑可骑乘实体）。
     *
     * @param onlyRideables true 只骑 Minecart/Boat/AbstractHorse，false 附近任意实体
     */
    void mount(boolean onlyRideables);

    /**
     * 骑乘附近最近的坐骑（只骑可骑乘实体）。
     */
    void mount();

    /**
     * 下马。
     */
    void dismount();
}

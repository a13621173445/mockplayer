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
     * 持续跳跃（按住空格）。
     *
     * @return this
     */
    BotActions jump();

    /**
     * 停止所有持续状态（移动/潜行/疾跑/跳跃归零）。
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
     * 丢弃当前选中槽位的物品（1 个）。
     */
    void dropSelected();

    /**
     * 交换主手/副手物品。
     */
    void swapHands();
}

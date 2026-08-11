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
 * 风格规则（P3-4）：持续状态 setter/开关返回 this（可链式）；一次性动作统一 void。
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
     * <p>不自动转向，需要追踪目标朝向时由外部调用者按 tick 调 {@link #lookAt(Entity)}。
     *
     * @param target 攻击目标
     * @return this
     */
    BotActions sustainedAttack(Entity target);

    /**
     * 持续右键目标（每 tick 自动 interact 一次，目标死亡自动停止；配合 {@link #stopSustained()}）。
     *
     * <p>不自动转向，需要追踪目标朝向时由外部调用者按 tick 调 {@link #lookAt(Entity)}。
     *
     * @param target 交互目标
     * @return this
     */
    BotActions sustainedUse(Entity target);

    /**
     * 沿假人视线射线执行一次原版左键动作（单点）：
     * 命中实体 → {@link #attack(Entity)}；命中方块 → 打一下（attackBlock 等价，
     * 不掉方块）；空 → 挥空。射线复用原版 pick（方块 4.5 / 实体 3.0，创造 +2）。
     */
    void attackLook();

    /**
     * 沿假人视线射线执行一次原版右键动作（单点）：
     * 命中实体 → {@link #interact(Entity)}；命中方块 → {@link #useItemOn(BlockPos, Direction)}；
     * 空 → {@link #useItem(InteractionHand)}。
     */
    void useLook();

    /**
     * 长按左键（每 tick 沿射线）：实体 → 持续攻击；方块 → 持续挖掘；
     * 配合 {@link #stopSustained()} 停止。
     *
     * @return this
     */
    BotActions sustainedAttackLook();

    /**
     * 长按右键（每 tick 沿射线）：实体 → 持续交互；方块 → 首次 useItemOn，
     * 之后持续 useItem（吃 / 拉弓 / 投掷的按住语义）；配合
     * {@link #stopSustained()} 停止。
     *
     * @return this
     */
    BotActions sustainedUseLook();

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

    /**
     * 连点左键开关：开启后主手蓄力满（attack strength 1.0）才攻击一次（原版攻击节奏）。
     * 类似疾跑的开关状态：不随 GUI 关闭停止，由 {@link #stop()} 停止。
     *
     * @param enabled true 开启连点
     * @return this
     */
    BotActions setRapidAttack(boolean enabled);

    /**
     * 连点右键开关：开启后每 20 tick（1 秒）使用一次。
     * 类似疾跑的开关状态：不随 GUI 关闭停止，由 {@link #stop()} 停止。
     *
     * @param enabled true 开启连点
     * @return this
     */
    BotActions setRapidUse(boolean enabled);

    // ===== 持续状态查询（GUI 状态面板/测试只读） =====

    /** 当前是否潜行。 */
    boolean isSneaking();

    /** 当前是否疾跑。 */
    boolean isSprinting();

    /** 当前是否持续跳跃。 */
    boolean isJumping();

    /** 当前是否正在持续挖掘方块。 */
    boolean isMining();

    /** 当前是否正在持续攻击（sustainedAttack / sustainedAttackLook）。 */
    boolean isSustainedAttacking();

    /** 当前是否正在持续使用（sustainedUse / sustainedUseLook）。 */
    boolean isSustainedUsing();

    /** 当前是否开启连点左键。 */
    boolean isRapidAttacking();

    /** 当前是否开启连点右键。 */
    boolean isRapidUsing();

    // ===== 一次性动作（立即执行一次） =====

    /**
     * 攻击实体（挥拳/武器）。
     *
     * <p>不改变假人朝向；需要先看向目标请调用 {@link #lookAt(Entity)}。
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
     * <p>不改变假人朝向；需要先看向目标请调用 {@link #lookAt(Entity)}。
     *
     * @param target 目标实体
     */
    void interact(Entity target);

    /**
     * 左键打方块（一次破坏进度）。
     *
     * <p>不改变假人朝向；需要先看向方块请调用 {@link #lookAt(Vec3)}。
     *
     * @param pos 方块位置
     */
    void attackBlock(BlockPos pos);

    /**
     * 开始挖掘方块（持续挖掘由 gameMode 自动累积进度并发 START/STOP 包）。
     *
     * <p>不改变假人朝向；需要先看向方块请调用 {@link #lookAt(Vec3)}。
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
     * 松开右键（结束使用物品）。长按场景：弓蓄满/提前放箭、投掷物抛出、盾牌解除格挡、
     * 吃食物提前取消——原版 releaseUsingItem 链路（发 RELEASE_USE_ITEM 包 + player.releaseUsingItem）。
     */
    void releaseUsingItem();

    /**
     * 右键交互方块（开箱/点门/放方块前的位置）。
     *
     * <p>不改变假人朝向；需要先看向方块请调用 {@link #lookAt(Vec3)}。
     *
     * @param pos  方块位置
     * @param side 交互的面
     */
    void useItemOn(BlockPos pos, Direction side);

    /**
     * 放置方块（手持方块对准 pos 的 side 面放置；与 useItemOn 同通道，独立语义原语）。
     *
     * <p>实际放置位置与原版 BlockPlaceContext 一致：点击可替换方块（空气/水等）→ 放 pos 本身；
     * 点击实心方块 → 放 pos.relative(side)。不改变假人朝向，需要先看向目标请调用 {@link #lookAt(Vec3)}。
     *
     * @param pos  相邻方块位置
     * @param side 放置的面
     */
    void placeBlock(BlockPos pos, Direction side);

    /**
     * 直接指定被放置方块位置：内部自动找最近的不可替换相邻方块作支撑，点击其朝向
     * target 的面完成放置（原语等价 placeBlock(支撑块, 反方向)）；找不到支撑块时
     * 不放置。
     *
     * <p>不改变假人朝向，需要先看向目标请调用 {@link #lookAt(Vec3)}。
     *
     * @param target 被放置方块的位置
     */
    void placeBlockAt(BlockPos target);

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
     * 物品栏选中槽位（原版快捷栏 0-8 切换）。
     */
    BotActions setSelectedSlot(int slot);

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
     * 骑乘指定实体（右键坐骑等价，服务端决定能否上马）。
     *
     * @param target 目标实体
     */
    void mount(Entity target);

    /**
     * 下马。
     */
    void dismount();

    // ===== GUI 操作直接发包（不走 Screen 按钮，包路由到假人 connection，零主玩家污染） =====

    /**
     * 发聊天消息（等价 ChatScreen 回车发送）。
     *
     * @param message 消息内容；以 "/" 开头会自动走命令包路径
     */
    void chat(String message);

    /**
     * 执行服务端命令（等价 ChatScreen 输入 "/命令" 回车）。
     */
    void sendCommand(String command);

    /**
     * 起床（等价 InBedChatScreen 起床按钮：发 STOP_SLEEPING）。
     */
    void wakeUp();

    /**
     * 重生（等价 DeathScreen 重生按钮：发 PERFORM_RESPAWN）。
     */
    void respawn();

    /**
     * 写书（等价 BookEditScreen 保存：发 EditBookPacket）。
     *
     * @param slot  书与笔所在快捷栏槽位
     * @param pages 各页内容
     * @param title 成书标题（写书并署名时为 present）
     */
    void editBook(int slot, java.util.List<String> pages, java.util.Optional<String> title);

    /**
     * 写告示牌（等价 SignEditScreen 完成：发 SignUpdatePacket）。
     *
     * @param pos         告示牌方块位置
     * @param isFrontText 正面/背面文本
     * @param lines       4 行文本
     */
    void editSign(net.minecraft.core.BlockPos pos, boolean isFrontText, String[] lines);

    /**
     * 设置信标效果（等价 BeaconScreen 确认：发 SetBeaconPacket）。
     */
    void setBeacon(java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> primary,
            java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> secondary);

    /**
     * 铁砧改名（等价 AnvilScreen 输入名字：发 RenameItemPacket）。
     */
    void renameItem(String name);

    /**
     * 中键取方块到主手（等价创造模式 pick block：发 PickItemFromBlockPacket）。
     */
    void pickItemFromBlock(net.minecraft.core.BlockPos pos, boolean includeData);
}

package com.mockplayer.session;

import com.mockplayer.api.action.BotActions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * BotActions 实现：持续状态写入假人 LocalPlayer.input（每 tick 由 BotImpl.tick 应用），
 * 一次性动作走假人 MultiPlayerGameMode（复用原版客户端逻辑，服务端反作弊合规）。
 */
public class BotActionsImpl implements BotActions {

    private final BotImpl bot;

    private float forward;
    private float strafe;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    /** 下马 shift 保持 tick 数：模拟原版「按住 Shift 下马，再松开」，到期自动复位 false。 */
    private int dismountShiftTicks;
    private Entity sustainedAttackTarget;
    private Entity sustainedUseTarget;
    /** 长按左键/右键的射线持续状态（沿视线，stopSustained 停止）。 */
    private boolean sustainedAttackLook;
    private boolean sustainedUseLook;
    /** 连点左键/右键开关（类似疾跑的开关状态：不随 GUI 关闭停止，stop() 停止）。 */
    private boolean rapidAttackLook;
    private boolean rapidUseLook;
    /** 连点右键 20 tick 计数（1 秒一次）。 */
    private int rapidUseTicks;
    /** 长按右键已交互过的方块（同一方块只 useItemOn 一次，之后持续 useItem）。 */
    private BlockPos sustainedUseLookBlock;
    private BlockPos miningPos;
    /** 矛戳刺节流（原版 missTime=10 tick ≈ 500ms）。 */
    private long lastStabMillis = -1000;

    public BotActionsImpl(BotImpl bot) {
        this.bot = bot;
    }

    @Override
    public BotActions setForward(float value) {
        this.forward = value;
        return this;
    }

    @Override
    public BotActions setStrafe(float value) {
        this.strafe = value;
        return this;
    }

    @Override
    public BotActions setSneak(boolean sneaking) {
        this.sneaking = sneaking;
        this.bot.fireOnSneakToggle(sneaking);
        return this;
    }

    @Override
    public BotActions setSprint(boolean sprinting) {
        this.sprinting = sprinting;
        this.bot.fireOnSprintToggle(sprinting);
        return this;
    }

    @Override
    public BotActions look(float yaw, float pitch) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.setYRot(yaw % 360.0F);
            player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        }
        return this;
    }

    @Override
    public BotActions lookAt(Vec3 position) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, position);
        }
        return this;
    }

    @Override
    public BotActions lookAt(Entity entity) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null && entity != null) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
        }
        return this;
    }

    @Override
    public BotActions turn(float yaw, float pitch) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.setYRot(player.getYRot() + yaw);
            player.setXRot(Mth.clamp(player.getXRot() + pitch, -90.0F, 90.0F));
        }
        return this;
    }

    /** 一次性朝向：交互前立即转向目标位置（不保存持续 look 状态，与持续移动输入无关）。 */
    private void facePos(Vec3 pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null && pos != null) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, pos);
        }
    }

    /** 一次性朝向实体（取其眼睛位置）。 */
    private void faceEntity(Entity target) {
        if (target != null) {
            this.facePos(target.getEyePosition());
        }
    }

    @Override
    public BotActions jump() {
        this.jumping = true;
        return this;
    }

    @Override
    public BotActions sustainedAttack(Entity target) {
        this.sustainedAttackTarget = target;
        return this;
    }

    @Override
    public BotActions sustainedUse(Entity target) {
        this.sustainedUseTarget = target;
        return this;
    }

    @Override
    public BotActions stopSustained() {
        this.sustainedAttackTarget = null;
        this.sustainedUseTarget = null;
        this.sustainedAttackLook = false;
        this.sustainedUseLook = false;
        this.sustainedUseLookBlock = null;
        this.stopMining();
        // 原版松开右键：正在使用物品（举盾/拉弓/吃东西）→ releaseUsingItem
        LocalPlayer usingPlayer = this.bot.getLocalPlayer();
        if (usingPlayer != null && usingPlayer.isUsingItem()) {
            this.releaseUsingItem();
        }
        return this;
    }

    @Override
    public BotActions stop() {
        this.forward = 0.0F;
        this.strafe = 0.0F;
        this.sneaking = false;
        this.sprinting = false;
        this.jumping = false;
        this.dismountShiftTicks = 0;
        this.sustainedAttackTarget = null;
        this.sustainedUseTarget = null;
        this.sustainedAttackLook = false;
        this.sustainedUseLook = false;
        this.rapidAttackLook = false;
        this.rapidUseLook = false;
        this.rapidUseTicks = 0;
        this.sustainedUseLookBlock = null;
        this.stopMining();
        return this;
    }

    @Override
    public BotActions setRapidAttack(boolean enabled) {
        this.rapidAttackLook = enabled;
        return this;
    }

    @Override
    public BotActions setRapidUse(boolean enabled) {
        this.rapidUseLook = enabled;
        if (!enabled) {
            this.rapidUseTicks = 0;
        }
        return this;
    }

    @Override
    public boolean isSneaking() {
        return this.sneaking || this.dismountShiftTicks > 0;
    }

    @Override
    public boolean isSprinting() {
        return this.sprinting;
    }

    @Override
    public boolean isJumping() {
        return this.jumping;
    }

    @Override
    public boolean isMining() {
        return this.miningPos != null;
    }

    @Override
    public boolean isSustainedAttacking() {
        return this.sustainedAttackTarget != null || this.sustainedAttackLook;
    }

    @Override
    public boolean isSustainedUsing() {
        return this.sustainedUseTarget != null || this.sustainedUseLook;
    }

    @Override
    public boolean isRapidAttacking() {
        return this.rapidAttackLook;
    }

    @Override
    public boolean isRapidUsing() {
        return this.rapidUseLook;
    }

    /** 取消持续挖掘：清目标 + 发 ABORT_DESTROY_BLOCK（原版松开左键等价）。 */
    private void stopMining() {
        if (this.miningPos != null && this.bot.getGameMode() != null) {
            this.bot.getGameMode().stopDestroyBlock();
        }
        this.miningPos = null;
    }

    /** 内部：每 tick 应用持续输入 + 持续挖掘（BotImpl.tick 调用）。
     *  26.2 输入模型：移动（xxa/zza）由 LocalPlayer.applyInput 从 input.getMoveVector() 读——
     *  Bot 用抽象 moveVector（Vec2 左右/前后）驱动，不写 keyPresses 的移动按键位（解耦：setForward 是"前进"动作，
     *  不是"按 W"）。jump/shift/sprint 原版从 keyPresses 位读（applyInput 的 jump / isSprintingDown / 潜行 shift），
     *  Bot 抽象动作驱动这三个输入位。 */
    void applyInput() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 移动：只用抽象 moveVector（x=左右=strafe, y=前后=forward），原版 applyInput 从它设 xxa/zza
        ((com.mockplayer.session.accessor.MockplayerClientInputAccessor) player.input)
                .mockplayer$setMoveVector(new Vec2(this.strafe, this.forward));
        // jump/shift/sprint：原版从 keyPresses 位读，Bot 抽象动作驱动输入位（不涉及移动按键）
        // shift 位 = 用户潜行状态 或 下马瞬间（dismount 置 2 tick，到期自动复位 false）
        boolean shift = this.sneaking || this.dismountShiftTicks > 0;
        if (this.dismountShiftTicks > 0) {
            this.dismountShiftTicks--;
        }
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, this.jumping, shift, this.sprinting);
        // 持续挖掘（原版按住左键：每 tick continueDestroyBlock）
        this.tickMining();
        // 持续攻击/使用：目标死亡/无效则自动停止
        if (this.sustainedAttackTarget != null) {
            if (this.sustainedAttackTarget.isAlive()) {
                this.attack(this.sustainedAttackTarget);
            } else {
                this.sustainedAttackTarget = null;
            }
        }
        if (this.sustainedUseTarget != null) {
            if (this.sustainedUseTarget.isAlive()) {
                this.interact(this.sustainedUseTarget);
            } else {
                this.sustainedUseTarget = null;
            }
        }
        // 长按左键（原版按住左键）：射线命中实体 → 持续攻击；方块 → 持续挖掘；空 → 挥空
        if (this.sustainedAttackLook) {
            // 持矛戳刺：原版按住左键每 10 tick（missTime）一次 STAB，不依赖射线
            if (player.getMainHandItem().has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)) {
                long now = net.minecraft.util.Util.getMillis();
                if (now - this.lastStabMillis >= 500L) {
                    this.lastStabMillis = now;
                    this.pierceStab();
                }
                return;
            }
            net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
            if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                if (entityHit.getEntity().isAlive()) {
                    this.attack(entityHit.getEntity());
                }
            } else if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                    && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                if (!blockHit.getBlockPos().equals(this.miningPos)) {
                    this.mineBlock(blockHit.getBlockPos());
                }
            } else {
                this.stopMining();
                player.swing(InteractionHand.MAIN_HAND);
            }
        }
        // 长按右键（原版按住右键）：实体 → 持续交互；方块 → 首次 useItemOn，之后持续 useItem
        if (this.sustainedUseLook) {
            net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
            if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                if (entityHit.getEntity().isAlive()) {
                    // 原版按住右键：interact 非 Success（如持矛）→ useItem（举矛蓄力）
                    if (!(this.interactResult(entityHit.getEntity())
                            instanceof net.minecraft.world.InteractionResult.Success)
                            && !player.isUsingItem()) {
                        this.useItemLikeVanilla();
                    }
                }
            } else if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                    && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                if (!blockHit.getBlockPos().equals(this.sustainedUseLookBlock)) {
                    this.sustainedUseLookBlock = blockHit.getBlockPos();
                    this.useItemOn(blockHit.getBlockPos(), blockHit.getDirection());
                }
                this.useItemLikeVanilla();
            } else {
                // 原版按住右键：未在使用时才 startUseItem（主手优先，副手 fallback 举盾）
                if (!player.isUsingItem()) {
                    this.useItemLikeVanilla();
                }
            }
        }
        // 连点左键：主手蓄力满（attack strength 1.0）才攻击一次（原版攻击节奏）
        if (this.rapidAttackLook && player.getAttackStrengthScale(0.0F) >= 1.0F) {
            this.attackLook();
        }
        // 连点右键：每 20 tick（1 秒）使用一次
        if (this.rapidUseLook) {
            if (++this.rapidUseTicks >= 20) {
                this.rapidUseTicks = 0;
                this.useLook();
            }
        }
    }

    @Override
    public void attack(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null) {
            return;
        }
        // 攻击前自动看向目标（用户拍板：所有实体交互先 lookAt）
        this.faceEntity(target);
        if (this.bot.getGameMode() != null) {
            this.bot.getGameMode().attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnAttackEntity(target);
    }

    @Override
    public void stab() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        net.minecraft.world.item.component.PiercingWeapon weapon =
                player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (weapon != null) {
            // 复用原版 MultiPlayerGameMode.piercingAttack（MixinMultiPlayerGameMode 把方法内
            // this.minecraft.player 换成假人），与 mod 扩展的原版戳刺逻辑保持一致。
            // 原版方法硬编码主玩家，假人不能直接调（污染主玩家），Mixin 替换作用对象后即可复用。
            this.bot.getGameMode().piercingAttack(weapon);
        }
    }

    /** 原版 startAttack 持矛分支：piercingAttack（发 STAB 包）+ swing。 */
    private void pierceStab() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        net.minecraft.world.item.component.PiercingWeapon weapon =
                player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (weapon != null) {
            this.bot.getGameMode().piercingAttack(weapon);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void interact(Entity target) {
        this.interactResult(target);
    }

    /** 右键实体（交易/喂食/骑乘等），返回原版分发结果（useLook 需判断 fallthrough）。 */
    private net.minecraft.world.InteractionResult interactResult(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // 交互前自动看向目标
        this.faceEntity(target);
        // 右键实体（村民交易/喂食/骑乘）：EntityHitResult 定位目标
        net.minecraft.world.phys.EntityHitResult hit = new net.minecraft.world.phys.EntityHitResult(target);
        net.minecraft.world.InteractionResult result =
                this.bot.getGameMode().interact(player, target, hit, InteractionHand.MAIN_HAND);
        // 与原版 Minecraft 右键分发一致：Success + swingSource==CLIENT → player.swing（本地动画 + ServerboundSwingPacket 广播）
        if (result instanceof net.minecraft.world.InteractionResult.Success success
                && success.swingSource() == net.minecraft.world.InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnInteractEntity(target);
        return result;
    }

    @Override
    public void attackLook() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 原版 Minecraft.startAttack 持矛分支：直接戳刺（STAB 包，不依赖射线命中）
        if (player.getMainHandItem().has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)) {
            this.pierceStab();
            return;
        }
        net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            this.attack(entityHit.getEntity());
        } else if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            // 原版单点左键方块 = 打一下（start+stop+swing，不掉方块）
            this.attackBlock(blockHit.getBlockPos());
        } else {
            player.swing(InteractionHand.MAIN_HAND); // 挥空
        }
    }

    @Override
    public void useLook() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            // 原版 startUseItem：interact 非 Success（如持矛右键实体）→ 继续 useItem（举矛）
            if (!(this.interactResult(entityHit.getEntity())
                    instanceof net.minecraft.world.InteractionResult.Success)) {
                this.useItemLikeVanilla();
            }
        } else if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            this.useItemOn(blockHit.getBlockPos(), blockHit.getDirection());
        } else {
            // 原版单点右键空气：主手优先，主手无使用动画自动 fallback 副手（如副手盾举盾）
            this.useItemLikeVanilla();
        }
    }

    @Override
    public BotActions sustainedAttackLook() {
        this.sustainedAttackLook = true;
        return this;
    }

    @Override
    public BotActions sustainedUseLook() {
        this.sustainedUseLook = true;
        return this;
    }

    /**
     * 原版等价射线（对齐 LocalPlayer.raycastHitResult / pick）：方块 clip +
     * 实体相交最近优先 + 作用距离过滤。返回 null 表示假人未就绪。
     */
    private net.minecraft.world.phys.HitResult pickLookTarget() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return null;
        }
        // 完全复用原版 LocalPlayer.raycastHitResult（含主手 AttackRange 组件、方块/实体
        // 最近优先与距离过滤）——不再自写射线，任何细微差异都会导致命中不一致
        return player.raycastHitResult(1.0F, player);
    }

    @Override
    public void attackBlock(BlockPos pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || pos == null || this.bot.getGameMode() == null) {
            return;
        }
        // 打方块前自动看向方块中心
        this.facePos(Vec3.atCenterOf(pos));
        // 26.2 无 attackBlock：用 startDestroyBlock + 立即 stopDestroyBlock 模拟一次左键点击
        this.bot.getGameMode().startDestroyBlock(pos, Direction.UP);
        this.bot.getGameMode().stopDestroyBlock();
        player.swing(InteractionHand.MAIN_HAND);
        this.bot.fireOnBreakBlock(pos);
    }

    @Override
    public void mineBlock(BlockPos pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || pos == null || this.bot.getGameMode() == null) {
            return;
        }
        // 挖掘前自动看向方块中心
        this.facePos(Vec3.atCenterOf(pos));
        // 原版等价距离判断：复用 Player.isWithinBlockInteractionRange（blockInteractionRange，生存约 4.5 格），
        // 与原版 Minecraft.startAttack 的 hitResult 射线范围一致；不做视线射线（方块在背后也能按坐标挖，方便使用）
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            return;
        }
        // 复用原版挖掘驱动（Minecraft.continueAttack）：
        // startDestroyBlock（按住第一帧，发 START_DESTROY_BLOCK）→ 之后由 tickMining 每 tick
        // continueDestroyBlock（原版按住期间每 tick 持续发 START + 本地裂纹）→ 服务端进度推进到 1.0
        // 破坏后假人 level 方块变 air → continueDestroyBlock 返回 false → stopDestroyBlock（松开）。
        // MultiPlayerGameMode 内部 this.minecraft.player/level 已被 Mixin @Redirect 换成假人（隔离）。
        this.miningPos = pos;
        this.bot.getGameMode().startDestroyBlock(pos, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** 内部：每 tick 持续挖掘（BotImpl.tick 调用）——复用原版 continueAttack 逐行逻辑：
     *  continueDestroyBlock（持续挖）+ addBreakingBlockEffect（裂纹）+ swing（持续挥动），服务端挖掉自动松开。 */
    void tickMining() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.miningPos == null || this.bot.getGameMode() == null) {
            return;
        }
        BlockPos pos = this.miningPos;
        // 持续挖掘每 tick 保持看向方块（玩家移动/转向后仍对准目标）
        this.facePos(Vec3.atCenterOf(pos));
        // 原版等价：玩家移动/目标离开交互范围后，continueAttack 的 hitResult 不再指向该方块 → 松开左键
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            this.stopMining();
            return;
        }
        // 原版 Minecraft.continueAttack：continueDestroyBlock → addBreakingBlockEffect（裂纹）→ swing（挥动）
        if (this.bot.getGameMode().continueDestroyBlock(pos, Direction.UP)) {
            if (player.level() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
                cl.addBreakingBlockEffect(pos, Direction.UP);
            }
            player.swing(InteractionHand.MAIN_HAND);
        } else {
            // 方块已被挖掉（假人 level 该位置变 air → continueDestroyBlock 返回 false）：松开 + 触发事件
            this.bot.getGameMode().stopDestroyBlock();
            this.miningPos = null;
            this.bot.fireOnBreakBlock(pos);
        }
    }

    @Override
    public void useItem(InteractionHand hand) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        // 原版 Minecraft.java:1743-1745：useItem 返回 Success + swingSource==CLIENT → swing（吃食物/投掷物/饮用药水等）
        InteractionResult result = this.bot.getGameMode().useItem(player, hand);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
        }
        this.bot.fireOnUseItem(hand, player.getItemInHand(hand));
    }

    /**
     * 原版 startUseItem 的空气/交互失败分支：主手优先，主手无使用动画（如剑）时
     * 自动 fallback 副手（如副手盾举盾），与原版按住右键行为一致。
     */
    public void useItemLikeVanilla() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).isEmpty()) {
                continue;
            }
            InteractionResult result = this.bot.getGameMode().useItem(player, hand);
            if (result instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    player.swing(hand);
                }
                this.bot.fireOnUseItem(hand, player.getItemInHand(hand));
                return;
            }
        }
    }

    @Override
    public void releaseUsingItem() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        // 复用原版 MultiPlayerGameMode.releaseUsingItem：发 RELEASE_USE_ITEM 包 + player.releaseUsingItem
        // （服务端触发弓放箭/投掷物抛出/盾牌解除格挡/食物提前取消）
        this.bot.getGameMode().releaseUsingItem(player);
    }

    @Override
    public void useItemOn(BlockPos pos, Direction side) {
        this.useItemOnFacing(pos, side, Vec3.atCenterOf(pos));
    }

    /** useItemOn 内部实现：可选朝向目标（placeBlock 传实际放置位置，useItemOn 传点击方块中心）。 */
    private void useItemOnFacing(BlockPos pos, Direction side, Vec3 faceTarget) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || pos == null) {
            return;
        }
        this.facePos(faceTarget);
        // 26.2：useItemOn(LocalPlayer, InteractionHand, BlockHitResult)
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), side != null ? side : Direction.UP, pos, false);
        InteractionResult result = this.bot.getGameMode().useItemOn(player, InteractionHand.MAIN_HAND, hit);
        // 与原版 Minecraft 右键分发一致：Success + swingSource==CLIENT → player.swing（本地动画 + ServerboundSwingPacket 广播）
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnInteractBlock(pos, side != null ? side : Direction.UP);
    }

    @Override
    public void placeBlock(BlockPos pos, Direction side) {
        LocalPlayer player = this.bot.getLocalPlayer();
        Direction face = side != null ? side : Direction.UP;
        // 实际放置位置与原版 BlockPlaceContext 一致：点击方块可替换（空气/水等）→ 放 pos 本身；
        // 点击实心方块 → 放 pos.relative(face)。朝向对准实际放置位置。
        BlockPos placeTarget = pos;
        if (player != null && !player.level().getBlockState(pos).canBeReplaced()) {
            placeTarget = pos.relative(face);
        }
        this.useItemOnFacing(pos, face, Vec3.atCenterOf(placeTarget));
        this.bot.fireOnPlaceBlock(pos);
    }

    @Override
    public void placeBlockAt(BlockPos target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return;
        }
        Level level = player.level();
        // 找支撑块：6 方向中不可替换（canBeReplaced=false，如实心方块）且离假人眼睛最近的相邻方块；
        // 点击它时 BlockPlaceContext.replaceClicked=false → 放置点 = 支撑块.relative(反方向) = target
        Vec3 eye = player.getEyePosition();
        BlockPos support = null;
        Direction supportSide = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.relative(dir);
            if (level.getBlockState(neighbor).canBeReplaced()) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(neighbor));
            if (d < bestDist) {
                bestDist = d;
                support = neighbor;
                supportSide = dir.getOpposite();
            }
        }
        if (support == null || supportSide == null) {
            return; // 无支撑块，不放置
        }
        this.useItemOnFacing(support, supportSide, Vec3.atCenterOf(target));
        this.bot.fireOnPlaceBlock(target);
    }

    @Override
    public void dropSelected() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        player.drop(false);
        this.bot.fireOnDropItem(player.getMainHandItem());
    }

    @Override
    public void drop(int slot, boolean dropAll) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 只能丢快捷栏当前选中槽（原版 Q/Ctrl+Q）；slot 越界钳制到 0-8
        int selected = Mth.clamp(slot, 0, Inventory.getSelectionSize() - 1);
        player.getInventory().setSelectedSlot(selected);
        player.drop(dropAll);
        this.bot.fireOnDropItem(player.getMainHandItem());
    }

    @Override
    public void swapHands() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
        this.bot.fireOnSwapHands();
    }

    @Override
    public BotActions setSelectedSlot(int slot) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return this;
        }
        int selected = Mth.clamp(slot, 0, net.minecraft.world.entity.player.Inventory.getSelectionSize() - 1);
        player.getInventory().setSelectedSlot(selected);
        // 原版快捷栏切换：本地更新后，向当前 LocalPlayer.connection 发包；这里必须是 bot connection。
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
        this.bot.fireOnHeldSlotChanged(selected);
        return this;
    }

    @Override
    public void mount(boolean onlyRideables) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || this.bot.getLevel() == null) {
            return;
        }
        List<Entity> entities;
        if (onlyRideables) {
            entities = this.bot.getLevel().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D),
                    e -> e instanceof Minecart || e instanceof Boat || e instanceof AbstractHorse);
        } else {
            entities = this.bot.getLevel().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D));
        }
        Entity closest = entities.stream()
                .filter(e -> e != player && e.isAlive() && e.getVehicle() != player)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
        if (closest == null) {
            return;
        }
        this.mount(closest);
    }

    @Override
    public void mount() {
        this.mount(true);
    }

    @Override
    public void mount(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || target == null) {
            return;
        }
        // 客户端骑乘：右键坐骑走 ServerboundInteractPacket，服务端处理上马
        this.bot.getGameMode().interact(player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
    }

    @Override
    public void dismount() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 原版等价下马（2026-08-08 修复，对照 26.2 反编译源码）：
        // 真玩家按 Shift 下马的完整链路是：
        //   客户端 input.shift=true → LocalPlayer.tick 检测输入变化发 ServerboundPlayerInputPacket
        //   → 服务端 handlePlayerInput 置 shiftKeyDown
        //   → ServerPlayer.rideTick() 检测 wantsToStopRiding()（= isShiftKeyDown）→ stopRiding()
        //   → 服务端广播 SetPassengers，客户端本地脱离。
        // 实现：置 dismountShiftTicks=10，applyInput 写 keyPresses.shift=true 并保持 10 tick，
        // 到期自动写回 false（LocalPlayer.tick 检测变化再发复位包）——等价于「按住 Shift 再松开」。
        // 保持时间不能太短：true 和 false 复位包若被服务端同一 tick 处理，rideTick 检查时
        // shift 已是 false 会漏掉下马（间歇性「下不来」的竞态），10 tick = 0.5 秒足够稳定。
        // 注意不能只发一次 shift=true：BotActionsImpl 输入驱动从不主动产生 shift=false 变化，
        // 服务端会永久潜行，导致后续右键箱子/门被抑制（isSecondaryUseActive）。
        // 之前只调 removeVehicle() 只改本地引用不发包，服务端不知道下马，矿车/船/马都下不来。
        this.dismountShiftTicks = 10;
        // 本地立即脱离（无头客户端不等渲染层 SetPassengers 回包）：
        // 下一个动作可能立刻执行（如打开容器），骑乘状态会挡住交互，所以同步清本地引用。
        // 服务端下马仍由上面的 shift 输入包驱动，二者不冲突。
        player.removeVehicle();
    }

    // ===== GUI 操作直接发包（不走 Screen 按钮，包路由到假人 connection，零主玩家污染） =====

    /** 假人 connection 发包（FakeLocalPlayer.connection = 假人 ClientPacketListener → 假人 TCP） */
    private net.minecraft.client.multiplayer.ClientPacketListener conn() {
        LocalPlayer player = this.bot.getLocalPlayer();
        return player != null ? player.connection : null;
    }

    @Override
    public BotActions chat(String message) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        // 原版等价过滤：StringUtil.filterText 移除 §(167) 等非法聊天字符，
        // 服务端 tryHandleChat 对含 § 的消息直接 disconnect(illegal_characters) 踢人。
        String filtered = net.minecraft.util.StringUtil.filterText(message);
        if (filtered.isEmpty()) {
            return this;
        }
        if (filtered.startsWith("/")) {
            return this.sendCommand(filtered.substring(1));
        }
        // offline 服务器不要求签名（signature=null）+ 空 lastSeen（原版无历史消息时等同）
        c.send(new net.minecraft.network.protocol.game.ServerboundChatPacket(
                filtered, java.time.Instant.now(), net.minecraft.util.Crypt.SaltSupplier.getLong(), null,
                new net.minecraft.network.chat.LastSeenMessages.Update(0, new java.util.BitSet(), (byte) 0)));
        return this;
    }

    @Override
    public BotActions sendCommand(String command) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        // 命令通道同样走 tryHandleChat 校验：含 § 会被踢，先过滤
        String filtered = net.minecraft.util.StringUtil.filterText(command);
        if (filtered.isEmpty()) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(filtered));
        return this;
    }

    @Override
    public BotActions wakeUp() {
        LocalPlayer player = this.bot.getLocalPlayer();
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (player == null || c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
                player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        return this;
    }

    @Override
    public BotActions respawn() {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return this;
    }

    @Override
    public BotActions editBook(int slot, java.util.List<String> pages, java.util.Optional<String> title) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundEditBookPacket(slot, pages, title));
        return this;
    }

    @Override
    public BotActions editSign(net.minecraft.core.BlockPos pos, boolean isFrontText, String[] lines) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null || lines == null || lines.length < 4) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundSignUpdatePacket(
                pos, isFrontText, lines[0], lines[1], lines[2], lines[3]));
        return this;
    }

    @Override
    public BotActions setBeacon(
            java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> primary,
            java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> secondary) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundSetBeaconPacket(primary, secondary));
        return this;
    }

    @Override
    public BotActions renameItem(String name) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundRenameItemPacket(name));
        return this;
    }

    @Override
    public BotActions pickItemFromBlock(net.minecraft.core.BlockPos pos, boolean includeData) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return this;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket(pos, includeData));
        return this;
    }
}

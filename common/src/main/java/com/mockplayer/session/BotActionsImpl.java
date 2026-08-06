package com.mockplayer.session;

import com.mockplayer.api.action.BotActions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
    public BotActions jump() {
        this.jumping = true;
        return this;
    }

    @Override
    public BotActions stop() {
        this.forward = 0.0F;
        this.strafe = 0.0F;
        this.sneaking = false;
        this.sprinting = false;
        this.jumping = false;
        return this;
    }

    /** 内部：每 tick 应用持续输入（BotImpl.tick 调用）。
     *  26.2 输入模型：ClientInput.keyPresses 是 Input record（forward/backward/left/right/jump/shift/sprint），
     *  LocalPlayer.applyInput 每 tick 消费它 → travel 驱动移动/跳跃/潜行。 */
    void applyInput() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                this.forward > 0, this.forward < 0, this.strafe < 0, this.strafe > 0,
                this.jumping, this.sneaking, this.sprinting);
    }

    @Override
    public void attack(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null) {
            return;
        }
        if (this.bot.getGameMode() != null) {
            this.bot.getGameMode().attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnAttackEntity(target);
    }

    @Override
    public void interact(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return;
        }
        // 右键实体（村民交易/喂食/骑乘）：EntityHitResult 定位目标
        net.minecraft.world.phys.EntityHitResult hit = new net.minecraft.world.phys.EntityHitResult(target);
        this.bot.getGameMode().interact(player, target, hit, InteractionHand.MAIN_HAND);
        this.bot.fireOnInteractEntity(target);
    }

    @Override
    public void attackBlock(BlockPos pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || pos == null || this.bot.getGameMode() == null) {
            return;
        }
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
        this.bot.getGameMode().startDestroyBlock(pos, Direction.UP);
    }

    @Override
    public void useItem(InteractionHand hand) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        this.bot.getGameMode().useItem(player, hand);
        this.bot.fireOnUseItem(hand, player.getItemInHand(hand));
    }

    @Override
    public void useItemOn(BlockPos pos, Direction side) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || pos == null) {
            return;
        }
        // 26.2：useItemOn(LocalPlayer, InteractionHand, BlockHitResult)
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), side != null ? side : Direction.UP, pos, false);
        this.bot.getGameMode().useItemOn(player, InteractionHand.MAIN_HAND, hit);
        this.bot.fireOnInteractBlock(pos, side != null ? side : Direction.UP);
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
}

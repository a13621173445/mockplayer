package com.mockplayer.mixin;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 假人跨维无敌窗口绕过（26.1.2 修复）。
 *
 * 26.1.2 的 ServerPlayer.isInvulnerableTo 在 isChangingDimension()（跨维中，直到客户端
 * 回 ServerboundAcceptTeleportation 确认位置）或 !hasClientLoaded() 时返回无敌，
 * 且 generic_kill（kill 命令）没有例外——跨维尾巴里 kill 的 MAX 伤害被挡，死亡包
 * 不发、onDeath 事件丢失（26.2 同样代码但客户端确认时序不同，实测稳定）。
 *
 * 假人是无头会话（无渲染、不弹死亡界面），不存在「传送门落点伤害」等需要跨维
 * 无敌保护的场景；跨维中保持可受伤，与真玩家被 /kill 的语义一致。
 * 假人判定：服务端实体名在 SessionManager 有会话（同一 JVM 的单机/局域网服务器）。
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer {

    /** 诊断：tracking 半径变化时打印一次。 */
    private int mockplayer$lastTrackingRadius = -2;

    @Redirect(
            method = "isInvulnerableTo",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;isChangingDimension()Z"
            )
    )
    private boolean mockplayer$noCrossDimensionInvulnForFake(ServerPlayer instance) {
        if (com.mockplayer.session.SessionManager.getInstance()
                .getSession(instance.getPlainTextName()) != null) {
            // 假人跨维中：返回 false 绕过 isChangingDimension 无敌分支（generic_kill 无例外，
            // kill 撞上跨维尾巴会被挡 → 死亡包不发 → onDeath 丢失）。
            return false;
        }
        return instance.isChangingDimension();
    }

    /**
     * 假人每 tick 同步服务端 chunk 跟踪视距：原版 updateChunkTracking 只在玩家移动
     * （区块变化）时调用，假人静止时改 viewDistance（chunkRadius 命令）不会触发服务端
     * 发新 chunk。每 tick 强制检查（tracking 与视距一致时内部直接 return，零开销），
     * 等价于原版玩家移动时的同步时机；假人无渲染，无传送门/加载场景副作用。
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void mockplayer$syncChunkTracking(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (com.mockplayer.session.SessionManager.getInstance()
                .getSession(self.getPlainTextName()) != null) {
            int r = self.getChunkTrackingView()
                    instanceof net.minecraft.server.level.ChunkTrackingView.Positioned p ? p.viewDistance() : -1;
            if (r != this.mockplayer$lastTrackingRadius) {
                this.mockplayer$lastTrackingRadius = r;
                com.mockplayer.session.FakeSession.LOG.info(
                        "[fake][diag] tracking name={} radius={} requested={} pos={} dim={}",
                        self.getPlainTextName(), r, self.requestedViewDistance(),
                        self.chunkPosition(), self.level().dimension());
            }
            ((com.mockplayer.session.accessor.MockplayerChunkMapAccessor)
                            self.level().getChunkSource().chunkMap)
                    .mockplayer$updateChunkTracking(self);
        }
    }
}



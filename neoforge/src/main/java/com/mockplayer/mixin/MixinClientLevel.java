package com.mockplayer.mixin;

import com.mockplayer.session.SessionManager;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 假人 level 的粒子隔离：假人 level.addParticle 默认经主玩家 Minecraft.particleEngine
 * 渲染（AGENTS 已知污染点），这里把假人粒子记录到对应假人 state，不加入主玩家屏幕，
 * 杜绝「只有假人存在时主玩家挖方块粒子重复叠加」。
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void mockplayer$redirectFakeParticle(ParticleOptions particle, boolean overrideLimiter,
                                                 boolean alwaysShow, double x, double y, double z,
                                                 double xd, double yd, double zd, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (com.mockplayer.session.FakeLevelRegistry.isFakeLevel(self)) {
            SessionManager.recordFakeParticle(self, particle, x, y, z);
            ci.cancel();
        }
    }

    /**
     * 方块破坏粒子（服务端 2001 level event → addDestroyBlockEffect）不走 addParticle，
     * 而是直接往主玩家 particleEngine 塞 TerrainParticle——这是「只有假人存在时
     * 主玩家挖方块粒子重复叠加」的真凶，必须同样拦截记录。
     */
    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void mockplayer$redirectFakeDestroyEffect(BlockPos pos, BlockState blockState, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (com.mockplayer.session.FakeLevelRegistry.isFakeLevel(self)) {
            SessionManager.recordFakeParticle(self, new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            ci.cancel();
        }
    }

    /** 方块破坏音效（2001 level event → playLocalSound）同样只记录，不播主玩家 SoundManager。 */
    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V",
            at = @At("HEAD"), cancellable = true)
    private void mockplayer$redirectFakeLocalSound(double x, double y, double z,
                                                   SoundEvent sound, SoundSource source,
                                                   float volume, float pitch, boolean distanceDelay,
                                                   CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (com.mockplayer.session.FakeLevelRegistry.isFakeLevel(self)) {
            SessionManager.recordFakeSound(self, sound.location().toString(), x, y, z);
            ci.cancel();
        }
    }
}

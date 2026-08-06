package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakeSession;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让 MultiPlayerGameMode.piercingAttack（矛左键戳刺）对假人复用原版逻辑。
 *
 * 原版 piercingAttack 内部用 this.minecraft.player（主玩家）做 onAttack/postPiercingAttack/makeSound——
 * 假人 gameMode 调它会把攻击打到主玩家（污染）。@Redirect 把方法内 Minecraft.player 字段访问换成假人
 * （按连接判假人），其余逻辑（发 ServerboundPlayerActionPacket(STAB) + 服务端 PiercingWeapon.attack）
 * 完全复用原版——mod 扩展戳刺逻辑假人也继承，不自己写一份。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {

    @Shadow
    private ClientPacketListener connection;

    @Redirect(
            method = "piercingAttack",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;")
    )
    private LocalPlayer mockplayer$useFakePlayer(Minecraft mc) {
        if (FakeConnectionRegistry.isFake(this.connection.getConnection())) {
            FakeSession session = FakeConnectionRegistry.getSession(this.connection.getConnection());
            if (session != null && session.getFakePlayer() != null) {
                return session.getFakePlayer();
            }
        }
        return mc.player;
    }
}

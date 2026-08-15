package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakeSession;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * 让假人 gameMode 操作（挖矿/放置/攻击/交互/使用物品）复用原版逻辑但作用对象是假人，不污染主玩家。
 *
 * 原版 MultiPlayerGameMode 内部硬编码 this.minecraft.player / this.minecraft.level（主玩家）——假人
 * gameMode（fakeGameMode）调它会把操作打到主玩家（污染 + 依赖主玩家视角）。@ModifyExpressionValue
 * 把方法内 Minecraft.player / Minecraft.level 字段访问换成假人（按连接判假人），其余逻辑（发包/
 * 服务端处理/预测）完全复用原版——mod 扩展（如 PiercingWeapon 戳刺）假人也继承，不自己写一份。
 * 主玩家连接不受影响（isFake 判断只在假人连接时替换）。
 *
 * 用 @ModifyExpressionValue 而非 @Redirect：同一指令可多 mod 共存（handler 链），不与应用冲突
 * （@Redirect 同一指令只允许一个，第二个应用直接炸）。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {

    @Shadow
    private ClientPacketListener connection;

    private boolean mockplayer$isFake() {
        return FakeConnectionRegistry.isFake(this.connection.getConnection());
    }

    private FakeSession mockplayer$session() {
        return FakeConnectionRegistry.getSession(this.connection.getConnection());
    }

    @ModifyExpressionValue(
            method = {
                    "continueDestroyBlock", "destroyBlock", "ensureHasSentCarriedItem", "handleCreativeModeItemAdd",
                    "handleCreativeModeItemDrop", "isServerControlledInventory", "piercingAttack",
                    "setLocalMode", "startDestroyBlock", "stopDestroyBlock", "tick",
                    // P0-1：私有方法/合成 lambda 同样读主玩家 player，必须一并替换
                    "sameDestroyTarget", "performUseItemOn",
                    "lambda$startDestroyBlock$1", "lambda$useItem$0"
            },
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;")
    )
    private LocalPlayer mockplayer$useFakePlayer(LocalPlayer original) {
        if (this.mockplayer$isFake()) {
            FakeSession session = this.mockplayer$session();
            if (session != null && session.getFakePlayer() != null) {
                return session.getFakePlayer();
            }
        }
        return original;
    }

    @ModifyExpressionValue(
            method = {
                    "continueDestroyBlock", "destroyBlock",
                    "startDestroyBlock", "stopDestroyBlock", "useItem", "useItemOn",
                    // P0-1：私有方法/合成 lambda 同样读主玩家 level，必须一并替换
                    "performUseItemOn", "lambda$startDestroyBlock$1", "lambda$useItem$0"
            },
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;")
    )
    private ClientLevel mockplayer$useFakeLevel(ClientLevel original) {
        if (this.mockplayer$isFake()) {
            FakeSession session = this.mockplayer$session();
            if (session != null) {
                ClientLevel fakeLevel = session.getFakeLevel();
                if (fakeLevel != null) {
                    return fakeLevel;
                }
            }
        }
        return original;
    }
}

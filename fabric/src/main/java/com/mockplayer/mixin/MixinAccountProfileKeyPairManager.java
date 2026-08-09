package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.AccountProfileKeyPairManager;
import net.minecraft.world.entity.player.ProfileKeyPair;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 离线用户短路聊天 key 获取（消除启动/连接时的 Yggdrasil 401 延迟）。
 *
 * 原版 AccountProfileKeyPairManager.readOrFetchProfileKeyPair 无论离线还是正版
 * 都会调 userApiService.getKeyPair()；离线启动（accessToken == "0"）注定 401，
 * 网络差时能卡 75s+。离线用户直接返回空 key（本来也拿不到），正版走原逻辑。
 */
@Mixin(AccountProfileKeyPairManager.class)
public abstract class MixinAccountProfileKeyPairManager {

    @Inject(method = "readOrFetchProfileKeyPair", at = @At("HEAD"), cancellable = true)
    private void mockplayer$skipOfflineKeyFetch(
            Optional<ProfileKeyPair> cachedKeyPair,
            CallbackInfoReturnable<CompletableFuture<Optional<ProfileKeyPair>>> cir) {
        if (isOfflineUser()) {
            cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
        }
    }

    /** 离线启动特征：accessToken 很短（"0"/"FabricMC" 等 dev 值），正版 JWT 数百字符。 */
    private static boolean isOfflineUser() {
        String token = Minecraft.getInstance().getUser().getAccessToken();
        return token == null || token.isEmpty() || token.length() < 32;
    }
}

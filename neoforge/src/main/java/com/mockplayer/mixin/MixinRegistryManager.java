package com.mockplayer.mixin;

import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.RegistryManager;

import com.mockplayer.session.FakeConnectionRegistry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 假人配置阶段跳过 neoforge RegistryManager.applySnapshot。
 *
 * neoforge 服务端对假人（TCP）发 FrozenRegistrySyncCompletedPayload，客户端
 * handle(FrozenRegistrySyncCompletedPayload) → RegistryManager.applySnapshot 会把
 * BuiltInRegistries.BLOCK 的 tags 覆盖成服务端 snapshot（基础态 16），破坏假人本地完整
 * block tags（395）→ 原版配置阶段数据包加载缺 tag（infiniburn_overworld 等）→ Registry
 * Loading 崩。主玩家（内存连接）不走 neoforge 网络同步不受影响。假人 registry 用主玩家的
 * （已含全部数据包条目 + neoforge 特有条目），跳过 snapshot 最干净。
 */
@Mixin(RegistryManager.class)
public abstract class MixinRegistryManager {

    @Inject(method = "applySnapshot(Ljava/util/Map;Z)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void mockplayer$skipFakeSnapshot(CallbackInfoReturnable<Set<ResourceKey<?>>> cir) {
        if (FakeConnectionRegistry.isConfiguringFake()) {
            cir.setReturnValue(Set.of());
        }
    }
}

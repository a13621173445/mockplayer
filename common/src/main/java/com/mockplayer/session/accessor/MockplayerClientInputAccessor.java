package com.mockplayer.session.accessor;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 ClientInput 的 protected moveVector 字段。
 *
 * LocalPlayer.applyInput 用 input.getMoveVector()（moveVector）算移动（xxa/zza）；
 * keyPresses 只管 sprint/jump 判断。BotActionsImpl.applyInput 只写 keyPresses 会导致
 * 假人 moveVector 恒 ZERO → 假人 setForward 不移动（实测 bug）。设 moveVector 修复。
 */
@Mixin(ClientInput.class)
public interface MockplayerClientInputAccessor {

    @Accessor("moveVector")
    void mockplayer$setMoveVector(Vec2 moveVector);

    @Accessor("moveVector")
    Vec2 mockplayer$getMoveVector();
}

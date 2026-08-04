package com.mockplayer.session.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 MultiPlayerGameMode 的 private 游戏模式字段。
 *
 * MultiPlayerGameMode.setLocalMode(GameType) 内部硬编码 this.minecraft.player.getAbilities()
 * 会污染主玩家（假人调用会把主玩家改成生存）。假人需要记录自己的 gameType 时，
 * 直接通过本 accessor 写字段，不触发 setLocalMode 的全局副作用。
 */
@Mixin(MultiPlayerGameMode.class)
public interface MockplayerMultiPlayerGameModeAccessor {

    @Accessor("localPlayerMode")
    GameType mockplayer$getLocalPlayerMode();

    @Accessor("localPlayerMode")
    void mockplayer$setLocalPlayerMode(GameType mode);

    @Accessor("previousLocalPlayerMode")
    GameType mockplayer$getPreviousLocalPlayerMode();

    @Accessor("previousLocalPlayerMode")
    void mockplayer$setPreviousLocalPlayerMode(GameType mode);
}

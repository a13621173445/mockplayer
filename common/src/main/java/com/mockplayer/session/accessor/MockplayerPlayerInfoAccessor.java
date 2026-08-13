package com.mockplayer.session.accessor;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 PlayerInfo 的 protected 更新方法，供 FakePlayListener 更新假人 tab 列表用。
 *
 * 原版 ClientPacketListener.applyPlayerInfoUpdate 在 UPDATE_GAME_MODE / UPDATE_LATENCY
 * 时调 info.setGameMode / info.setLatency（protected，跨包不可访问）；假人 override
 * 该逻辑（不调主玩家 socialManager/player），需要 accessor 写同一字段。
 * 纯加法，不影响主玩家逻辑。
 */
@Mixin(PlayerInfo.class)
public interface MockplayerPlayerInfoAccessor {

    @Accessor("gameMode")
    void mockplayer$setGameMode(GameType gameMode);

    @Accessor("latency")
    void mockplayer$setLatency(int latency);
}

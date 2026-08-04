package com.mockplayer.session;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatAbilities;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;

/**
 * 假人自己的 LocalPlayer：复用 MC 完整物理（重力/移动/碰撞/击退），
 * 但覆盖 isControlledCamera 始终返回 true——
 * MC 默认只给「相机跟随者」算物理发包，假人无相机，必须强制走物理+发包，
 * 反作弊才能看到假人在正常移动（不悬空、不瞬移）。
 *
 * 额外保存假人自己的游戏模式（fakeGameType）：
 * 原版 LocalPlayer.aiStep 里 2 处 `this.minecraft.gameMode.isSpectator()`
 * 会读主玩家的游戏模式，平台 Mixin（MixinLocalPlayer @Redirect）对假人
 * 改用本方法判断，彻底与主玩家解耦。
 *
 * 物理在客户端算（LocalPlayer.tick → super.tick → aiStep），发移动包到假人连接。
 */
public class FakeLocalPlayer extends LocalPlayer {

    /** 假人自己的游戏模式（由 FakePlayListener 在 handleLogin/handleGameEvent/handleRespawn 时同步） */
    private volatile GameType fakeGameType = GameType.DEFAULT_MODE;

    public FakeLocalPlayer(
            Minecraft minecraft,
            ClientLevel level,
            ClientPacketListener connection,
            StatsCounter stats,
            ClientRecipeBook recipeBook,
            Input lastSentInput,
            boolean wasSprinting,
            ChatAbilities chatAbilities
    ) {
        super(minecraft, level, connection, stats, recipeBook, lastSentInput, wasSprinting, chatAbilities);
    }

    /** 同步假人自己的游戏模式（FakePlayListener 调用） */
    public void setFakeGameType(GameType type) {
        this.fakeGameType = type;
    }

    public GameType getFakeGameType() {
        return this.fakeGameType;
    }

    /** 假人是否旁观者（用假人自己的 gameMode，不读主玩家） */
    public boolean isFakeSpectator() {
        return this.fakeGameType == GameType.SPECTATOR;
    }

    /**
     * 假人无相机，强制视为受控相机 → LocalPlayer.tick 里物理正常算、移动包正常发。
     */
    @Override
    protected boolean isControlledCamera() {
        return true;
    }
}

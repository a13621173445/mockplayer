package com.mockplayer.session;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatAbilities;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Input;

/**
 * 假人自己的 LocalPlayer：复用 MC 完整物理（重力/移动/碰撞/击退），
 * 但覆盖 isControlledCamera 始终返回 true——
 * MC 默认只给「相机跟随者」算物理发包，假人无相机，必须强制走物理+发包，
 * 反作弊才能看到假人在正常移动（不悬空、不瞬移）。
 *
 * 物理在客户端算（LocalPlayer.tick → super.tick → aiStep），发移动包到假人连接。
 */
public class FakeLocalPlayer extends LocalPlayer {

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

    /**
     * 假人无相机，强制视为受控相机 → LocalPlayer.tick 里物理正常算、移动包正常发。
     */
    @Override
    protected boolean isControlledCamera() {
        return true;
    }
}

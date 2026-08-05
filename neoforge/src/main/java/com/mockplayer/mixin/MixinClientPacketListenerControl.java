package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLowDiskSpaceWarningPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

import com.mockplayer.session.ControlIsolation;
import com.mockplayer.session.ControlManager;
import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakePlayerState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * /control 期间主玩家 listener 的完整隔离。
 *
 * 主玩家 connection 的原版 ClientPacketListener 收到服务端包后，handler 直接读 Minecraft.player/level/gameMode
 * （反编译扫描确认），control 期间 mc.player=bot → 主玩家的血量/位置/经验/能力/背包包会污染 bot。本 Mixin：
 * - @Redirect 把数据类 handler 里的全局引用换成 mainPlayer/mainLevel（数据照原版逻辑应用到主玩家那套）。
 *   Mixin 的 @Redirect 不支持 method 通配/逗号/`(...)`，只能逐个精确方法名，故每个数据类 handler 一个 @Redirect。
 * - @Inject 隔离显示类（弹屏/Boss/Tab/标题/死亡界面/聊天/音效/粒子）——主玩家挂机 = 无头假人，不弹 UI 不播音效不渲染。
 * - handleRespawn/handleGameEvent/聊天 走 ControlIsolation（完整迁移/记录，绝不碰 mc.setLevel/mc.player）。
 *
 * 条件 isMainControlled()：control 进行中 + 本 listener 是主玩家（假人 listener 的连接是 fake）。
 * 非 control 时所有注入返回原值/不生效 → 对正常游戏零影响。
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerControl {

    /** 是否「正在控制假人 + 本 listener 属于主玩家」 */
    private boolean mockplayer$isMainControlled() {
        return ControlManager.isControlling()
                && !FakeConnectionRegistry.isFake(((ClientPacketListener) (Object) this).getConnection());
    }

    // ===== ① @Redirect Minecraft.player → mainPlayer（数据类 handler，字节码扫描确认读 mc.player） =====

    @Redirect(method = "handleSetHealth", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerSetHealth(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleSetExperience", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerSetExperience(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handlePlayerAbilities", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerAbilities(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleSetHeldSlot", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerSetHeldSlot(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleItemCooldown", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerItemCooldown(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleContainerContent", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerContainerContent(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleContainerSetSlot", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerContainerSetSlot(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleSetCursorItem", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerSetCursor(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleSetPlayerInventory", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerSetPlayerInventory(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleContainerSetData", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerContainerSetData(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleContainerClose", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerContainerClose(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleRecipeBookAdd", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerRecipeAdd(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleRecipeBookRemove", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerRecipeRemove(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleRecipeBookSettings", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerRecipeSettings(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handlePlaceRecipe", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerPlaceRecipe(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleMovePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerMovePlayer(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleRotatePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerRotatePlayer(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleSetEntityPassengersPacket", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerPassengers(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleMoveVehicle", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerMoveVehicle(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleLookAt", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerLookAt(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleTakeItemEntity", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerTakeItem(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleTeleportEntity", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerTeleport(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    @Redirect(method = "handleEntityPositionSync", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer mockplayer$mainPlayerEntitySync(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainPlayer() : mc.player;
    }

    // ===== ② @Redirect Minecraft.level → mainLevel（读 mc.level 的数据类 handler） =====

    @Redirect(method = "handleMovePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelMovePlayer(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleSetSpawn", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelSetSpawn(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleTickingState", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelTickingState(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleTickingStep", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelTickingStep(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleMapItemData", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelMapData(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleBlockDestruction", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelBlockDestruction(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleBlockEntityData", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelBlockEntityData(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    @Redirect(method = "handleBlockEvent", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel mockplayer$mainLevelBlockEvent(Minecraft mc) {
        return mockplayer$isMainControlled() ? ControlManager.getMainLevel() : mc.level;
    }

    // ===== ③ 聊天：主玩家 listener 走原版（ChatListener 推进消息 index，否则断线）；显示到 bot 视角聊天栏 =====

    // ===== ④ 完整隔离：游戏事件/统计/死亡/重生 =====

    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateGameEvent(ClientboundGameEventPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlIsolation.applyGameEvent(packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleAwardStats", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlIsolation.applyStats(packet);
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlIsolation.recordCombatKill(packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlIsolation.handleRespawn((ClientPacketListener) (Object) this, packet);
            ci.cancel();
        }
    }

    // ===== ⑤ 显示类：主玩家挂机不弹 UI（一行 cancel） =====

    @Inject(method = "handleTitlesClear", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateTitles(ClientboundClearTitlesPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCamera", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateCamera(ClientboundSetCameraPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolatePlayerInfo(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerInfoRemove", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolatePlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTabListCustomisation", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateTabList(ClientboundTabListPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleBossUpdate", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateBoss(ClientboundBossEventPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUpdateAdvancementsPacket", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateAdvancements(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSelectAdvancementsTab", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateAdvancementTab(ClientboundSelectAdvancementsTabPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMountScreenOpen", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateMountScreen(ClientboundMountScreenOpenPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleOpenSignEditor", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateSignEditor(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleOpenBook", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateOpenBook(ClientboundOpenBookPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMerchantOffers", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateMerchant(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleCustomChatCompletions", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateChatCompletions(ClientboundCustomChatCompletionsPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleLowDiskSpaceWarning", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateDiskWarning(ClientboundLowDiskSpaceWarningPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ci.cancel();
        }
    }

    // ===== ⑥ 音效/粒子：记录到主玩家 state，不播不渲染 =====

    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            FakePlayerState state = ControlManager.getMainState();
            state.recordSound(packet.getSound().value().location().toString(), packet.getX(), packet.getY(), packet.getZ());
            ci.cancel();
        }
    }

    @Inject(method = "handleSoundEntityEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateSoundEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleSoundEntityEvent", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleStopSoundEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateStopSound(ClientboundStopSoundPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordStopSound(packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleEntityEvent", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleLevelEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleLevelEvent", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleExplosion", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleExplosion", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleParticleEvent", packet);
            ci.cancel();
        }
    }
}

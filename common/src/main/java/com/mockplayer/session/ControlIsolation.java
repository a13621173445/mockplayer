package com.mockplayer.session;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import com.mockplayer.session.accessor.MockplayerClientLevelAccessor;
import com.mockplayer.session.accessor.MockplayerClientPacketListenerAccessor;
import com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor;

/**
 * /control 期间主玩家 listener 的完整隔离逻辑。
 *
 * 主玩家 connection 的原版 ClientPacketListener 收到服务端包后，handler 直接读 Minecraft.player/level/gameMode
 * （反编译确认），control 期间 mc.player=bot → 主玩家的数据会污染 bot。本类把这些 handler 的隔离逻辑集中实现：
 * 数据更新到 mainPlayer/mainLevel/mainState，不弹 GUI、不播音效、不弹屏（主玩家挂机 = 无头假人）。
 *
 * 逻辑全部参照 FakePlayListener 的「假人分支」实现，目标对象换成 ControlManager.getMainPlayer()/getMainLevel()。
 */
public final class ControlIsolation {

    private ControlIsolation() {
    }

    // ===== 聊天：记录到主玩家 state，不显示到 bot 聊天栏 =====

    public static void recordChat(ClientboundPlayerChatPacket packet) {
        FakePlayerState state = ControlManager.getMainState();
        state.recordPacket("handlePlayerChat", packet);
        net.minecraft.network.chat.Component text = packet.unsignedContent() != null
                ? packet.unsignedContent()
                : net.minecraft.network.chat.Component.literal(packet.body().content());
        state.addChat(text);
    }

    public static void recordSystemChat(ClientboundSystemChatPacket packet) {
        ControlManager.getMainState().addChat(packet.content());
    }

    public static void recordDisguisedChat(ClientboundDisguisedChatPacket packet) {
        ControlManager.getMainState().addChat(packet.message());
    }

    public static void recordDeleteChat(net.minecraft.network.protocol.game.ClientboundDeleteChatPacket packet) {
        ControlManager.getMainState().recordPacket("handleDeleteChat", packet);
    }

    // ===== 游戏事件（模式切换/天气/音效/粒子/win/demo）：全部应用到主玩家那套，不碰全局 GUI =====

    public static void applyGameEvent(ClientboundGameEventPacket packet) {
        LocalPlayer player = ControlManager.getMainPlayer();
        ClientLevel level = ControlManager.getMainLevel();
        if (player == null || level == null) {
            return;
        }
        FakePlayerState state = ControlManager.getMainState();
        ClientboundGameEventPacket.Type event = packet.getEvent();
        float paramFloat = packet.getParam();
        int param = Mth.floor(paramFloat + 0.5F);
        if (event == ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE) {
            state.addChat(net.minecraft.network.chat.Component.translatable("block.minecraft.spawn.not_valid"));
        } else if (event == ClientboundGameEventPacket.START_RAINING) {
            level.setRainLevel(0.0F);
        } else if (event == ClientboundGameEventPacket.STOP_RAINING) {
            level.setRainLevel(1.0F);
        } else if (event == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            // 只改主玩家 abilities + gameMode accessor，不用 setLocalMode（内部硬编码 this.minecraft.player）
            net.minecraft.world.level.GameType mode = net.minecraft.world.level.GameType.byId(param);
            mode.updatePlayerAbilities(player.getAbilities());
            MultiPlayerGameMode gameMode = ControlManager.getMainGameMode();
            if (gameMode != null) {
                ((MockplayerMultiPlayerGameModeAccessor) gameMode).mockplayer$setLocalPlayerMode(mode);
            }
        } else if (event == ClientboundGameEventPacket.WIN_GAME) {
            state.recordWinGame();
        } else if (event == ClientboundGameEventPacket.DEMO_EVENT) {
            state.recordDemoEvent(paramFloat);
        } else if (event == ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) {
            state.recordSound("arrow_hit", player.getX(), player.getEyeY(), player.getZ());
        } else if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            level.setRainLevel(paramFloat);
        } else if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            level.setThunderLevel(paramFloat);
        } else if (event == ClientboundGameEventPacket.PUFFER_FISH_STING) {
            state.recordSound("puffer_sting", player.getX(), player.getY(), player.getZ());
        } else if (event == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT) {
            state.recordParticle("elder_guardian", player.getX(), player.getY(), player.getZ());
            if (param == 1) {
                state.recordSound("elder_guardian_curse", player.getX(), player.getY(), player.getZ());
            }
        } else if (event == ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
            player.setShowDeathScreen(paramFloat == 0.0F);
        } else if (event == ClientboundGameEventPacket.LIMITED_CRAFTING) {
            player.setDoLimitedCrafting(paramFloat == 1.0F);
        }
        // LEVEL_CHUNKS_LOAD_START：chunk 加载由原版 LevelLoadTracker 处理（切回主玩家渲染后完成），忽略
    }

    // ===== 统计：写到主玩家 player =====

    public static void applyStats(ClientboundAwardStatsPacket packet) {
        LocalPlayer player = ControlManager.getMainPlayer();
        if (player == null) {
            return;
        }
        for (var entry : packet.stats().object2IntEntrySet()) {
            player.getStats().setValue(player, entry.getKey(), entry.getIntValue());
        }
    }

    // ===== 死亡：记录到 state + 触发重生（不弹死亡界面，与原版一致触发 respawn 链路） =====

    public static void recordCombatKill(ClientboundPlayerCombatKillPacket packet) {
        FakePlayerState state = ControlManager.getMainState();
        state.recordPacket("handlePlayerCombatKill", packet);
        LocalPlayer player = ControlManager.getMainPlayer();
        if (player != null) {
            state.setHealth(0.0F);
            player.respawn();
        }
    }

    // ===== 重生：完整迁移主玩家状态，重建 mainLevel/mainPlayer，绝不碰 mc.setLevel/mc.player =====

    public static void handleRespawn(ClientPacketListener listener, ClientboundRespawnPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer oldPlayer = ControlManager.getMainPlayer();
        ClientLevel oldLevel = ControlManager.getMainLevel();
        if (oldPlayer == null || oldLevel == null) {
            return;
        }
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) listener;
        var spawnInfo = packet.commonPlayerSpawnInfo();
        ResourceKey<Level> dimensionKey = spawnInfo.dimension();
        Holder<DimensionType> dimensionType = spawnInfo.dimensionType();
        boolean dimensionChanged = dimensionKey != oldLevel.dimension();
        ClientLevel newLevel = oldLevel;
        if (dimensionChanged) {
            // 迁移地图数据（不能丢）：从旧 level 取，写入新 level
            java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mapData =
                    ((MockplayerClientLevelAccessor) oldLevel).mockplayer$getAllMapData();
            boolean isDebug = spawnInfo.isDebug();
            boolean isFlat = spawnInfo.isFlat();
            int seaLevel = spawnInfo.seaLevel();
            ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                    self.mockplayer$getLevelData().getDifficulty(),
                    self.mockplayer$getLevelData().isHardcore(),
                    isFlat);
            self.mockplayer$setLevelData(levelData);
            newLevel = new ClientLevel(
                    listener,
                    levelData,
                    dimensionKey,
                    dimensionType,
                    self.mockplayer$getServerChunkRadius(),
                    self.mockplayer$getServerSimulationDistance(),
                    mc.levelExtractor,
                    isDebug,
                    spawnInfo.seed(),
                    seaLevel);
            ((MockplayerClientLevelAccessor) newLevel).mockplayer$addMapData(mapData);
            // 更新主玩家 listener 的 level 字段（原版 respawn 会 mc.setLevel，这里只写 listener 自己的 level）
            self.mockplayer$setLevel(newLevel);
        }
        if (oldPlayer.hasContainerOpen()) {
            oldPlayer.closeContainer();
        }
        LocalPlayer newPlayer;
        if (packet.shouldKeep((byte) 2)) {
            newPlayer = new LocalPlayer(
                    mc,
                    newLevel,
                    listener,
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook(),
                    oldPlayer.getLastSentInput(),
                    oldPlayer.isSprinting(),
                    mc.computeChatAbilities());
        } else {
            newPlayer = new LocalPlayer(
                    mc,
                    newLevel,
                    listener,
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook(),
                    new Input(false, false, false, false, false, false, false),
                    false,
                    mc.computeChatAbilities());
        }
        // 标记未加载，等待新 level 加载完成再恢复物理（原版 LevelLoadTracker，切回主玩家渲染后完成）
        self.mockplayer$setClientLoaded(false);
        newPlayer.setId(oldPlayer.getId());
        if (packet.shouldKeep((byte) 2)) {
            java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data =
                    oldPlayer.getEntityData().getNonDefaultValues();
            if (data != null) {
                newPlayer.getEntityData().assignValues(data);
            }
            newPlayer.setDeltaMovement(oldPlayer.getDeltaMovement());
            newPlayer.setYRot(oldPlayer.getYRot());
            newPlayer.setXRot(oldPlayer.getXRot());
        } else {
            newPlayer.resetPos();
            newPlayer.setYRot(-180.0F);
        }
        if (packet.shouldKeep((byte) 1)) {
            newPlayer.getAttributes().assignAllValues(oldPlayer.getAttributes());
        } else {
            newPlayer.getAttributes().assignBaseValues(oldPlayer.getAttributes());
        }
        newLevel.addEntity(newPlayer);
        newPlayer.input = new net.minecraft.client.player.ClientInput();
        MultiPlayerGameMode mainGameMode = ControlManager.getMainGameMode();
        if (mainGameMode != null) {
            mainGameMode.adjustPlayer(newPlayer);
        }
        newPlayer.setReducedDebugInfo(oldPlayer.isReducedDebugInfo());
        newPlayer.setShowDeathScreen(oldPlayer.shouldShowDeathScreen());
        newPlayer.setDoLimitedCrafting(oldPlayer.getDoLimitedCrafting());
        newPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        newPlayer.portalEffectIntensity = oldPlayer.portalEffectIntensity;
        newPlayer.oPortalEffectIntensity = oldPlayer.oPortalEffectIntensity;
        // 主玩家自己的 gameType：只改主玩家 abilities + gameMode accessor，不用 setLocalMode
        spawnInfo.gameType().updatePlayerAbilities(newPlayer.getAbilities());
        if (mainGameMode != null) {
            ((MockplayerMultiPlayerGameModeAccessor) mainGameMode).mockplayer$setLocalPlayerMode(spawnInfo.gameType());
            ((MockplayerMultiPlayerGameModeAccessor) mainGameMode).mockplayer$setPreviousLocalPlayerMode(spawnInfo.previousGameType());
        }
        // 更新 ControlManager 备份引用（不碰 mc.player/mc.level，control 保持）
        ControlManager.replaceMainPlayer(newPlayer);
        ControlManager.replaceMainLevel(newLevel);
        FakeSession.LOG.info("[control] 主玩家已重生（控制保持，新 player={}）", newPlayer.getId());
    }
}

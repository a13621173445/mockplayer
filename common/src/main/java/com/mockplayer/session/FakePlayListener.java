package com.mockplayer.session;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mockplayer.session.accessor.MockplayerClientPacketListenerAccessor;

/**
 * 假人的 play 阶段 listener：复用 ClientPacketListener 的全部 handler，
 * 但 handleLogin 走「假人分支」——只建假人自己的 world/player，绝不写主玩家全局。
 *
 * 无头小客户端：不渲染、不弹 UI，但网络/协议/物理全做，反作弊合规。
 */
public class FakePlayListener extends ClientPacketListener {

    /** 关联的假人会话 */
    private final FakeSession session;
    /** 假人自己的 gameMode（交互用） */
    private MultiPlayerGameMode fakeGameMode;
    /** 假人自己的 LocalPlayer（物理） */
    private LocalPlayer fakePlayer;

    public FakePlayListener(FakeSession session, Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
        this.session = session;
    }

    public FakeSession getSession() {
        return this.session;
    }

    public LocalPlayer getFakePlayer() {
        return this.fakePlayer;
    }

    public MultiPlayerGameMode getFakeGameMode() {
        return this.fakeGameMode;
    }

    /**
     * 假人分支 handleLogin：只建假人自己的 world/player，不写主玩家全局。
     * 不调 minecraft.setLevel / setCameraEntity / startWaitingForNewLevel（不污染主玩家、不弹加载界面）。
     */
    @Override
    public void handleLogin(ClientboundLoginPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;

        // 1. 假人自己的 gameMode（不写 mc.gameMode）
        this.fakeGameMode = new MultiPlayerGameMode(mc, this);

        // 2. 假人自己的 level（不调 mc.setLevel）
        var spawnInfo = packet.commonPlayerSpawnInfo();
        List<ResourceKey<Level>> levels = Lists.newArrayList(packet.levels());
        Collections.shuffle(levels);
        self.mockplayer$setLevels(Sets.newLinkedHashSet(levels));
        ResourceKey<Level> dimension = spawnInfo.dimension();
        Holder<DimensionType> dimensionType = spawnInfo.dimensionType();
        self.mockplayer$setServerChunkRadius(packet.chunkRadius());
        self.mockplayer$setServerSimulationDistance(packet.simulationDistance());
        boolean isDebug = spawnInfo.isDebug();
        boolean isFlat = spawnInfo.isFlat();
        int seaLevel = spawnInfo.seaLevel();
        ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(Difficulty.NORMAL, packet.hardcore(), isFlat);
        self.mockplayer$setLevelData(levelData);
        self.mockplayer$setLevel(new ClientLevel(
                this,
                levelData,
                dimension,
                dimensionType,
                self.mockplayer$getServerChunkRadius(),
                self.mockplayer$getServerSimulationDistance(),
                mc.levelExtractor,
                isDebug,
                spawnInfo.seed(),
                seaLevel
        ));

        // 3. 假人自己的 player（不写 mc.player）——FakeLocalPlayer 强制物理发包
        this.fakePlayer = new FakeLocalPlayer(
                mc,
                self.mockplayer$getLevel(),
                this,
                new StatsCounter(),
                new ClientRecipeBook(),
                new net.minecraft.world.entity.player.Input(false, false, false, false, false, false, false),
                false,
                mc.computeChatAbilities()
        );
        this.fakePlayer.setYRot(-180.0F);
        this.fakePlayer.resetPos();
        this.fakePlayer.setId(packet.playerId());
        self.mockplayer$getLevel().addEntity(this.fakePlayer);
        // 关键：标记客户端已加载，否则 LocalPlayer.tick() 里的物理（super.tick）不执行 → 假人悬空
        self.mockplayer$setClientLoaded(true);
        // 用 ClientInput（零输入），不用 KeyboardInput——KeyboardInput 读主玩家的按键，
        // 会导致假人跟着主玩家移动（挂机时假人应静止，只受重力/击退等环境影响）
        this.fakePlayer.input = new net.minecraft.client.player.ClientInput();
        this.fakeGameMode.adjustPlayer(this.fakePlayer);
        this.fakePlayer.setReducedDebugInfo(packet.reducedDebugInfo());
        this.fakePlayer.setShowDeathScreen(packet.showDeathScreen());
        this.fakePlayer.setDoLimitedCrafting(packet.doLimitedCrafting());
        this.fakePlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        this.fakePlayer.setPortalCooldown(spawnInfo.portalCooldown());
        this.fakeGameMode.setLocalMode(spawnInfo.gameType(), spawnInfo.previousGameType());

        // 4. 存进 session（供 tick 驱动物理）
        this.session.setFakePlayer(this.fakePlayer);
        this.session.setPlayListener(this);
        FakeSession.LOG.info("[{}] 假人进入 play 阶段，已创建独立 world/player", this.session.getName());
    }

    // ===== override 污染源 handler：假人收到的一切包处理到假人自己，绝不写主玩家全局 =====

    /**
     * 聊天：假人记录到自己的 state，不写主玩家聊天栏（防止双发）。
     */
    @Override
    public void handlePlayerChat(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket packet) {
        // 签名聊天：取未签名内容（unsignedContent）作为假人记录的聊天文本
        this.session.getState().addChat(packet.unsignedContent());
    }

    @Override
    public void handleSystemChat(net.minecraft.network.protocol.game.ClientboundSystemChatPacket packet) {
        this.session.getState().addChat(packet.content());
    }

    @Override
    public void handleDisguisedChat(net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket packet) {
        this.session.getState().addChat(packet.message());
    }

    /**
     * 血量/饥饿：写到假人自己的 player（不写主玩家 → 攻击假人主玩家不掉血）。
     */
    @Override
    public void handleSetHealth(net.minecraft.network.protocol.game.ClientboundSetHealthPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.hurtTo(packet.getHealth());
            this.fakePlayer.getFoodData().setFoodLevel(packet.getFood());
            this.fakePlayer.getFoodData().setSaturation(packet.getSaturation());
        }
        this.session.getState().setHealth(packet.getHealth());
        this.session.getState().setFoodLevel(packet.getFood());
    }

    /**
     * 经验：写到假人自己的 player。
     */
    @Override
    public void handleSetExperience(net.minecraft.network.protocol.game.ClientboundSetExperiencePacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.setExperienceValues(packet.getExperienceProgress(), packet.getTotalExperience(), packet.getExperienceLevel());
        }
        this.session.getState().setExperienceLevel(packet.getExperienceLevel());
    }

    /**
     * 能力（飞行等）：写到假人自己的 player。
     */
    @Override
    public void handlePlayerAbilities(net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.getAbilities().flying = packet.isFlying();
            this.fakePlayer.getAbilities().instabuild = packet.canInstabuild();
            this.fakePlayer.getAbilities().mayfly = packet.canFly();
        }
    }

    /**
     * 游戏事件：全部数据拿全，写到假人状态（不碰主玩家 gameMode/gui）。
     * - 天气（rain/thunder）→ 假人 level
     * - CHANGE_GAME_MODE → 假人自己的 gameMode
     * - IMMEDIATE_RESPAWN / LIMITED_CRAFTING → 假人 player
     * - 音效/粒子 → 假人 level
     * - 消息类 / WIN / DEMO → 记录到 state
     */
    @Override
    public void handleGameEvent(net.minecraft.network.protocol.game.ClientboundGameEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.client.player.LocalPlayer player = this.fakePlayer;
        if (player == null) {
            return;
        }
        net.minecraft.network.protocol.game.ClientboundGameEventPacket.Type event = packet.getEvent();
        float paramFloat = packet.getParam();
        int param = net.minecraft.util.Mth.floor(paramFloat + 0.5F);
        if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE) {
            this.session.getState().addChat(net.minecraft.network.chat.Component.translatable("block.minecraft.spawn.not_valid"));
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING) {
            self.mockplayer$getLevel().setRainLevel(0.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING) {
            self.mockplayer$getLevel().setRainLevel(1.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            this.fakeGameMode.setLocalMode(net.minecraft.world.level.GameType.byId(param));
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.WIN_GAME) {
            // 假人无头不弹 WinScreen，记录到 state
            this.session.getState().recordWinGame();
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.DEMO_EVENT) {
            // 假人无头不弹演示 UI，记录到 state
            this.session.getState().recordDemoEvent(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) {
            self.mockplayer$getLevel().playSound(player, player.getX(), player.getEyeY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.ARROW_HIT_PLAYER, net.minecraft.sounds.SoundSource.PLAYERS, 0.18F, 0.45F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            self.mockplayer$getLevel().setRainLevel(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            self.mockplayer$getLevel().setThunderLevel(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.PUFFER_FISH_STING) {
            self.mockplayer$getLevel().playSound(player, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.PUFFER_FISH_STING, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT) {
            self.mockplayer$getLevel().addParticle(net.minecraft.core.particles.ParticleTypes.ELDER_GUARDIAN,
                    player.getX(), player.getY(), player.getZ(), 0.0, 0.0, 0.0);
            if (param == 1) {
                self.mockplayer$getLevel().playSound(player, player.getX(), player.getY(), player.getZ(),
                        net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_CURSE, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
            }
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
            player.setShowDeathScreen(paramFloat == 0.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.LIMITED_CRAFTING) {
            player.setDoLimitedCrafting(paramFloat == 1.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START) {
            // 假人 level 加载跟踪（此 listener 无 levelLoadTracker，跳过无影响）
        }
    }

    /**
     * 玩家列表更新：完整记录到假人 state（不写主玩家社交管理器/playerInfoMap），
     * 供程序化 AI 感知周围玩家。新条目记录在线，动作更新游戏模式/名字。
     */
    @Override
    public void handlePlayerInfoUpdate(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet) {
        for (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
            // 新玩家上线：记录 UUID + 名字（profile 可能为空，用 UUID 兜底）
            String name = entry.profile() != null ? entry.profile().name() : entry.profileId().toString();
            this.session.getState().recordPlayerOnline(entry.profileId(), name);
        }
        for (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            // 已有玩家更新动作：更新名字（游戏模式等信息后续扩展）
            String name = entry.profile() != null ? entry.profile().name() : entry.profileId().toString();
            this.session.getState().recordPlayerOnline(entry.profileId(), name);
        }
    }

    // ===== 位置/旋转：换假人引用，假人位置由服务端同步（解决下坠/横跳） =====

    /**
     * 服务端位置包 → 应用到假人自己（不写主玩家）。
     * 父类用 this.minecraft.player 会导致主玩家视角被拽到假人位置（横跳），这里换成 fakePlayer。
     */
    @Override
    public void handleMovePlayer(net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.player.Player player = this.fakePlayer;
        if (!player.isPassenger()) {
            MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
        }
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(packet.id()));
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), false, false));
        // 注：父类会调 level.getBlockStatePredictionHandler().onTeleport()（方块预测清理）。
        // 假人无渲染方块预测，此调用无实际影响，且 getBlockStatePredictionHandler 是包内私有，跳过。
    }

    /**
     * 旋转包 → 应用到假人自己。
     */
    @Override
    public void handleRotatePlayer(net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.player.Player player = this.fakePlayer;
        Set<net.minecraft.world.entity.Relative> relatives = net.minecraft.world.entity.Relative.rotation(packet.relativeY(), packet.relativeX());
        net.minecraft.world.entity.PositionMoveRotation currentValues = net.minecraft.world.entity.PositionMoveRotation.of(player);
        net.minecraft.world.entity.PositionMoveRotation newValues = net.minecraft.world.entity.PositionMoveRotation.calculateAbsolute(
                currentValues, currentValues.withRotation(packet.yRot(), packet.xRot()), relatives
        );
        player.setYRot(newValues.yRot());
        player.setXRot(newValues.xRot());
        player.setOldRot();
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), false, false));
    }

    /**
     * 快捷栏选择 → 应用到假人自己的背包。
     */
    @Override
    public void handleSetHeldSlot(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket packet) {
        if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(packet.slot())) {
            this.fakePlayer.getInventory().setSelectedSlot(packet.slot());
        }
    }

    /**
     * 容器内容 → 应用到假人自己的菜单/背包。
     */
    @Override
    public void handleContainerContent(net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet) {
        if (this.fakePlayer != null) {
            if (packet.containerId() == 0) {
                this.fakePlayer.inventoryMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
            } else if (packet.containerId() == this.fakePlayer.containerMenu.containerId) {
                this.fakePlayer.containerMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
            }
        }
    }

    /**
     * 容器槽位 → 应用到假人自己的背包。
     */
    @Override
    public void handleContainerSetSlot(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet) {
        if (this.fakePlayer != null) {
            if (packet.getContainerId() == 0) {
                this.fakePlayer.getInventory().setItem(packet.getSlot(), packet.getItem());
            } else {
                this.fakePlayer.containerMenu.setItem(packet.getSlot(), packet.getStateId(), packet.getItem());
            }
        }
    }

    /**
     * 容器关闭 → 应用到假人自己的菜单。
     */
    @Override
    public void handleContainerClose(net.minecraft.network.protocol.game.ClientboundContainerClosePacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.containerMenu.removed(this.fakePlayer);
            this.fakePlayer.containerMenu = this.fakePlayer.inventoryMenu;
        }
    }

    // ===== 成就/进度/统计（假人自己记录，不弹主玩家） =====

    /**
     * 进度更新：记录到假人自己的进度（不触发主玩家进度弹窗）。
     * 父类 this.advancements 是主玩家的，override 后更新假人自己的（若有）。
     */
    @Override
    public void handleUpdateAdvancementsPacket(net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket packet) {
        // 假人无头不弹进度窗，但进度数据记录到会话状态（供程序化 AI 读取）
        this.session.getState().recordAdvancements(packet);
    }

    @Override
    public void handleSelectAdvancementsTab(net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket packet) {
        // 假人无 UI，不处理进度 tab 选择
    }

    /**
     * 统计：写到假人自己的统计（不写主玩家）。
     */
    @Override
    public void handleAwardStats(net.minecraft.network.protocol.game.ClientboundAwardStatsPacket packet) {
        if (this.fakePlayer != null) {
            for (var entry : packet.stats().object2IntEntrySet()) {
                this.fakePlayer.getStats().setValue(this.fakePlayer, entry.getKey(), entry.getIntValue());
            }
        }
    }

    // ===== 音效（假人 level 播放，假人也感知） =====

    @Override
    public void handleSoundEvent(net.minecraft.network.protocol.game.ClientboundSoundPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.level().playSeededSound(
                    this.fakePlayer,
                    packet.getX(), packet.getY(), packet.getZ(),
                    packet.getSound(), packet.getSource(), packet.getVolume(), packet.getPitch(),
                    packet.getSeed()
            );
        }
    }

    // ===== 骑乘（假人自己） =====

    @Override
    public void handleSetEntityPassengersPacket(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket packet) {
        if (this.fakePlayer != null) {
            // 假人无头，骑乘状态记录到会话（简化：调用父类会碰 minecraft.player，故手动处理）
            MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
            net.minecraft.world.entity.Entity vehicle = self.mockplayer$getLevel().getEntity(packet.getVehicle());
            if (vehicle != null) {
                vehicle.ejectPassengers();
                for (int id : packet.getPassengers()) {
                    net.minecraft.world.entity.Entity passenger = self.mockplayer$getLevel().getEntity(id);
                    if (passenger != null) {
                        passenger.startRiding(vehicle, true, false);
                    }
                }
            }
        }
    }

    // ===== 冷却（假人自己） =====

    @Override
    public void handleItemCooldown(net.minecraft.network.protocol.game.ClientboundCooldownPacket packet) {
        if (this.fakePlayer != null) {
            if (packet.duration() == 0) {
                this.fakePlayer.getCooldowns().removeCooldown(packet.cooldownGroup());
            } else {
                this.fakePlayer.getCooldowns().addCooldown(packet.cooldownGroup(), packet.duration());
            }
        }
    }

    // ===== 标题/UI（假人无头，忽略但记录） =====

    @Override
    public void handleTitlesClear(net.minecraft.network.protocol.game.ClientboundClearTitlesPacket packet) {
        // 假人无 UI，忽略
    }

    // ===== 实体事件/音效实体（假人 level） =====

    @Override
    public void handleSoundEntityEvent(net.minecraft.network.protocol.game.ClientboundSoundEntityPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getId());
        if (entity != null) {
            // 用假人 listener 的 connection/level 播放，不碰主玩家
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            self.mockplayer$getLevel().playSeededSound(
                    this.fakePlayer != null ? this.fakePlayer : null,
                    entity,
                    packet.getSound(),
                    packet.getSource(),
                    packet.getVolume(),
                    packet.getPitch(),
                    packet.getSeed());
        }
    }

    @Override
    public void handleEntityEvent(net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet) {
        // this.level 处理实体状态，保持继承即可（父类用 this.level，不碰 minecraft.player 主要部分）
        super.handleEntityEvent(packet);
    }

    @Override
    public void handleAnimate(net.minecraft.network.protocol.game.ClientboundAnimatePacket packet) {
        // 实体动画状态更新到假人 level（不碰主玩家粒子引擎）
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getId());
        if (entity != null) {
            if (packet.getAction() == 0) {
                ((net.minecraft.world.entity.LivingEntity) entity).swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            } else if (packet.getAction() == 3) {
                ((net.minecraft.world.entity.LivingEntity) entity).swing(net.minecraft.world.InteractionHand.OFF_HAND);
            } else if (packet.getAction() == 2) {
                ((net.minecraft.world.entity.player.Player) entity).stopSleepInBed(false, false);
            } else if (packet.getAction() == 4 || packet.getAction() == 5) {
                // 暴击/附魔粒子：假人无头不渲染粒子，跳过（动画状态已由 swing 反映）
            }
        }
    }

    // ===== 其他容器/背包 =====

    @Override
    public void handleSetCursorItem(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.containerMenu.setCarried(packet.contents());
        }
    }

    @Override
    public void handleSetPlayerInventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.getInventory().setItem(packet.slot(), packet.contents());
        }
    }

    @Override
    public void handleContainerSetData(net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.containerMenu.setData(packet.getId(), packet.getValue());
        }
    }

    // ===== 重生（假人分支，完整迁移假人状态，不碰 mc.player/mc.gameMode/mc.setLevel/mc.setCameraEntity） =====

    @Override
    public void handleRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.network.protocol.game.CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey = spawnInfo.dimension();
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> dimensionType = spawnInfo.dimensionType();
        if (this.fakePlayer == null) {
            return;
        }
        net.minecraft.client.player.LocalPlayer oldPlayer = this.fakePlayer;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> oldDimensionKey = oldPlayer.level().dimension();
        boolean dimensionChanged = dimensionKey != oldDimensionKey;
        if (dimensionChanged) {
            // 迁移地图数据（不能丢！假人重生不罕见）：从旧 level 取，写入新 level
            com.mockplayer.session.accessor.MockplayerClientLevelAccessor oldLevelAccessor =
                    (com.mockplayer.session.accessor.MockplayerClientLevelAccessor) self.mockplayer$getLevel();
            java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mapData =
                    oldLevelAccessor.mockplayer$getAllMapData();
            boolean isDebug = spawnInfo.isDebug();
            boolean isFlat = spawnInfo.isFlat();
            int seaLevel = spawnInfo.seaLevel();
            net.minecraft.client.multiplayer.ClientLevel.ClientLevelData levelData = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                    self.mockplayer$getLevelData().getDifficulty(),
                    self.mockplayer$getLevelData().isHardcore(),
                    isFlat);
            self.mockplayer$setLevelData(levelData);
            net.minecraft.client.multiplayer.ClientLevel newLevel = new net.minecraft.client.multiplayer.ClientLevel(
                    this,
                    levelData,
                    dimensionKey,
                    dimensionType,
                    self.mockplayer$getServerChunkRadius(),
                    self.mockplayer$getServerSimulationDistance(),
                    net.minecraft.client.Minecraft.getInstance().levelExtractor,
                    isDebug,
                    spawnInfo.seed(),
                    seaLevel);
            ((com.mockplayer.session.accessor.MockplayerClientLevelAccessor) newLevel).mockplayer$addMapData(mapData);
            self.mockplayer$setLevel(newLevel);
        }
        if (oldPlayer.hasContainerOpen()) {
            oldPlayer.closeContainer();
        }
        net.minecraft.client.player.LocalPlayer newPlayer;
        if (packet.shouldKeep((byte) 2)) {
            newPlayer = this.fakeGameMode.createPlayer(
                    self.mockplayer$getLevel(),
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook(),
                    oldPlayer.getLastSentInput(),
                    oldPlayer.isSprinting());
        } else {
            newPlayer = this.fakeGameMode.createPlayer(
                    self.mockplayer$getLevel(),
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook());
        }
        // 标记未加载，等待新 level 加载完成再恢复物理（与父类 startWaitingForNewLevel 对应）
        self.mockplayer$setClientLoaded(false);
        newPlayer.setId(oldPlayer.getId());
        this.fakePlayer = newPlayer;
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
        self.mockplayer$getLevel().addEntity(newPlayer);
        newPlayer.input = new net.minecraft.client.player.ClientInput();
        this.fakeGameMode.adjustPlayer(newPlayer);
        // 这些标志从旧玩家继承（父类逻辑），不从 packet 读——packet 里没有这些字段
        newPlayer.setReducedDebugInfo(oldPlayer.isReducedDebugInfo());
        newPlayer.setShowDeathScreen(oldPlayer.shouldShowDeathScreen());
        newPlayer.setDoLimitedCrafting(oldPlayer.getDoLimitedCrafting());
        newPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        newPlayer.portalEffectIntensity = oldPlayer.portalEffectIntensity;
        newPlayer.oPortalEffectIntensity = oldPlayer.oPortalEffectIntensity;
        this.fakeGameMode.setLocalMode(spawnInfo.gameType(), spawnInfo.previousGameType());
        // 新 level 已就绪，恢复物理驱动
        self.mockplayer$setClientLoaded(true);
    }

    // ===== 实体传送/同步（假人 level 操作，minecraft.player 部分换假人） =====

    @Override
    public void handleTeleportEntity(net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.id());
        if (entity == null) {
            if (this.fakePlayer != null) {
                MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(packet.change(), packet.relatives(), this.fakePlayer, false);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                        this.fakePlayer.getX(), this.fakePlayer.getY(), this.fakePlayer.getZ(),
                        this.fakePlayer.getYRot(), this.fakePlayer.getXRot(), false, false));
            }
        } else {
            boolean hasRelative = packet.relatives().contains(net.minecraft.world.entity.Relative.X)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Y)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Z);
            boolean interpolate = self.mockplayer$getLevel().isTickingEntity(entity) || !entity.isLocalInstanceAuthoritative() || hasRelative;
            boolean wasInterpolated = MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(
                    packet.change(), packet.relatives(), entity, interpolate);
            entity.setOnGround(packet.onGround());
            if (!wasInterpolated && this.fakePlayer != null && entity.hasIndirectPassenger(this.fakePlayer)) {
                entity.positionRider(this.fakePlayer);
                this.fakePlayer.setOldPosAndRot();
            }
        }
    }

    @Override
    public void handleEntityPositionSync(net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.id());
        if (entity != null) {
            net.minecraft.world.phys.Vec3 pos = packet.values().position();
            entity.getPositionCodec().setBase(pos);
            if (!entity.isLocalInstanceAuthoritative()) {
                float yRot = packet.values().yRot();
                float xRot = packet.values().xRot();
                boolean tooBig = entity.position().distanceToSqr(pos) > 4096.0;
                if (self.mockplayer$getLevel().isTickingEntity(entity) && !tooBig) {
                    entity.moveOrInterpolateTo(pos, yRot, xRot);
                } else {
                    entity.snapTo(pos, yRot, xRot);
                }
            }
        }
    }

    @Override
    public void handleRemoveEntities(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        packet.getEntityIds().forEach(entityId -> {
            net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(entityId);
            if (entity != null) {
                self.mockplayer$getLevel().removeEntity(entityId, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        });
    }

    @Override
    public void handleTakeItemEntity(net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity from = self.mockplayer$getLevel().getEntity(packet.getItemId());
        net.minecraft.world.entity.LivingEntity to = (net.minecraft.world.entity.LivingEntity) self.mockplayer$getLevel().getEntity(packet.getPlayerId());
        if (to == null) {
            to = this.fakePlayer;
        }
        if (from != null) {
            net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
            if (from instanceof net.minecraft.world.entity.ExperienceOrb) {
                self.mockplayer$getLevel().playLocalSound(from.getX(), from.getY(), from.getZ(),
                        net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS,
                        0.1F, (random.nextFloat() - random.nextFloat()) * 0.35F + 0.9F, false);
            } else {
                self.mockplayer$getLevel().playLocalSound(from.getX(), from.getY(), from.getZ(),
                        net.minecraft.sounds.SoundEvents.ITEM_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS,
                        0.2F, (random.nextFloat() - random.nextFloat()) * 1.4F + 2.0F, false);
            }
            // 实体被拾取移除（数据保留到假人 level）
            self.mockplayer$getLevel().removeEntity(from.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    @Override
    public void handleBlockEntityData(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.core.BlockPos pos = packet.getPos();
        self.mockplayer$getLevel().getBlockEntity(pos, packet.getType()).ifPresent(blockEntity -> {
            // 方块实体数据完整加载进假人 level（不碰主玩家 registryAccess/UI）
            net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, self.mockplayer$getRegistryAccess(), packet.getTag());
            blockEntity.loadWithComponents(input);
        });
    }

    @Override
    public void handleMoveVehicle(net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.world.entity.Entity vehicle = this.fakePlayer.getRootVehicle();
            if (vehicle != this.fakePlayer && vehicle.isLocalInstanceAuthoritative()) {
                net.minecraft.world.phys.Vec3 target = packet.position();
                vehicle.setPos(target.x, target.y, target.z);
            }
        }
    }

    @Override
    public void handleLookAt(net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        if (this.fakePlayer != null) {
            net.minecraft.world.phys.Vec3 pos = packet.getPosition(self.mockplayer$getLevel());
            if (pos != null) {
                this.fakePlayer.lookAt(packet.getFromAnchor(), pos);
            }
        }
    }

    @Override
    public void handleExplosion(net.minecraft.network.protocol.game.ClientboundExplodePacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        // 爆炸音效/粒子到假人 level（假人也感知）
        net.minecraft.world.phys.Vec3 center = packet.center();
        self.mockplayer$getLevel().playLocalSound(center.x(), center.y(), center.z(),
                packet.explosionSound().value(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F, false);
    }

    // ===== 配方书（假人自己的配方） =====

    @Override
    public void handleRecipeBookAdd(net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.client.ClientRecipeBook recipeBook = this.fakePlayer.getRecipeBook();
            if (packet.replace()) {
                recipeBook.clear();
            }
            for (var entry : packet.entries()) {
                recipeBook.add(entry.contents());
            }
        }
    }

    @Override
    public void handleRecipeBookRemove(net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.client.ClientRecipeBook recipeBook = this.fakePlayer.getRecipeBook();
            for (net.minecraft.world.item.crafting.display.RecipeDisplayId id : packet.recipes()) {
                recipeBook.remove(id);
            }
        }
    }

    @Override
    public void handleRecipeBookSettings(net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.getRecipeBook().setBookSettings(packet.bookSettings());
        }
    }

    @Override
    public void handlePlaceRecipe(net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket packet) {
        // 假人无头，幽灵配方显示忽略
    }

    // ===== 容器/菜单（假人自己的） =====

    @Override
    public void handleOpenBook(net.minecraft.network.protocol.game.ClientboundOpenBookPacket packet) {
        // 假人无头，不打开书本界面
    }

    @Override
    public void handleMountScreenOpen(net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket packet) {
        // 骑乘容器：完整建假人自己的菜单（不弹主玩家 UI，但数据进假人背包/菜单）
        if (this.fakePlayer == null) {
            return;
        }
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getEntityId());
        net.minecraft.client.player.LocalPlayer player = this.fakePlayer;
        int inventoryColumns = packet.getInventoryColumns();
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(
                net.minecraft.world.inventory.AbstractMountInventoryMenu.getInventorySize(inventoryColumns));
        if (entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse) {
            net.minecraft.world.inventory.HorseInventoryMenu menu = new net.minecraft.world.inventory.HorseInventoryMenu(
                    packet.getContainerId(), player.getInventory(), container, horse, inventoryColumns);
            player.containerMenu = menu;
        } else if (entity instanceof net.minecraft.world.entity.animal.nautilus.AbstractNautilus nautilus) {
            net.minecraft.world.inventory.NautilusInventoryMenu menu = new net.minecraft.world.inventory.NautilusInventoryMenu(
                    packet.getContainerId(), player.getInventory(), container, nautilus, inventoryColumns);
            player.containerMenu = menu;
        }
    }

    @Override
    public void handleOpenSignEditor(net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket packet) {
        // 假人无头，不打开告示牌编辑
    }

    @Override
    public void handleMerchantOffers(net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket packet) {
        if (this.fakePlayer != null && this.fakePlayer.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu merchantMenu) {
            merchantMenu.setOffers(packet.getOffers());
            merchantMenu.setXp(packet.getVillagerXp());
            merchantMenu.setMerchantLevel(packet.getVillagerLevel());
        }
    }

    // ===== UI/HUD（假人无头，忽略渲染；数据已通过其他 handler 记录） =====

    @Override
    public void handleTabListCustomisation(net.minecraft.network.protocol.game.ClientboundTabListPacket packet) {
        // 假人无头，不显示 Tab 列表
    }

    @Override
    public void handleBossUpdate(net.minecraft.network.protocol.game.ClientboundBossEventPacket packet) {
        // Boss 事件数据记录到假人 state（不弹主玩家 Boss 栏，供 AI 感知 Boss 状态）
        this.session.getState().recordBossEvent(packet);
    }

    @Override
    public void handleLowDiskSpaceWarning(net.minecraft.network.protocol.game.ClientboundLowDiskSpaceWarningPacket packet) {
        // 假人无头，磁盘警告忽略
    }

    @Override
    public void handleChangeDifficulty(net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket packet) {
        // 难度数据写入假人自己的 levelData（父类 this.levelData 是假人的），仅去掉主玩家 GUI 反应
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevelData().setDifficulty(packet.difficulty());
        self.mockplayer$getLevelData().setDifficultyLocked(packet.locked());
    }

    @Override
    public void handleGameRuleValues(net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket packet) {
        // 假人无头，游戏规则界面忽略（规则已由父类 level 处理）
    }

    @Override
    public void handleConfigurationStart(net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket packet) {
        // 服务端要求重进配置。假人无头不走主玩家重进 UI（会清主玩家 level），
        // 保持连接等待后续 login 包重建假人 level（数据由 handleLogin 重新下发，不丢）。
    }

    @Override
    public void handleTestInstanceBlockStatus(net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus packet) {
        // 假人无头，测试实例状态忽略
    }

    // ===== 地图数据 / 世界事件 / 挖矿 / 生物群系 / 出生点 / tick（假人 level，不碰主玩家） =====

    @Override
    public void handleMapItemData(net.minecraft.network.protocol.game.ClientboundMapItemDataPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.level.saveddata.maps.MapId id = packet.mapId();
        net.minecraft.world.level.saveddata.maps.MapItemSavedData data = self.mockplayer$getLevel().getMapData(id);
        if (data == null) {
            data = net.minecraft.world.level.saveddata.maps.MapItemSavedData.createForClient(
                    packet.scale(), packet.locked(), self.mockplayer$getLevel().dimension());
            self.mockplayer$getLevel().overrideMapData(id, data);
        }
        packet.applyToMap(data);
    }

    @Override
    public void handleTickingState(net.minecraft.network.protocol.game.ClientboundTickingStatePacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.TickRateManager manager = self.mockplayer$getLevel().tickRateManager();
        manager.setTickRate(packet.tickRate());
        manager.setFrozen(packet.isFrozen());
    }

    @Override
    public void handleTickingStep(net.minecraft.network.protocol.game.ClientboundTickingStepPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().tickRateManager().setFrozenTicksToRun(packet.tickSteps());
    }

    @Override
    public void handleLevelEvent(net.minecraft.network.protocol.game.ClientboundLevelEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        if (packet.isGlobalEvent()) {
            self.mockplayer$getLevel().globalLevelEvent(packet.getType(), packet.getPos(), packet.getData());
        } else {
            self.mockplayer$getLevel().levelEvent(packet.getType(), packet.getPos(), packet.getData());
        }
    }

    @Override
    public void handleBlockDestruction(net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().destroyBlockProgress(packet.getId(), packet.getPos(), packet.getProgress());
    }

    @Override
    public void handleChunksBiomes(net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        for (net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            self.mockplayer$getLevel().getChunkSource().replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }
        for (net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            self.mockplayer$getLevel().onChunkLoaded(new net.minecraft.world.level.ChunkPos(data.pos().x(), data.pos().z()));
        }
    }

    @Override
    public void handleSetSpawn(net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().setRespawnData(packet.respawnData());
    }

    @Override
    public void handleBlockEvent(net.minecraft.network.protocol.game.ClientboundBlockEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().blockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    @Override
    public void handleGameTestHighlightPos(net.minecraft.network.protocol.game.ClientboundGameTestHighlightPosPacket packet) {
        // 假人无头，测试高亮渲染忽略
    }

    @Override
    public void handlePlayerCombatKill(net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket packet) {
        // 假人死亡：记录到状态，不弹主玩家死亡界面
        if (this.fakePlayer != null) {
            this.session.getState().setHealth(0.0F);
        }
    }

    @Override
    public void handleSetCamera(net.minecraft.network.protocol.game.ClientboundSetCameraPacket packet) {
        // 假人无头，不切换主玩家相机；若假人骑乘则视角跟随坐骑由本地模拟处理
        if (this.fakePlayer != null && this.fakePlayer.isSpectator()) {
            // 假人是旁观者时记录到状态（无 UI）
        }
    }

    @Override
    public void handleCustomChatCompletions(net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket packet) {
        // 假人无头，聊天补全忽略
    }

    @Override
    public void handleDeleteChat(net.minecraft.network.protocol.game.ClientboundDeleteChatPacket packet) {
        // 假人聊天删除忽略（聊天已记录在 state）
    }
}

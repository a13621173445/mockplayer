package com.mockplayer.session.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 ClientPacketListener 的私有字段，供 FakePlayListener（继承类）访问假人自己的 world 状态。
 *
 * 假人 listener 需要填自己的 level/levelData/chunkRadius 等 private 字段，
 * 必须通过 @Accessor 暴露。纯加法，不影响主玩家逻辑。
 * 本接口在 common 包 com.mockplayer.session.accessor，通过独立的 mixins.json 注册。
 * 注意：mixin 包内的所有类都会被 Mixin 特殊处理，所以 accessor 单独放一个子包，
 * 避免和 SessionManager 等业务类同包导致误处理。
 */
@Mixin(ClientPacketListener.class)
public interface MockplayerClientPacketListenerAccessor {

    @Accessor("level")
    ClientLevel mockplayer$getLevel();

    @Accessor("level")
    void mockplayer$setLevel(ClientLevel level);

    @Accessor("levelData")
    void mockplayer$setLevelData(ClientLevel.ClientLevelData levelData);

    @Accessor("levelData")
    ClientLevel.ClientLevelData mockplayer$getLevelData();

    @Accessor("serverChunkRadius")
    void mockplayer$setServerChunkRadius(int radius);

    @Accessor("serverChunkRadius")
    int mockplayer$getServerChunkRadius();

    @Accessor("serverSimulationDistance")
    void mockplayer$setServerSimulationDistance(int distance);

    @Accessor("serverSimulationDistance")
    int mockplayer$getServerSimulationDistance();

    @Accessor("levels")
    void mockplayer$setLevels(Set<ResourceKey<Level>> levels);

    /**
     * 注册表访问（registryAccess）：方块实体/实体数据包解码需要，
     * 从假人 listener 自己的字段读取，不碰主玩家。
     * 26.2 里字段类型是 RegistryAccess.Frozen。
     */
    @Accessor("registryAccess")
    net.minecraft.core.RegistryAccess.Frozen mockplayer$getRegistryAccess();

    /**
     * 标记客户端已加载完成。LocalPlayer.tick() 开头检查 hasClientLoaded()，
     * 假人必须调用 setClientLoaded(true)，否则 fakePlayer.tick() 里的物理（super.tick）不执行。
     */
    @Invoker("setClientLoaded")
    void mockplayer$setClientLoaded(boolean loaded);

    /**
     * removedPlayerVehicleId：假人坐骑被移除时记录 ID（供 handleTeleportEntity 兜底应用到假人），
     * 与原版父类逻辑一致。
     */
    @Accessor("removedPlayerVehicleId")
    java.util.OptionalInt mockplayer$getRemovedPlayerVehicleId();

    @Accessor("removedPlayerVehicleId")
    void mockplayer$setRemovedPlayerVehicleId(java.util.OptionalInt id);

    /**
     * 通知服务端玩家已加载（发 ServerboundPlayerLoadedPacket）+ setClientLoaded(true)。
     * 假人收到 chunk 包（handleLevelChunkWithLight）后调用，恢复物理。
     */
    @Invoker("notifyPlayerLoaded")
    void mockplayer$notifyPlayerLoaded();

    /**
     * 应用位置包到指定实体（handleMovePlayer/handleRotatePlayer 用）。
     * 父类 handleMovePlayer 内部用 this.minecraft.player，假人 override 时用此方法操作假人。
     * 目标是 static 方法，故 @Invoker 必须声明 static。
     */
    @Invoker("setValuesFromPositionPacket")
    static boolean mockplayer$setValuesFromPositionPacket(
            net.minecraft.world.entity.PositionMoveRotation change,
            Set<net.minecraft.world.entity.Relative> relatives,
            Entity entity,
            boolean interpolate
    ) {
        throw new AssertionError();
    }
}

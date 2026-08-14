package com.mockplayer.session.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 ClientLevel 的 protected 地图数据方法（getAllMapData/addMapData）。
 *
 * 假人跨维重生时需要迁移地图数据（父类 ClientPacketListener 与 ClientLevel 同包
 * 可直接调 protected 方法，FakePlayListener 在不同包无法访问），用 @Invoker 暴露。
 */
@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public interface MockplayerClientLevelAccessor {

    /** 假人 level 的实体分区管理（level 结构记账用）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("entityStorage")
    net.minecraft.world.level.entity.TransientEntitySectionManager<?> mockplayer$getEntityStorage();

    /** 假人 level 的 ticking 实体表。 */
    @org.spongepowered.asm.mixin.gen.Accessor("tickingEntities")
    net.minecraft.world.level.entity.EntityTickList mockplayer$getTickingEntities();

    /** 假人 level 的光照更新队列（无头假人可能积压 Runnable 持有 DataLayer）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("lightUpdateQueue")
    java.util.Deque<Runnable> mockplayer$getLightUpdateQueue();

    /** 假人 level 的环境属性系统（采样器结构）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("environmentAttributes")
    net.minecraft.world.attribute.EnvironmentAttributeSystem mockplayer$getEnvironmentAttributes();

    /** 假人 level 的渲染器（26.1.2 破坏进度状态挂在 LevelRenderer 上，记账用）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("levelRenderer")
    net.minecraft.client.renderer.LevelRenderer mockplayer$getLevelRenderer();

    @Invoker("getAllMapData")
    java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mockplayer$getAllMapData();

    @Invoker("addMapData")
    void mockplayer$addMapData(java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mapData);

    /**
     * 方块状态预测处理器（包内私有方法）：handleMovePlayer 传送后清理方块预测，
     * 与原版父类 this.minecraft.level.getBlockStatePredictionHandler().onTeleport() 一致。
     */
    @Invoker("getBlockStatePredictionHandler")
    net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler mockplayer$getBlockStatePredictionHandler();
}

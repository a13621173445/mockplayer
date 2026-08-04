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

    @Invoker("getAllMapData")
    java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mockplayer$getAllMapData();

    @Invoker("addMapData")
    void mockplayer$addMapData(java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mapData);
}

package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露光照数据层表（DataLayer 的 Long2Object 表）。 */
@Mixin(DataLayerStorageMap.class)
public interface MockplayerDataLayerStorageMapAccessor {

    @Accessor("map")
    Long2ObjectOpenHashMap<DataLayer> mockplayer$getMap();
}

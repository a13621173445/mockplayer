package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露天空光存储的 topSections 表（字段在嵌套 SkyDataLayerStorageMap 里）。 */
@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
public interface MockplayerSkyLightSectionStorageAccessor {

    @Accessor("topSections")
    Long2IntOpenHashMap mockplayer$getTopSections();
}

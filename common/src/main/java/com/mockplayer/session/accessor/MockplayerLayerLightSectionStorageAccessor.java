package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露光照 section 存储的内部表（状态表/来源集合/数据层表）。 */
@Mixin(LayerLightSectionStorage.class)
public interface MockplayerLayerLightSectionStorageAccessor {

    @Accessor("sectionStates")
    Long2ByteMap mockplayer$getSectionStates();

    @Accessor("columnsWithSources")
    LongSet mockplayer$getColumnsWithSources();

    @Accessor("updatingSectionData")
    net.minecraft.world.level.lighting.DataLayerStorageMap<?> mockplayer$getUpdatingSectionData();

    @Accessor("visibleSectionData")
    net.minecraft.world.level.lighting.DataLayerStorageMap<?> mockplayer$getVisibleSectionData();

    @Accessor("queuedSections")
    Long2ObjectMap<DataLayer> mockplayer$getQueuedSections();

    @Accessor("changedSections")
    LongSet mockplayer$getChangedSections();

    @Accessor("sectionsAffectedByLightUpdates")
    LongSet mockplayer$getSectionsAffectedByLightUpdates();

    @Accessor("columnsToRetainQueuedDataFor")
    LongSet mockplayer$getColumnsToRetainQueuedDataFor();

    @Accessor("toRemove")
    LongSet mockplayer$getToRemove();
}

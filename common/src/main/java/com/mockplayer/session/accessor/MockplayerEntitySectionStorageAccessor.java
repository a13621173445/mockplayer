package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;

import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露实体分区表（sections 开放寻址表 + sectionIds 树）。 */
@Mixin(EntitySectionStorage.class)
public interface MockplayerEntitySectionStorageAccessor {

    @Accessor("sections")
    Long2ObjectMap<EntitySection<?>> mockplayer$getSections();

    @Accessor("sectionIds")
    LongSortedSet mockplayer$getSectionIds();
}

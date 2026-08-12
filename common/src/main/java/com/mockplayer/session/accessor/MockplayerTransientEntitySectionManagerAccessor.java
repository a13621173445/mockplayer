package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.TransientEntitySectionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露假人 level 的实体分区管理（分区表 + ticking 区块集合）。 */
@Mixin(TransientEntitySectionManager.class)
public interface MockplayerTransientEntitySectionManagerAccessor {

    @Accessor("sectionStorage")
    EntitySectionStorage<?> mockplayer$getSectionStorage();

    @Accessor("tickingChunks")
    LongSet mockplayer$getTickingChunks();
}

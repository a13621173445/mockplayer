package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.world.level.entity.EntityTickList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 EntityTickList 的 active/passive 表。 */
@Mixin(EntityTickList.class)
public interface MockplayerEntityTickListAccessor {

    @Accessor("active")
    Int2ObjectMap<?> mockplayer$getActive();

    @Accessor("passive")
    Int2ObjectMap<?> mockplayer$getPassive();
}

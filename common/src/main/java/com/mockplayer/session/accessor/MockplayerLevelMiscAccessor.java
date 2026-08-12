package com.mockplayer.session.accessor;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 Level 的小型直接字段（邻居更新器/生物群系管理器）。 */
@Mixin(Level.class)
public interface MockplayerLevelMiscAccessor {

    @Accessor("neighborUpdater")
    CollectingNeighborUpdater mockplayer$getNeighborUpdater();

    @Accessor("biomeManager")
    BiomeManager mockplayer$getBiomeManager();
}

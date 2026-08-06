package com.mockplayer.session.accessor;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 AbstractFurnaceMenu 的 private ContainerData data（烧制进度/燃料，无公共 getter）。
 */
@Mixin(AbstractFurnaceMenu.class)
public interface MockplayerAbstractFurnaceMenuAccessor {

    @Accessor("data")
    ContainerData mockplayer$getData();
}

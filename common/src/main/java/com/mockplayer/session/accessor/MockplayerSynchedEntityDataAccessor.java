package com.mockplayer.session.accessor;

import net.minecraft.network.syncher.SynchedEntityData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 SynchedEntityData 的 itemsById 数组（实体数据运行时结构）。 */
@Mixin(SynchedEntityData.class)
public interface MockplayerSynchedEntityDataAccessor {

    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] mockplayer$getItemsById();
}

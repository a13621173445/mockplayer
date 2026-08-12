package com.mockplayer.session.accessor;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 PalettedContainer.Data（私有 record）的 palette/storage。
 *
 * 私有嵌套类用 targets 指定；运行时 Mixin 会给该 record 装上本接口，
 * 从 PalettedContainerAccessor 拿到的 Object 可直接 cast 使用。
 */
@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public interface MockplayerPalettedContainerDataAccessor {

    @Accessor("palette")
    Palette<?> mockplayer$palette();

    @Accessor("storage")
    BitStorage mockplayer$storage();
}

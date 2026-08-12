package com.mockplayer.session.accessor;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 EnvironmentAttributeSystem$ValueSampler（私有嵌套类）的 layers 列表。 */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
public interface MockplayerValueSamplerAccessor {

    @Accessor("layers")
    List<?> mockplayer$getLayers();
}

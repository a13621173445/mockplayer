package com.mockplayer.session.accessor;

import net.minecraft.world.attribute.EnvironmentAttributeSystem;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露环境属性系统的采样器表。 */
@Mixin(EnvironmentAttributeSystem.class)
public interface MockplayerEnvironmentAttributeSystemAccessor {

    @Accessor("attributeSamplers")
    Map<?, ?> mockplayer$getAttributeSamplers();
}

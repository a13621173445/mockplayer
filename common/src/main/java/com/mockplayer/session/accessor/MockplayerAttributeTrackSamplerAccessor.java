package com.mockplayer.session.accessor;

import net.minecraft.util.KeyframeTrackSampler;
import net.minecraft.world.timeline.AttributeTrackSampler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 AttributeTrackSampler 的 argumentSampler（KeyframeTrackSampler）。 */
@Mixin(AttributeTrackSampler.class)
public interface MockplayerAttributeTrackSamplerAccessor {

    @Accessor("argumentSampler")
    KeyframeTrackSampler<?> mockplayer$getArgumentSampler();
}

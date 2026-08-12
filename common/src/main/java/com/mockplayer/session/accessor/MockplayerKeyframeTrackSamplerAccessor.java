package com.mockplayer.session.accessor;

import java.util.List;

import net.minecraft.util.KeyframeTrackSampler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 KeyframeTrackSampler 的 segments 列表。 */
@Mixin(KeyframeTrackSampler.class)
public interface MockplayerKeyframeTrackSamplerAccessor {

    @Accessor("segments")
    List<?> mockplayer$getSegments();
}

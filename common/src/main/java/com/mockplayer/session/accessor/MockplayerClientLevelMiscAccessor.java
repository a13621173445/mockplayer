package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露假人 level 的小型直接字段（tint 缓存/破坏进度/实体列表/处理器）。 */
@Mixin(ClientLevel.class)
public interface MockplayerClientLevelMiscAccessor {

    @Accessor("tintCaches")
    Object2ObjectArrayMap<?, ?> mockplayer$getTintCaches();

    @Accessor("destroyingBlocks")
    Int2ObjectMap<?> mockplayer$getDestroyingBlocks();

    @Accessor("destructionProgress")
    Long2ObjectMap<?> mockplayer$getDestructionProgress();

    @Accessor("players")
    List<?> mockplayer$getPlayers();

    @Accessor("dragonParts")
    List<?> mockplayer$getDragonParts();

    @Accessor("globallyRenderedBlockEntities")
    Set<?> mockplayer$getGloballyRenderedBlockEntities();
}

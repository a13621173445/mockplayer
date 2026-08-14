package com.mockplayer.session.accessor;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露假人 level 的小型直接字段（tint 缓存/实体列表/处理器）。
 * 26.1.2 破坏进度状态在 LevelRenderer（26.2 移入 ClientLevel），
 * 由 MockplayerLevelRendererAccessor 暴露。
 */
@Mixin(ClientLevel.class)
public interface MockplayerClientLevelMiscAccessor {

    @Accessor("tintCaches")
    Object2ObjectArrayMap<?, ?> mockplayer$getTintCaches();

    @Accessor("players")
    List<?> mockplayer$getPlayers();

    @Accessor("dragonParts")
    List<?> mockplayer$getDragonParts();

    @Accessor("globallyRenderedBlockEntities")
    Set<?> mockplayer$getGloballyRenderedBlockEntities();
}

/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mockplayer.baritone.launch.mixins;

import com.mockplayer.baritone.utils.accessor.IClientInputAccessor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ClientInput 字段访问器（moveVector/keyPresses 原版 protected，
 * InputOverrideHandler 直写输入用）。
 */
@Mixin(ClientInput.class)
public interface MixinClientInput extends IClientInputAccessor {

    @Accessor("moveVector")
    @Override
    void baritone$setMoveVector(Vec2 moveVector);

    @Accessor("keyPresses")
    @Override
    void baritone$setKeyPresses(Input keyPresses);
}

/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mockplayer.baritone.utils.accessor;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * ClientInput 的 moveVector/keyPresses 写入接口（原版字段 protected，
 * InputOverrideHandler 直写输入用；实现在 launch 源集的 MixinClientInput）。
 */
public interface IClientInputAccessor {

    void baritone$setMoveVector(Vec2 moveVector);

    void baritone$setKeyPresses(Input keyPresses);
}

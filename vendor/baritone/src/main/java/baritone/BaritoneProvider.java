/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mockplayer.baritone;

import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.IBaritoneProvider;
import com.mockplayer.baritone.api.cache.IWorldScanner;
import com.mockplayer.baritone.api.schematic.ISchematicSystem;
import com.mockplayer.baritone.cache.FasterWorldScanner;
import com.mockplayer.baritone.utils.schematic.SchematicSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Brady
 * @since 9/29/2018
 */
public final class BaritoneProvider implements IBaritoneProvider {

    private final List<IBaritone> all;
    private final List<IBaritone> allView;

    public BaritoneProvider() {
        this.all = new CopyOnWriteArrayList<>();
        this.allView = Collections.unmodifiableList(this.all);
    }

    @Override
    public IBaritone getPrimaryBaritone() {
        return this.all.isEmpty() ? null : this.all.get(0);
    }

    @Override
    public List<IBaritone> getAllBaritones() {
        return this.allView;
    }

    @Override
    public synchronized IBaritone createBaritone(Minecraft minecraft) {
        return this.createBaritone(minecraft, null, null);
    }

    @Override
    public synchronized IBaritone createBaritone(Minecraft minecraft,
                                                 LocalPlayer player,
                                                 MultiPlayerGameMode gameMode) {
        IBaritone baritone = player != null
                ? this.getBaritoneForPlayer(player)
                : this.getBaritoneForMinecraft(minecraft);
        if (baritone == null) {
            this.all.add(baritone = new Baritone(minecraft, player, gameMode));
        }
        return baritone;
    }

    @Override
    public synchronized boolean destroyBaritone(IBaritone baritone) {
        return this.all.remove(baritone);
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return FasterWorldScanner.INSTANCE;
    }

    @Override
    public ISchematicSystem getSchematicSystem() {
        return SchematicSystem.INSTANCE;
    }
}

/*
 * Beautiful Wake - the water behind a boat.
 * Copyright (C) 2026 Rusty Shackleford and nfx
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.nfx.beautifulwake;

import com.nfx.beautifulwake.client.SplashTracker;
import com.nfx.beautifulwake.client.WakeRenderer;
import com.nfx.beautifulwake.client.WakeTracker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The water behind a boat: a foam trail that spreads and fades, the two
 * arms of a real wake's V, and spray off the bow -- a touch of the same
 * behind a player or an animal crossing the surface -- and a splash, sized
 * to the thing and its fall, wherever anything goes into the water.
 *
 * <p>Client only. Every client already knows where every boat is and how
 * fast it moves, so each draws the wakes it can see and the server needs
 * nothing. The trail is sampled from where a craft actually was, tick by
 * tick, so a boat any mod moves any way makes a wake.
 */
@Mod(value = BeautifulWake.MOD_ID, dist = Dist.CLIENT)
public final class BeautifulWake {
    public static final String MOD_ID = "beautifulwake";

    public BeautifulWake(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, WakeConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(WakeTracker::onClientTick);
        NeoForge.EVENT_BUS.addListener(WakeTracker::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(SplashTracker::onClientTick);
        NeoForge.EVENT_BUS.addListener(SplashTracker::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(WakeRenderer::onRenderStage);
    }
}

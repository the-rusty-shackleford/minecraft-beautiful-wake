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

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client's knobs, {@code config/beautifulwake-client.toml}. Speeds are
 * blocks per tick; a rowed boat makes about 0.1, a boat at full speed on
 * open water about 0.4, a sprinting swimmer about 0.2.
 */
public final class WakeConfig {
    private WakeConfig() {}

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue SKIN;
    public static final ModConfigSpec.BooleanValue LINES;
    public static final ModConfigSpec.BooleanValue FOAM;
    public static final ModConfigSpec.BooleanValue SPRAY;
    public static final ModConfigSpec.DoubleValue MIN_SPEED;
    public static final ModConfigSpec.DoubleValue FULL_SPEED;
    public static final ModConfigSpec.DoubleValue LIFE_SECONDS;
    public static final ModConfigSpec.DoubleValue RELIEF;
    public static final ModConfigSpec.DoubleValue SCALE;
    public static final ModConfigSpec.IntValue SPRAY_MAX;
    public static final ModConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_WATERCRAFT;

    public static final ModConfigSpec.BooleanValue SPLASHES;
    public static final ModConfigSpec.BooleanValue SWIMMERS;
    public static final ModConfigSpec.DoubleValue SWIMMER_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue SWIMMER_FULL_SPEED;
    public static final ModConfigSpec.DoubleValue SWIMMER_STRENGTH;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("What is drawn behind a boat.").push("wake");
        SKIN = b.comment("The disturbed water inside the wake's V: a pale sheet with the bow wave, the stern's",
                        "trough and the chevron ridges standing up out of it, edged in white.")
                .define("skin", true);
        LINES = b.comment("The white lines along the chevron ridges inside the V.")
                .define("lines", true);
        FOAM = b.comment("The churn behind the stern and the bubbles thrown up off the bow.")
                .define("foam", true);
        SPRAY = b.comment("Droplets thrown off the bow at speed, as particles.")
                .define("spray", true);
        MIN_SPEED = b.comment("Below this speed (blocks per tick) there is no wake; 0.075 is a slow paddle.")
                .defineInRange("minSpeed", 0.075, 0.0, 5.0);
        FULL_SPEED = b.comment("At this speed the wake is at full strength; 0.35 is a boat near its top speed.")
                .defineInRange("fullSpeed", 0.35, 0.01, 10.0);
        LIFE_SECONDS = b.comment("How long the wake lasts on the water, in seconds.")
                .defineInRange("lifeSeconds", 4.5, 0.5, 30.0);
        RELIEF = b.comment("How high the wake stands out of the water, 1 being a bow wave a fifth of a block tall;",
                        "0 lays it flat.")
                .defineInRange("relief", 1.0, 0.0, 3.0);
        SCALE = b.comment("The wake's overall size: how far behind the hull the sheet and its lines reach, how high",
                        "it stands and how many bubbles the bow throws. 1 is a big wake for a rowboat; 0.7 fits one.")
                .defineInRange("scale", 0.7, 0.1, 2.0);
        SPRAY_MAX = b.comment("Droplets thrown per tick at full speed; 0 for none.")
                .defineInRange("sprayMax", 8, 0, 40);
        MAX_DISTANCE = b.comment("Craft farther than this from the camera, in blocks, get no wake.")
                .defineInRange("maxDistance", 96.0, 8.0, 512.0);
        EXTRA_WATERCRAFT = b.comment("Entity types counted as watercraft besides every boat (any entity whose class is",
                        "the vanilla boat's, which includes most modded boats), by id, e.g. \"smallships:cog\".")
                .defineListAllowEmpty("extraWatercraft", List.of(), e -> e instanceof String s && s.contains(":"));
        b.pop();

        b.comment("What the water does when something goes into it.").push("splashes");
        SPLASHES = b.comment("A ring, droplets and bubbles where anything enters the water -- a dropped item too,",
                        "sized by what it is and how fast it fell. The game's own splash for living things stays.")
                .define("enabled", true);
        b.pop();

        b.comment("A touch of the same behind a player or an animal crossing the surface.").push("swimmers");
        SWIMMERS = b.comment("Whether swimmers leave a wake at all. Nothing under water, nothing riding a boat.")
                .define("enabled", true);
        SWIMMER_MIN_SPEED = b.comment("Below this speed a swimmer leaves nothing; 0.03 is a slow wade.")
                .defineInRange("minSpeed", 0.03, 0.0, 5.0);
        SWIMMER_FULL_SPEED = b.comment("At this speed a swimmer's wake is at its strongest; 0.14 is a brisk wade or an easy swim.")
                .defineInRange("fullSpeed", 0.14, 0.01, 10.0);
        SWIMMER_STRENGTH = b.comment("How strong a swimmer's wake is next to a boat's, 0 to 1.")
                .defineInRange("strength", 0.9, 0.05, 1.0);
        b.pop();

        SPEC = b.build();
    }

    /** effects: returns the wake's life in ticks, at least one */
    public static int lifeTicks() {
        return Math.max(1, (int) Math.round(LIFE_SECONDS.get() * 20.0));
    }
}

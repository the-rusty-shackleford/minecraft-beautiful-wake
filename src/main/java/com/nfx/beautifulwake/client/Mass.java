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
package com.nfx.beautifulwake.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;

/**
 * How heavy a thing is, for the size of its splash. A plain number, about
 * one for a player: the game has no weights, so these are judgements --
 * an apple is light, an ingot is dense, a block is heavy, a stack is heavier
 * than one, and anything else is sized by its body.
 */
public final class Mass {
    private Mass() {}

    private static final TagKey<Item> INGOTS = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots"));
    private static final TagKey<Item> NUGGETS = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:nuggets"));
    private static final TagKey<Item> GEMS = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:gems"));
    private static final TagKey<Item> STORAGE_BLOCKS = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:storage_blocks"));

    /** effects: returns {@code entity}'s mass for a splash: a dropped item's by what it is, anything else's by its size */
    public static double of(Entity entity) {
        if (entity instanceof ItemEntity item) {
            return ofStack(item.getItem());
        }
        // A body: width squared by height, so a player is about one, a cow
        // about two, an arrow next to nothing.
        double w = entity.getBbWidth();
        return Math.max(0.02, w * w * entity.getBbHeight() * 1.6);
    }

    /**
     * effects: returns a stack's mass: one of the item by kind, times the
     * square root of the count so a stack of sixty-four is eight times one
     * and not sixty-four
     */
    public static double ofStack(ItemStack stack) {
        double one;
        if (stack.is(STORAGE_BLOCKS)) {
            one = 2.0;
        } else if (stack.getItem() instanceof BlockItem) {
            one = 1.2;
        } else if (stack.is(INGOTS) || stack.is(GEMS)) {
            one = 0.9;
        } else if (stack.getItem() instanceof TieredItem || stack.getItem() instanceof ArmorItem) {
            one = 1.0;
        } else if (stack.is(NUGGETS)) {
            one = 0.3;
        } else if (stack.has(DataComponents.FOOD)) {
            one = 0.35;
        } else {
            one = 0.5;
        }
        return one * Math.sqrt(Math.max(1, stack.getCount()));
    }
}

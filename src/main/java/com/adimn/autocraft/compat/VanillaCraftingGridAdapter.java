package com.adimn.autocraft.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;

/**
 * 原版 3×3 合成台适配器：CraftingMenu 的固定槽位布局。
 */
public final class VanillaCraftingGridAdapter implements CraftingGridAdapter {

    private static final List<Integer> GRID = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);

    @Override
    public boolean matches(AbstractContainerMenu menu) {
        return menu instanceof CraftingMenu;
    }

    @Override
    public int getResultSlotId(AbstractContainerMenu menu) {
        return 0;
    }

    @Override
    public List<Integer> getGridSlotIds(AbstractContainerMenu menu) {
        return GRID;
    }

    @Override
    public List<Integer> getSourceSlotIds(AbstractContainerMenu menu) {
        List<Integer> ids = new ArrayList<>(36);
        for (int i = 10; i <= 45; i++) {
            ids.add(i);
        }
        return ids;
    }

    @Override
    public boolean usesRecipeBookPlacement() {
        return true;
    }

    @Override
    public String name() {
        return "vanilla";
    }
}

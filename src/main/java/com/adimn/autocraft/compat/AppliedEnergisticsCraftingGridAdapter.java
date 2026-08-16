package com.adimn.autocraft.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.adimn.autocraft.util.Log;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Applied Energistics 2 合成终端适配器（反射，无编译期依赖）。
 *
 * 识别 CraftingTermMenu / WirelessCraftingTermMenu，通过槽位类名找到合成格与结果槽；
 * 来源槽暂用玩家物品栏（AE2 网络库存走 mod 专用 transfer，后续再扩展）。
 */
public final class AppliedEnergisticsCraftingGridAdapter implements CraftingGridAdapter {

    private static final String MENU_CLASS = "appeng.menu.me.items.CraftingTermMenu";
    private static final String WIRELESS_MENU_CLASS = "appeng.menu.me.items.WirelessCraftingTermMenu";
    private static final String GRID_SLOT_CLASS = "appeng.menu.slot.CraftingMatrixSlot";
    private static final String RESULT_SLOT_CLASS = "appeng.menu.slot.CraftingTermSlot";

    @Override
    public boolean matches(AbstractContainerMenu menu) {
        Class<?> menuClass = CompatReflection.clazz(MENU_CLASS);
        Class<?> wirelessClass = CompatReflection.clazz(WIRELESS_MENU_CLASS);
        return (menuClass != null && menuClass.isInstance(menu))
                || (wirelessClass != null && wirelessClass.isInstance(menu));
    }

    @Override
    public int getResultSlotId(AbstractContainerMenu menu) {
        return CompatReflection.firstSlotIdByClassName(menu, RESULT_SLOT_CLASS);
    }

    @Override
    public List<Integer> getGridSlotIds(AbstractContainerMenu menu) {
        return CompatReflection.slotIdsByClassName(menu, GRID_SLOT_CLASS);
    }

    @Override
    public List<Integer> getSourceSlotIds(AbstractContainerMenu menu) {
        return CompatReflection.playerInventorySlotIds(menu);
    }

    @Override
    public boolean usesNetworkFill() {
        return true;
    }

    @Override
    public void fillGrid(AbstractContainerMenu menu, CraftingRecipe recipe) {
        try {
            Class<?> helper = CompatReflection.clazz("appeng.integration.modules.jeirei.CraftingHelper");
            if (helper == null) {
                return;
            }
            Method perform = helper.getMethod("performTransfer", menu.getClass(), Recipe.class, boolean.class);
            perform.invoke(null, menu, recipe, false);
        } catch (Throwable t) {
            Log.warn("AE2 网络填格失败：" + t);
        }
    }

    @Override
    public List<ItemStack> getNetworkItemStacks(AbstractContainerMenu menu) {
        List<ItemStack> result = new ArrayList<>();
        try {
            Method getRepo = menu.getClass().getMethod("getClientRepo");
            Object repo = getRepo.invoke(menu);
            if (repo == null) {
                return result;
            }
            Method getAll = repo.getClass().getMethod("getAllEntries");
            Object entries = getAll.invoke(repo);
            if (!(entries instanceof Collection<?> collection)) {
                return result;
            }
            for (Object entry : collection) {
                Method getWhat = entry.getClass().getMethod("getWhat");
                Method getStoredAmount = entry.getClass().getMethod("getStoredAmount");
                Object key = getWhat.invoke(entry);
                long amount = (Long) getStoredAmount.invoke(entry);
                if (key == null || amount <= 0) {
                    continue;
                }
                if (key.getClass().getName().equals("appeng.items.storage.AEItemKey")
                        || key.getClass().getName().endsWith(".AEItemKey")) {
                    try {
                        Method toStack = key.getClass().getMethod("toStack", int.class);
                        result.add((ItemStack) toStack.invoke(key, (int) Math.min(amount, Integer.MAX_VALUE)));
                    } catch (NoSuchMethodException e) {
                        Method toStack = key.getClass().getMethod("toStack");
                        result.add((ItemStack) toStack.invoke(key));
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("AE2 网络库存快照失败：" + t);
        }
        return result;
    }

    @Override
    public String name() {
        return "ae2";
    }
}

package com.adimn.autocraft.compat;

import java.util.List;

import com.adimn.autocraft.util.Log;

import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 合成格适配器注册表：按顺序尝试匹配当前菜单。
 * 原版在最前，可选 mod 适配器随后（反射探测，缺 mod 自动跳过）。
 */
public final class CraftingGridAdapters {

    private static final List<CraftingGridAdapter> ADAPTERS = List.of(
            new VanillaCraftingGridAdapter(),
            new RefinedStorageCraftingGridAdapter(),
            new AppliedEnergisticsCraftingGridAdapter(),
            new SophisticatedBackpacksCraftingGridAdapter()
    );

    private CraftingGridAdapters() {
    }

    /** 找到能处理当前菜单的适配器；找不到返回 null。 */
    public static CraftingGridAdapter find(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        for (CraftingGridAdapter adapter : ADAPTERS) {
            try {
                if (adapter.matches(menu)) {
                    return adapter;
                }
            } catch (Throwable t) {
                Log.debug("适配器匹配异常 " + adapter.name() + ": " + t);
            }
        }
        return null;
    }
}

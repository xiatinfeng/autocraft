package com.adimn.autocraft.compat;

import java.util.List;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/**
 * 合成格适配器：把“当前打开的容器菜单”抽象成一个 3×3 合成台。
 *
 * 原版 CraftingMenu 用默认实现；AE2/RS/精妙背包等可选 mod 通过反射按需适配，
 * 不强制编译期依赖 mod 类。
 */
public interface CraftingGridAdapter {

    /** 当前菜单是否由本适配器处理。 */
    boolean matches(AbstractContainerMenu menu);

    /** 是否需要在开始执行前做准备工作（例如自动打开背包合成升级 Tab）。 */
    default boolean needsPreparation(AbstractContainerMenu menu) {
        return false;
    }

    /** 执行准备动作（必须在客户端线程调用）。 */
    default void prepare(AbstractContainerMenu menu) {
    }

    /** 结果槽 slot id。 */
    int getResultSlotId(AbstractContainerMenu menu);

    /** 3×3 网格槽 slot id，按左上→右下的顺序，长度 9。 */
    List<Integer> getGridSlotIds(AbstractContainerMenu menu);

    /** 可作为原料来源的槽位 id（不含网格/结果）。 */
    List<Integer> getSourceSlotIds(AbstractContainerMenu menu);

    /**
     * 是否使用 mod 自带的“网络填格”能力（AE2/RS 从网络库存直接填充合成格）。
     * 为 true 时，执行器不手动从 source 槽取料，而是在每步前调用 {@link #fillGrid}。
     */
    default boolean usesNetworkFill() {
        return false;
    }

    /** 让 mod 把当前配方从网络/存储填充到合成格（客户端线程调用）。 */
    default void fillGrid(AbstractContainerMenu menu, CraftingRecipe recipe) {
        fillGrid(menu, recipe, 1);
    }

    /**
     * 带批次数量的网络填格：一次放入 batches 份材料，用于可堆叠产物批量合成。
     * 默认实现退化为单批填格。
     */
    default void fillGrid(AbstractContainerMenu menu, CraftingRecipe recipe, int batches) {
        fillGrid(menu, recipe);
    }

    /** 返回网络/存储中的物品快照（用于规划时计入库存）；无则返回空表。 */
    default List<ItemStack> getNetworkItemStacks(AbstractContainerMenu menu) {
        return List.of();
    }

    /**
     * 槽位是否为虚拟槽（如 Sophisticated 的合成升级槽）。
     * 虚拟槽不在 menu.slots 里，客户端不能走 MultiPlayerGameMode.handleInventoryMouseClick，
     * 需要直接发送 ServerboundContainerClickPacket 由服务端 getSlot() 映射处理。
     */
    default boolean usesVirtualSlotIds() {
        return false;
    }

    /**
     * 是否支持原版配方书填格（ServerboundPlaceRecipePacket）。
     * 原版 CraftingMenu 支持，可以用一个数据包快速填满合成格，避免逐槽点击。
     */
    default boolean usesRecipeBookPlacement() {
        return false;
    }

    /** 适配器名称（日志用）。 */
    String name();
}

package com.adimn.autocraft.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.adimn.autocraft.util.Log;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * Refined Storage 网格终端适配器（反射，无编译期依赖）。
 *
 * 识别 GridContainerMenu，通过槽位类名找到 3×3 合成格与结果槽；
 * 来源槽暂用玩家物品栏（网络库存走 mod 专用 transfer，后续再扩展）。
 */
public final class RefinedStorageCraftingGridAdapter implements CraftingGridAdapter {

    private static final String MENU_CLASS = "com.refinedmods.refinedstorage.container.GridContainerMenu";
    private static final String GRID_SLOT_CLASS = "com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot";
    private static final String RESULT_SLOT_CLASS = "com.refinedmods.refinedstorage.container.slot.grid.ResultCraftingGridSlot";

    @Override
    public boolean matches(AbstractContainerMenu menu) {
        Class<?> menuClass = CompatReflection.clazz(MENU_CLASS);
        return menuClass != null && menuClass.isInstance(menu);
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
        fillGrid(menu, recipe, 1);
    }

    @Override
    public void fillGrid(AbstractContainerMenu menu, CraftingRecipe recipe, int batches) {
        try {
            Class<?> rsClass = CompatReflection.clazz("com.refinedmods.refinedstorage.RS");
            Class<?> msgClass = CompatReflection.clazz(
                    "com.refinedmods.refinedstorage.network.grid.GridTransferMessage");
            if (rsClass == null || msgClass == null) {
                return;
            }
            Field handlerField = rsClass.getField("NETWORK_HANDLER");
            Object handler = handlerField.get(null);
            Constructor<?> ctor = msgClass.getConstructor(List.class);

            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            List<List<ItemStack>> inputs = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                inputs.add(List.of());
            }
            // 必须按配方形状把原料放到 3x3 网格的正确位置，不能直接把 getIngredients() 平铺。
            // 例如木棍是 1x2 竖排，getIngredients() 只有 2 项；平铺会变成横排两根木板 = 压力板。
            if (recipe instanceof ShapedRecipe shaped) {
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        Ingredient ingredient = ingredients.get(row * width + col);
                        if (!ingredient.isEmpty()) {
                            inputs.set(row * 3 + col, scaleStacks(ingredient.getItems(), batches));
                        }
                    }
                }
            } else {
                int index = 0;
                for (Ingredient ingredient : ingredients) {
                    if (!ingredient.isEmpty()) {
                        inputs.set(index++, scaleStacks(ingredient.getItems(), batches));
                    }
                }
            }
            Object msg = ctor.newInstance(inputs);
            Log.info("RS 网络填格：recipe=" + recipe.getId() + " batches=" + batches + " inputs=" + inputs);
            Method send = handler.getClass().getMethod("sendToServer", Object.class);
            send.invoke(handler, msg);
        } catch (Throwable t) {
            Log.warn("RS 网络填格失败：" + t);
        }
    }

    /** 把每个可能的原料栈数量放大到 batches，让 RS 一次从网络提取多份放入合成格。 */
    private static List<ItemStack> scaleStacks(ItemStack[] stacks, int batches) {
        if (batches <= 1) {
            return List.of(stacks);
        }
        List<ItemStack> scaled = new ArrayList<>(stacks.length);
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            copy.setCount(batches);
            scaled.add(copy);
        }
        return scaled;
    }

    @Override
    public List<ItemStack> getNetworkItemStacks(AbstractContainerMenu menu) {
        List<ItemStack> result = new ArrayList<>();
        try {
            Method getProvider = menu.getClass().getMethod("getScreenInfoProvider");
            Object provider = getProvider.invoke(menu);
            if (provider == null) {
                return result;
            }
            Method getView = provider.getClass().getMethod("getView");
            Object view = getView.invoke(provider);
            if (view == null) {
                return result;
            }
            Method getStacks = view.getClass().getMethod("getStacks");
            Object stacks = getStacks.invoke(view);
            if (!(stacks instanceof List<?> list)) {
                return result;
            }
            for (Object gridStack : list) {
                Method getIngredient = gridStack.getClass().getMethod("getIngredient");
                Method getQuantity = gridStack.getClass().getMethod("getQuantity");
                Object ingredient = getIngredient.invoke(gridStack);
                int quantity = (Integer) getQuantity.invoke(gridStack);
                if (ingredient instanceof ItemStack stack && !stack.isEmpty() && quantity > 0) {
                    ItemStack copy = stack.copy();
                    // 网络数量可能超过单格最大堆叠；快照仅用于规划计数，不需要按 maxStackSize 截断。
                    copy.setCount(quantity);
                    result.add(copy);
                }
            }
        } catch (Throwable t) {
            Log.warn("RS 网络库存快照失败：" + t);
        }
        return result;
    }

    @Override
    public String name() {
        return "refined-storage";
    }
}

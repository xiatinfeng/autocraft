package com.adimn.autocraft.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.adimn.autocraft.util.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * 精妙背包 / 精妙核心合成升级适配器（反射，无编译期依赖）。
 *
 * 参考 Sophisticated Core 的 EMI 集成：如果背包里有合成升级但没打开，
 * 先自动打开合成 Tab，再按通用“网格槽/结果槽/来源槽”执行。
 */
public final class SophisticatedBackpacksCraftingGridAdapter implements CraftingGridAdapter {

    private static final String STORAGE_MENU_CLASS = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase";
    private static final String BACKPACK_MENU_CLASS = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";

    @Override
    public boolean matches(AbstractContainerMenu menu) {
        Class<?> storageClass = CompatReflection.clazz(STORAGE_MENU_CLASS);
        Class<?> backpackClass = CompatReflection.clazz(BACKPACK_MENU_CLASS);
        return (storageClass != null && storageClass.isInstance(menu))
                || (backpackClass != null && backpackClass.isInstance(menu));
    }

    @Override
    public boolean needsPreparation(AbstractContainerMenu menu) {
        Optional<Object> crafting = findCraftingContainer(menu);
        return crafting.map(c -> {
            try {
                Method isOpen = c.getClass().getMethod("isOpen");
                return !(Boolean) isOpen.invoke(c);
            } catch (Throwable ignored) {
                return false;
            }
        }).orElse(false);
    }

    @Override
    public void prepare(AbstractContainerMenu menu) {
        Optional<Object> crafting = findCraftingContainer(menu);
        if (crafting.isEmpty()) {
            return;
        }
        Object c = crafting.get();
        try {
            Method isOpen = c.getClass().getMethod("isOpen");
            if ((Boolean) isOpen.invoke(c)) {
                return;
            }
            Method setIsOpen = c.getClass().getMethod("setIsOpen", boolean.class);
            setIsOpen.invoke(c, true);
            Method getUpgradeContainerId = c.getClass().getMethod("getUpgradeContainerId");
            int id = (Integer) getUpgradeContainerId.invoke(c);
            Method setOpenTabId = menu.getClass().getMethod("setOpenTabId", int.class);
            setOpenTabId.invoke(menu, id);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int getResultSlotId(AbstractContainerMenu menu) {
        List<Integer> grid = getGridSlotIds(menu);
        Set<Integer> gridSet = new HashSet<>(grid);
        Player player = Minecraft.getInstance().player;
        // Sophisticated 的合成升级槽不在 menu.slots 里，而是通过 StorageContainerMenuBase.getSlot(int)
        // 映射到 upgradeSlots；所以必须按虚拟槽位号遍历，不能只扫 menu.slots。
        try {
            Method getTotal = menu.getClass().getMethod("getTotalSlotsNumber");
            int total = (Integer) getTotal.invoke(menu);
            for (int i = 0; i < total; i++) {
                Slot slot = menu.getSlot(i);
                if (slot instanceof ResultSlot && !gridSet.contains(i)
                        && (player == null || slot.container != player.getInventory())) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
            // 反射失败时回退到旧逻辑
            for (Slot slot : menu.slots) {
                if (slot instanceof ResultSlot && !gridSet.contains(slot.index)
                        && (player == null || slot.container != player.getInventory())) {
                    return slot.index;
                }
            }
        }
        return -1;
    }

    @Override
    public List<Integer> getGridSlotIds(AbstractContainerMenu menu) {
        List<Integer> ids = new ArrayList<>();
        Optional<Object> crafting = findCraftingContainer(menu);
        if (crafting.isEmpty()) {
            return ids;
        }
        try {
            Method getRecipeSlots = crafting.get().getClass().getMethod("getRecipeSlots");
            Object result = getRecipeSlots.invoke(crafting.get());
            if (result instanceof List<?> list) {
                for (Object obj : list) {
                    if (obj instanceof Slot slot) {
                        ids.add(slot.index);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    @Override
    public List<Integer> getSourceSlotIds(AbstractContainerMenu menu) {
        Set<Integer> excluded = new HashSet<>();
        excluded.add(getResultSlotId(menu));
        excluded.addAll(getGridSlotIds(menu));
        List<Integer> sources = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!excluded.contains(slot.index) && slot.isActive()) {
                sources.add(slot.index);
            }
        }
        return sources;
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
            Class<?> msgClass = CompatReflection.clazz(
                    "net.p3pp3rf1y.sophisticatedcore.compat.jei.TransferRecipeMessage");
            Class<?> handlerClass = CompatReflection.clazz(
                    "net.p3pp3rf1y.sophisticatedcore.network.PacketHandler");
            if (msgClass == null || handlerClass == null) {
                Log.warn("Sophisticated 填格：未找到 TransferRecipeMessage/PacketHandler");
                return;
            }
            List<Integer> gridIds = getGridSlotIds(menu);
            List<Integer> sourceIds = getSourceSlotIds(menu);
            List<Ingredient> gridIngredients = gridIngredients(recipe);
            Map<Integer, Integer> matching = new LinkedHashMap<>();
            for (int i = 0; i < 9; i++) {
                Ingredient ingredient = gridIngredients.get(i);
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                int source = findSourceSlot(menu, sourceIds, ingredient);
                if (source < 0) {
                    Log.warn("Sophisticated 填格缺料：网格槽 " + gridIds.get(i)
                            + " 需要 " + ingredient + "，背包/存储中没有匹配物品");
                    return;
                }
                matching.put(gridIds.get(i), source);
            }
            Constructor<?> ctor = msgClass.getConstructor(ResourceLocation.class, Map.class, List.class, List.class, boolean.class);
            Object msg = ctor.newInstance(recipe.getId(), matching, gridIds, sourceIds, false);
            Field instanceField = handlerClass.getField("INSTANCE");
            Object handler = instanceField.get(null);
            Method send = handler.getClass().getMethod("sendToServer", Object.class);
            Log.info("Sophisticated 填格：recipe=" + recipe.getId() + " matching=" + matching);
            send.invoke(handler, msg);
        } catch (Throwable t) {
            Log.warn("Sophisticated 填格失败：" + t);
        }
    }

    @Override
    public boolean usesVirtualSlotIds() {
        return true;
    }

    @Override
    public String name() {
        return "sophisticated-backpacks";
    }

    /** 把配方原料按 3x3 网格位置展开（shaped 按宽高左上对齐，shapeless 从左上开始顺排）。 */
    private static List<Ingredient> gridIngredients(CraftingRecipe recipe) {
        List<Ingredient> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(Ingredient.EMPTY);
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    grid.set(row * 3 + col, ingredients.get(row * width + col));
                }
            }
        } else {
            int index = 0;
            for (Ingredient ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    grid.set(index++, ingredient);
                }
            }
        }
        return grid;
    }

    /** 在来源槽中找一个能匹配该配方的槽位。 */
    private static int findSourceSlot(AbstractContainerMenu menu, List<Integer> sourceIds, Ingredient ingredient) {
        for (int id : sourceIds) {
            ItemStack stack = menu.getSlot(id).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            for (ItemStack alternative : ingredient.getItems()) {
                if (!alternative.isEmpty() && ItemStack.isSameItemSameTags(alternative, stack)) {
                    return id;
                }
            }
        }
        return -1;
    }

    private Optional<Object> findCraftingContainer(AbstractContainerMenu menu) {
        Class<?> storageClass = CompatReflection.clazz(STORAGE_MENU_CLASS);
        if (storageClass == null || !storageClass.isInstance(menu)) {
            return Optional.empty();
        }
        // 兼容不同 Sophisticated Core 版本：
        //  - 0.6.22.611 及部分版本：getOpenOrFirstCraftingContainer() 无参
        //  - 另一些版本：getOpenOrFirstCraftingContainer(RecipeType) 有参
        try {
            Method noArg = menu.getClass().getMethod("getOpenOrFirstCraftingContainer");
            Object result = noArg.invoke(menu);
            if (result instanceof Optional<?> opt && opt.isPresent()) {
                @SuppressWarnings("unchecked")
                Optional<Object> cast = (Optional<Object>) (Optional<?>) opt;
                return cast;
            }
        } catch (Throwable ignored) {
            // 无参版本不存在或调用失败时继续尝试有参版本
        }
        return CompatReflection.callOptional(menu, "getOpenOrFirstCraftingContainer", RecipeType.class,
                RecipeType.CRAFTING);
    }
}

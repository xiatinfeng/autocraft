package com.adimn.autocraft.util;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.adimn.autocraft.util.Log;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 动态读取 mod 特殊加工的工作方块图标。
 *
 * 优先使用内置原版图标；找不到时尝试通过 EMI 按输出物品查配方类别，
 * 再取该类别的第一个工作方块（workstation）作为图标。
 *
 * 全部走反射，EMI 未安装时安静返回 null，不会崩溃。
 */
public final class ProcessingIconProvider {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private ProcessingIconProvider() {
    }

    /** 根据加工方式和输出物品返回一个可用作图标的物品 id；找不到返回 null。 */
    public static String getIconItemId(String methodId, String outputItemId) {
        if (methodId == null || methodId.isBlank()) {
            return null;
        }
        String cacheKey = methodId + "|" + outputItemId;
        String cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String result = resolve(methodId, outputItemId);
        CACHE.put(cacheKey, result == null ? "" : result);
        return result;
    }

    private static String resolve(String methodId, String outputItemId) {
        // 1. 内置原版加工方式图标
        ManualProcessing.ManualMethod builtin = ManualProcessing.method(methodId);
        if (builtin != null && builtin.iconItemId() != null) {
            Log.info("ProcessingIconProvider: builtin icon for " + methodId + " -> " + builtin.iconItemId());
            return builtin.iconItemId();
        }
        // 2. 不再用“产物本身”当加工图标：会导致沙子显示成“用沙子加工沙子”的误导。
        //    直接进入 EMI/JEI 动态查询工作方块。
        // 3. EMI 动态查询工作方块
        Log.info("ProcessingIconProvider: try EMI for " + methodId + " output=" + outputItemId);
        String emi = emiWorkstationForOutput(outputItemId);
        if (emi != null) {
            Log.info("ProcessingIconProvider: EMI icon for " + outputItemId + " -> " + emi);
            return emi;
        }
        Log.info("ProcessingIconProvider: no EMI icon, try JEI for " + outputItemId);
        String jei = jeiWorkstationForOutput(outputItemId);
        if (jei != null) {
            Log.info("ProcessingIconProvider: JEI icon for " + outputItemId + " -> " + jei);
            return jei;
        }
        Log.info("ProcessingIconProvider: no JEI icon for " + outputItemId);
        return null;
    }

    private static boolean isBlockItem(String itemId) {
        try {
            Item item = resolveItem(itemId);
            return item != null && item instanceof net.minecraft.world.item.BlockItem
                    && item != net.minecraft.world.item.Items.AIR;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Item resolveItem(String itemId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            if (id == null) {
                return null;
            }
            return ForgeRegistries.ITEMS.getValue(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 通过 EMI 按输出物品查找第一个工作方块。 */
    private static String emiWorkstationForOutput(String outputItemId) {
        try {
            Class<?> emiApi = Class.forName("dev.emi.emi.api.EmiApi");
            Method getRecipeManager = emiApi.getMethod("getRecipeManager");
            Object manager = getRecipeManager.invoke(null);
            if (manager == null) {
                Log.warn("ProcessingIconProvider: EMI manager is null");
                return null;
            }
            Log.info("ProcessingIconProvider: EMI manager class=" + manager.getClass().getName());

            Item outputItem = resolveItem(outputItemId);
            if (outputItem == null) {
                Log.warn("ProcessingIconProvider: cannot resolve item " + outputItemId);
                return null;
            }

            Class<?> emiStackClass = Class.forName("dev.emi.emi.api.stack.EmiStack");
            Method of = emiStackClass.getMethod("of", ItemStack.class);
            Object outputStack = of.invoke(null, new ItemStack(outputItem));

            Class<?> managerClass = Class.forName("dev.emi.emi.api.recipe.EmiRecipeManager");
            Method getRecipesByOutput = managerClass.getMethod("getRecipesByOutput", emiStackClass);
            Object recipes = getRecipesByOutput.invoke(manager, outputStack);
            Log.info("ProcessingIconProvider: EMI recipes for " + outputItemId + " = "
                    + (recipes instanceof List<?> list ? list.size() : "not-list"));
            if (!(recipes instanceof List<?> list) || list.isEmpty()) {
                return null;
            }

            Object firstRecipe = list.get(0);
            Class<?> recipeClass = Class.forName("dev.emi.emi.api.recipe.EmiRecipe");
            Method getCategory = recipeClass.getMethod("getCategory");
            Object category = getCategory.invoke(firstRecipe);
            Log.info("ProcessingIconProvider: EMI category=" + category);

            Class<?> categoryClass = Class.forName("dev.emi.emi.api.recipe.EmiRecipeCategory");
            Method getWorkstations = managerClass.getMethod("getWorkstations", categoryClass);
            Object workstations = getWorkstations.invoke(manager, category);
            Log.info("ProcessingIconProvider: EMI workstations=" + workstations);
            if (!(workstations instanceof List<?> wsList) || wsList.isEmpty()) {
                return null;
            }

            for (Object workstation : wsList) {
                Method getEmiStacks = workstation.getClass().getMethod("getEmiStacks");
                Object stacks = getEmiStacks.invoke(workstation);
                if (!(stacks instanceof List<?> stackList)) {
                    continue;
                }
                for (Object stack : stackList) {
                    Method getItemStack = stack.getClass().getMethod("getItemStack");
                    Object is = getItemStack.invoke(stack);
                    if (is instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                        ResourceLocation key = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                        if (key != null) {
                            Log.info("ProcessingIconProvider: found workstation icon " + key);
                            return key.toString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("ProcessingIconProvider: EMI query failed for " + outputItemId + ": " + t);
        }
        return null;
    }

    /** 通过 JEI 按输出物品查找第一个工作方块（catalyst）。 */
    private static String jeiWorkstationForOutput(String outputItemId) {
        try {
            Class<?> internalClass = Class.forName("mezz.jei.common.Internal");
            Method getOptional = internalClass.getMethod("getOptionalJeiRuntime");
            Object opt = getOptional.invoke(null);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                Log.info("ProcessingIconProvider: JEI runtime not available");
                return null;
            }
            Object runtime = o.get();

            Class<?> iJeiRuntimeClass = Class.forName("mezz.jei.api.runtime.IJeiRuntime");
            Method getRecipeManager = iJeiRuntimeClass.getMethod("getRecipeManager");
            Object recipeManager = getRecipeManager.invoke(runtime);
            Method getJeiHelpers = iJeiRuntimeClass.getMethod("getJeiHelpers");
            Object jeiHelpers = getJeiHelpers.invoke(runtime);

            Class<?> iJeiHelpersClass = Class.forName("mezz.jei.api.helpers.IJeiHelpers");
            Method getFocusFactory = iJeiHelpersClass.getMethod("getFocusFactory");
            Object focusFactory = getFocusFactory.invoke(jeiHelpers);

            Item outputItem = resolveItem(outputItemId);
            if (outputItem == null) {
                return null;
            }
            ItemStack outputStack = new ItemStack(outputItem);

            Class<?> vanillaTypesClass = Class.forName("mezz.jei.api.constants.VanillaTypes");
            Object itemStackType = vanillaTypesClass.getField("ITEM_STACK").get(null);

            Class<?> recipeIngredientRoleClass = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
            Object outputRole = Enum.valueOf(recipeIngredientRoleClass.asSubclass(Enum.class), "OUTPUT");

            Class<?> iFocusFactoryClass = Class.forName("mezz.jei.api.recipe.IFocusFactory");
            Class<?> iIngredientTypeClass = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            Method createFocus = iFocusFactoryClass.getMethod("createFocus",
                    recipeIngredientRoleClass, iIngredientTypeClass, Object.class);
            Object focus = createFocus.invoke(focusFactory, outputRole, itemStackType, outputStack);

            Class<?> iRecipeManagerClass = Class.forName("mezz.jei.api.recipe.IRecipeManager");
            Method createCategoryLookup = iRecipeManagerClass.getMethod("createRecipeCategoryLookup");
            Object lookup = createCategoryLookup.invoke(recipeManager);

            Class<?> iRecipeCategoriesLookupClass = Class.forName("mezz.jei.api.recipe.IRecipeCategoriesLookup");
            Method limitFocus = iRecipeCategoriesLookupClass.getMethod("limitFocus", java.util.Collection.class);
            Object limited = limitFocus.invoke(lookup, java.util.List.of(focus));

            Method getCategories = iRecipeCategoriesLookupClass.getMethod("get");
            Object categoryStream = getCategories.invoke(limited);
            Method findFirstCategory = categoryStream.getClass().getMethod("findFirst");
            Object optCategory = findFirstCategory.invoke(categoryStream);
            if (!(optCategory instanceof Optional<?> oc) || oc.isEmpty()) {
                Log.info("ProcessingIconProvider: JEI no category for " + outputItemId);
                return null;
            }
            Object category = oc.get();

            Class<?> iRecipeCategoryClass = Class.forName("mezz.jei.api.recipe.category.IRecipeCategory");
            Method getRecipeType = iRecipeCategoryClass.getMethod("getRecipeType");
            Object recipeType = getRecipeType.invoke(category);

            Class<?> recipeTypeClass = Class.forName("mezz.jei.api.recipe.RecipeType");
            Method createCatalystLookup = iRecipeManagerClass.getMethod("createRecipeCatalystLookup", recipeTypeClass);
            Object catalystLookup = createCatalystLookup.invoke(recipeManager, recipeType);

            Class<?> iRecipeCatalystLookupClass = Class.forName("mezz.jei.api.recipe.IRecipeCatalystLookup");
            Method getItemStack = iRecipeCatalystLookupClass.getMethod("getItemStack");
            Object stackStream = getItemStack.invoke(catalystLookup);
            Method findFirstStack = stackStream.getClass().getMethod("findFirst");
            Object optStack = findFirstStack.invoke(stackStream);
            if (optStack instanceof Optional<?> os && os.isPresent()
                    && os.get() instanceof ItemStack is && !is.isEmpty()) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(is.getItem());
                if (key != null) {
                    return key.toString();
                }
            }
            Log.info("ProcessingIconProvider: JEI no catalyst ItemStack for " + outputItemId);
        } catch (Throwable t) {
            Log.warn("ProcessingIconProvider: JEI query failed for " + outputItemId + ": " + t);
        }
        return null;
    }
}

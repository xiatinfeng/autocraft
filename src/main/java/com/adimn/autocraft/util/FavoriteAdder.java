package com.adimn.autocraft.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 把物品加入 EMI 收藏 / JEI 书签。
 *
 * 优先 EMI（公开 EmiFavorites.addFavorite），失败/未装时尝试 JEI 内部 BookmarkList。
 * 全部反射，不强制依赖某个收藏 mod。
 */
public final class FavoriteAdder {

    private FavoriteAdder() {
    }

    /** 按物品 id 加入收藏；成功返回 true。 */
    public static boolean addFavorite(String itemId) {
        ItemStack stack = resolveStack(itemId);
        if (stack.isEmpty()) {
            return false;
        }
        if (tryEmi(stack)) {
            return true;
        }
        return tryJei(stack);
    }

    private static ItemStack resolveStack(String itemId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            if (id == null) {
                return ItemStack.EMPTY;
            }
            var item = ForgeRegistries.ITEMS.getValue(id);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean tryEmi(ItemStack stack) {
        try {
            Class<?> emiStackClass = Class.forName("dev.emi.emi.api.stack.EmiStack");
            Method of = emiStackClass.getMethod("of", ItemStack.class);
            Object emiStack = of.invoke(null, stack);

            Class<?> emiIngredientClass = Class.forName("dev.emi.emi.api.stack.EmiIngredient");
            Class<?> emiFavoritesClass = Class.forName("dev.emi.emi.runtime.EmiFavorites");
            Method add = emiFavoritesClass.getMethod("addFavorite", emiIngredientClass);
            add.invoke(null, emiStack);
            Log.info("FavoriteAdder: EMI favorite added for " + stack.getHoverName().getString());
            return true;
        } catch (Throwable t) {
            Log.info("FavoriteAdder: EMI favorite failed, try JEI: " + t);
            return false;
        }
    }

    private static boolean tryJei(ItemStack stack) {
        try {
            Class<?> internalClass = Class.forName("mezz.jei.common.Internal");
            Method getOptional = internalClass.getMethod("getOptionalJeiRuntime");
            Object opt = getOptional.invoke(null);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                return false;
            }
            Object runtime = o.get();

            Method getBookmarkOverlay = runtime.getClass().getMethod("getBookmarkOverlay");
            Object overlay = getBookmarkOverlay.invoke(runtime);
            if (overlay == null) {
                return false;
            }
            Field bookmarkListField = findField(overlay.getClass(), "bookmarkList");
            if (bookmarkListField == null) {
                return false;
            }
            bookmarkListField.setAccessible(true);
            Object bookmarkList = bookmarkListField.get(overlay);

            Method getIngredientManager = runtime.getClass().getMethod("getIngredientManager");
            Object ingredientManager = getIngredientManager.invoke(runtime);

            Class<?> vanillaTypesClass = Class.forName("mezz.jei.api.constants.VanillaTypes");
            Object itemStackType = vanillaTypesClass.getField("ITEM_STACK").get(null);

            Class<?> ingredientManagerClass = Class.forName("mezz.jei.api.runtime.IIngredientManager");
            Class<?> ingredientTypeClass = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            Method createTyped = ingredientManagerClass.getMethod("createTypedIngredient", ingredientTypeClass, Object.class);
            Object typedOpt = createTyped.invoke(ingredientManager, itemStackType, stack);
            if (!(typedOpt instanceof Optional<?> to) || to.isEmpty()) {
                return false;
            }
            Object typed = to.get();

            Class<?> ingredientBookmarkClass = Class.forName("mezz.jei.gui.bookmarks.IngredientBookmark");
            Class<?> typedIngredientClass = Class.forName("mezz.jei.api.ingredients.ITypedIngredient");
            Method create = ingredientBookmarkClass.getMethod("create", typedIngredientClass, ingredientManagerClass);
            Object bookmark = create.invoke(null, typed, ingredientManager);

            Class<?> iBookmarkClass = Class.forName("mezz.jei.gui.bookmarks.IBookmark");
            Method add = bookmarkList.getClass().getMethod("add", iBookmarkClass);
            add.invoke(bookmarkList, bookmark);
            Log.info("FavoriteAdder: JEI bookmark added for " + stack.getHoverName().getString());
            return true;
        } catch (Throwable t) {
            Log.info("FavoriteAdder: JEI bookmark failed: " + t);
            return false;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}

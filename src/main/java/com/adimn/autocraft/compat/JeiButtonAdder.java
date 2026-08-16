package com.adimn.autocraft.compat;

import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.Log;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.recipes.RecipesGui;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;

import java.util.Optional;

/**
 * JEI 配方屏（RecipesGui）齿轮按钮添加器。
 *
 * ⚠️ 本类直接 import JEI 内部类（mezz.jei.gui.recipes.RecipesGui），
 * 只允许在 {@link JeiGuard#isPresent()} 为 true 时由 JeiRecipeScreenHandler 调用——
 * 确保 JEI 未安装时本类永不加载。
 *
 * 按钮定位：RecipesGui.getArea()（配方区域，public 方法）右外侧。
 * 点击目标：RecipesGui.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)（public 方法，已 javap 核实）。
 */
public final class JeiButtonAdder {
    private static final ResourceLocation BUTTONS_TEXTURE =
            new ResourceLocation("autocraft", "textures/gui/buttons.png");
    private static final int ICON_SIZE = 12;
    private static final int TEX_SIZE = 16;

    private JeiButtonAdder() {}

    /** 由 ScreenEvent.Init.Post 调用（调用方已用类名字符串确认是 RecipesGui）。 */
    public static void addButton(Screen screen, ScreenEvent.Init.Post event) {
        if (!(screen instanceof RecipesGui jeiScreen)) {
            Log.warn("JEI addButton 收到非 RecipesGui：" + screen.getClass().getName());
            return;
        }
        ImmutableRect2i area = jeiScreen.getArea();
        int x = area.getX() + area.getWidth() - 14;
        int y = area.getY() + 4;
        Log.info("JEI 齿轮按钮位置：area=" + area + " button=[" + x + "," + y + "]");

        GearButton button = new GearButton(x, y, b -> triggerFor(jeiScreen));
        button.setTooltip(Tooltip.create(Component.literal("自动合成悬停物品")));
        event.addListener(button);  // Screen.addRenderableWidget 是 protected，走 Forge 事件公开入口
        Log.info("JEI 齿轮按钮已 addListener，位置 x=" + x + " y=" + y);
    }

    /** 供 JEI Mixin 点击按钮时调用。 */
    public static void triggerFor(RecipesGui jeiScreen) {
        Optional<ItemStack> hovered = jeiScreen.getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (hovered.isEmpty() || hovered.get().isEmpty()) {
            CraftExecutor.chat("请先在 JEI 配方屏悬停目标物品，再点齿轮按钮。");
            return;
        }
        ItemStack stack = hovered.get();
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            CraftExecutor.chat("无法识别物品：" + stack);
            return;
        }
        Log.info("JEI 齿轮按钮点击，目标=" + key + " x" + stack.getCount());
        OrderTrigger.order(com.adimn.autocraft.plan.MaterialRef.of(key.toString()), 1);
    }

    /** 供 JEI Mixin 按“当前配方布局”点击齿轮按钮时调用。 */
    public static void triggerForRecipe(Object recipeLayoutObj) {
        try {
            IRecipeLayoutDrawable<?> recipeLayout = (IRecipeLayoutDrawable<?>) recipeLayoutObj;
            Object recipe = recipeLayout.getRecipe();
            @SuppressWarnings("rawtypes")
            IRecipeCategory category = recipeLayout.getRecipeCategory();
            ResourceLocation recipeId = category.getRegistryName(recipe);
            if (recipeId == null) {
                CraftExecutor.chat("无法识别该配方 ID，无法自动合成。");
                return;
            }
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            mc.level.getRecipeManager().byKey(recipeId).ifPresent(r -> {
                ItemStack out = r.getResultItem(mc.level.registryAccess());
                if (out.isEmpty()) {
                    CraftExecutor.chat("该配方没有可识别的产出，无法自动合成。");
                    return;
                }
                ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(out.getItem());
                if (key == null) {
                    CraftExecutor.chat("无法识别产出物品，无法自动合成。");
                    return;
                }
                Log.info("JEI 配方按钮点击，配方=" + recipeId + " 目标=" + key);
                OrderTrigger.order(com.adimn.autocraft.plan.MaterialRef.of(key.toString()), 1);
            });
        } catch (Throwable t) {
            Log.warn("JEI 配方按钮点击失败：" + t);
        }
    }
}

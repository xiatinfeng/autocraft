package com.adimn.autocraft.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.adimn.autocraft.compat.GearButton;
import com.adimn.autocraft.compat.JeiButtonAdder;
import com.adimn.autocraft.util.Log;

import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipesGui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

/**
 * JEI RecipesGui 不渲染 Screen.renderables，且按钮应位于每张配方右侧按钮列。
 * 这里直接读取 RecipeGuiLayouts 里的每张配方，计算“下一个侧边按钮”的位置，
 * 把齿轮画在书签/转移按钮同一列，并处理点击。
 */
@Mixin(value = RecipesGui.class, remap = false)
public abstract class JeiRecipesGuiMixin {

    @Shadow
    @Final
    private RecipeGuiLayouts layouts;

    private static final int BUTTON_SIZE = 12;

    @Inject(method = {"m_88315_", "render"}, at = @At("TAIL"), remap = false)
    private void autocraft$render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                  CallbackInfo ci) {
        try {
            for (Object[] gear : getRecipeGears()) {
                int x = (Integer) gear[0];
                int y = (Integer) gear[1];
                GearButton button = new GearButton(x, y, b -> {
                });
                button.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        } catch (Throwable t) {
            Log.warn("JEI Mixin 渲染齿轮按钮失败：" + t);
        }
    }

    @Inject(method = {"m_6375_", "mouseClicked"}, at = @At("HEAD"), remap = false, cancellable = true)
    private void autocraft$mouseClicked(double mouseX, double mouseY, int button,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            for (Object[] gear : getRecipeGears()) {
                int x = (Integer) gear[0];
                int y = (Integer) gear[1];
                if (mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= y && mouseY < y + BUTTON_SIZE) {
                    Object recipeLayout = gear[2];
                    JeiButtonAdder.triggerForRecipe(recipeLayout);
                    cir.setReturnValue(true);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.warn("JEI Mixin 点击齿轮按钮失败：" + t);
        }
    }

    /** 返回每张配方右侧“下一个可用侧边按钮”的屏幕坐标和 recipeLayout。 */
    private List<Object[]> getRecipeGears() {
        List<Object[]> result = new ArrayList<>();
        try {
            Field field = RecipeGuiLayouts.class.getDeclaredField("recipeLayoutsWithButtons");
            field.setAccessible(true);
            Object list = field.get(layouts);
            if (!(list instanceof List<?> layoutList)) {
                return result;
            }

            Class<?> drawableClass = Class.forName("mezz.jei.api.gui.IRecipeLayoutDrawable");
            Method getSideButtonArea = drawableClass.getMethod("getSideButtonArea", int.class);

            for (Object entry : layoutList) {
                Method getRecipeLayout = entry.getClass().getMethod("getRecipeLayout");
                Object recipeLayout = getRecipeLayout.invoke(entry);
                if (recipeLayout == null) {
                    continue;
                }

                Method getRect = drawableClass.getMethod("getRect");
                Object rect = getRect.invoke(recipeLayout);
                if (!(rect instanceof Rect2i layoutRect)) {
                    continue;
                }

                int buttonIndex = countSideButtons(entry);
                Object side = getSideButtonArea.invoke(recipeLayout, buttonIndex);
                if (!(side instanceof Rect2i sideRect)) {
                    continue;
                }

                int x = layoutRect.getX() + sideRect.getX();
                int y = layoutRect.getY() + sideRect.getY();
                result.add(new Object[]{x, y, recipeLayout});
            }
        } catch (Throwable t) {
            Log.warn("JEI Mixin 读取配方按钮列失败：" + t);
        }
        return result;
    }

    /** 统计该配方已有几个可见侧边按钮，返回下一个按钮的 index。 */
    private int countSideButtons(Object entry) {
        int count = 0;
        try {
            for (String methodName : new String[]{"transferButton", "bookmarkButton"}) {
                try {
                    Method m = entry.getClass().getMethod(methodName);
                    Object btn = m.invoke(entry);
                    if (btn != null) {
                        Method isVisible = btn.getClass().getMethod("isVisible");
                        if (Boolean.TRUE.equals(isVisible.invoke(btn))) {
                            count++;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // 旧版本没有 record accessor 时按两个按钮都算
                }
            }
            try {
                Method m = entry.getClass().getMethod("extraButtons");
                Object extra = m.invoke(entry);
                if (extra instanceof List<?> extraList) {
                    for (Object btn : extraList) {
                        Method isVisible = btn.getClass().getMethod("isVisible");
                        if (Boolean.TRUE.equals(isVisible.invoke(btn))) {
                            count++;
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // 旧版本无 extraButtons
            }
        } catch (Throwable t) {
            Log.warn("JEI Mixin 统计侧边按钮失败，使用 index=1：" + t);
            return 1;
        }
        return count;
    }
}

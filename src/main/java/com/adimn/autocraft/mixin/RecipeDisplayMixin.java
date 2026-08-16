package com.adimn.autocraft.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.adimn.autocraft.ui.AutoCraftRecipeWidget;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.screen.RecipeDisplay;
import dev.emi.emi.screen.WidgetGroup;

/**
 * 在 EMI RecipeDisplay 的右侧标准按钮列（FILL/TREE/DEFAULT）追加「自动合成」齿轮按钮。
 *
 * 替代旧 RecipeScreenButtonHandler（反射读 private tabs/tab/page/currentPage）：
 *   - 注入点：RecipeDisplay.addButtons 右侧按钮列调用后（xOff == 14）；
 *   - 位置算法与 EMI 原版完全一致：把本按钮当作追加在现有按钮之后的第 N 个；
 *   - 按钮是真正的 EMI Widget（AutoCraftRecipeWidget），随 WidgetGroup 一起渲染/点击/悬停，
 *     且每次 setPage 重建 currentPage 时由 EMI 重新 addButtons，按钮天然跟随当前配方。
 *
 * remap=false：RecipeDisplay 是 EMI 自有类，addButtons 为 EMI 私有方法，名字不随映射变化。
 * 仅注入一次（右列 xOff==14），左列（截图按钮，xOff==-17）跳过。
 */
@Mixin(value = RecipeDisplay.class, remap = false)
public abstract class RecipeDisplayMixin {
    private static final int DISPLAY_PADDING = 8;
    private static final int BUTTON_SPACING = 14;
    private static final int BUTTON_SIZE = 12;

    @Shadow
    @Final
    public EmiRecipe recipe;

    @Shadow
    @Final
    private int height;

    @Inject(method = "addButtons", at = @At("TAIL"), remap = false)
    private void autocraft$addGearButton(WidgetGroup widgets, List<?> types, int x, int xOff,
                                         CallbackInfo ci) {
        if (xOff != BUTTON_SPACING || widgets == null || recipe == null
                || recipe.getOutputs().isEmpty()) {
            return;
        }
        int emiCount = types.size();
        int total = emiCount + 1;
        int rows = Math.max(1, (height + DISPLAY_PADDING + 2) / BUTTON_SPACING);
        int space = Math.min(DISPLAY_PADDING,
                height + DISPLAY_PADDING - (Math.min(rows, total) * BUTTON_SPACING - 2));
        int bottomLocal = height + DISPLAY_PADDING / 2 - BUTTON_SIZE - space / 2;

        // 与 RecipeDisplay.addButtons 相同的列填充顺序：
        // 每次填充 min(rows, size) 个按钮（从列表尾部开始），列满后 x += xOff 换列。
        int size = total;
        int colX = x;
        int ourX = -1;
        int ourY = -1;
        while (size > 0 && ourX < 0) {
            int used = Math.min(rows, size);
            int first = size - used;
            int yOff = 0;
            for (int i = first; i < size; i++) {
                if (i == emiCount) {
                    ourX = colX;
                    ourY = bottomLocal - yOff;
                    break;
                }
                yOff += BUTTON_SPACING;
            }
            size -= used;
            colX += xOff;
        }
        if (ourX >= 0) {
            widgets.add(new AutoCraftRecipeWidget(recipe, ourX, ourY));
        }
    }
}

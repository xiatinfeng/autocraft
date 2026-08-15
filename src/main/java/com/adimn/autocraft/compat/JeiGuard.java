package com.adimn.autocraft.compat;

/**
 * JEI 运行时存在性守卫（同 EmiGuard 模式）。
 * JEI 专属类（JeiButtonAdder / 齿轮按钮）只在 isPresent() 为 true 时才被加载，
 * JEI 未安装时整条 JEI 按钮逻辑安静禁用，绝不 NoClassDefFoundError。
 */
public final class JeiGuard {
    private static final boolean PRESENT = check();

    private JeiGuard() {}

    private static boolean check() {
        try {
            Class.forName("mezz.jei.gui.recipes.RecipesGui");
            Class.forName("mezz.jei.api.constants.VanillaTypes");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPresent() {
        return PRESENT;
    }
}

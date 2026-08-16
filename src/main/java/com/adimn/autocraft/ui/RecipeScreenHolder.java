package com.adimn.autocraft.ui;

import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;

/**
 * 配方屏返回工具：EMI 的 RecipeScreen.old 记录打开它之前的容器界面。
 * （EMI 齿轮按钮 AutoCraftRecipeWidget 点击后返回用。）
 */
public final class RecipeScreenHolder {
    private RecipeScreenHolder() {}

    /** 关闭当前配方屏并回到之前的容器界面；没有旧界面时直接关闭。成功返回 true。 */
    public static boolean back() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RecipeScreen screen) {
            mc.setScreen(screen.old != null ? screen.old : null);
            return true;
        }
        return false;
    }
}
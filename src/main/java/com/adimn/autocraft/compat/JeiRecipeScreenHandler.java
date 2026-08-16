package com.adimn.autocraft.compat;

import com.adimn.autocraft.util.Log;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * JEI 配方屏齿轮按钮注册（ScreenEvent.Init.Post）。
 *
 * 类加载安全设计：本类**不直接 import** 任何 JEI 类。
 * 通过 JeiGuard 短路 + JeiButtonAdder 间接引用——
 * JEI 未安装时 JeiButtonAdder 永不加载，无 NoClassDefFoundError。
 */
public final class JeiRecipeScreenHandler {
    private JeiRecipeScreenHandler() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!JeiGuard.isPresent()) {
            return;
        }
        Screen screen = event.getScreen();
        // 经反射确认类型后再进入 JEI 专属类（避免 JEI 缺失时类加载炸掉）
        if (screen.getClass().getName().equals("mezz.jei.gui.recipes.RecipesGui")) {
            Log.info("检测到 JEI 配方屏，准备添加齿轮按钮：" + screen.getClass().getSimpleName());
            try {
                JeiButtonAdder.addButton(screen, event);
                Log.info("JEI 齿轮按钮添加调用完成");
            } catch (Throwable t) {
                Log.warn("JEI 齿轮按钮添加失败：" + t);
            }
        }
    }
}

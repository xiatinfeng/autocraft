package com.adimn.autocraft.ui;

import com.adimn.autocraft.compat.EmiGuard;
import com.adimn.autocraft.util.Log;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 在 EMI 配方详情屏（RecipeScreen）的"标准按钮列"注册「自动合成」按钮。
 *
 * 按钮列算法（参考 emi-source RecipeDisplay.addButtons）：
 *   bx_local = widgetGroup.width + 5 + 14 * column
 *   by_local = widgetGroup.height + 4 - 12 - space/2 - 14 * rowIndex
 *
 * **关键修正（M6→M7）**：按钮列 y 范围是 **WidgetGroup（配方网格）的 height**，
 * 不是 RecipeScreen.bounds.height（后者包含 title/page 行，会算大）。
 *
 * **M8（实现选择）**：使用 {@link AutoCraftRecipeButton}（Screen 层 addDrawableChild），
 * 而不是直接塞进 widgetGroup.widgets——后者会在 RecipeScreen.setPage 重建 currentPage
 * 时整组清空（RecipeScreen.java:381）。Screen 层按钮不受切页影响，可跨页保持。
 * 渲染用 EmiDrawContext 画 12x12 齿轮，与 EMI 原版按钮同列同风格。
 *
 * 点击后：取当前配方首个非空产出 → 关闭配方屏 → 新管线下单（OrderTrigger.order）。
 */
public final class RecipeScreenButtonHandler {
    private RecipeScreenButtonHandler() {}

    /**
     * 右按钮列里 EMI 默认最多 3 个：FILL / TREE / DEFAULT。
     * 我们的齿轮按钮作为下一个追加位，0-based 索引 = 3。
     * 若 rows 足够，它与原版按钮同列；若高度不够，自动换到第二列（与 RecipeDisplay.addButtons 一致）。
     */
    private static final int BUTTON_INDEX = 3;
    private static final int BUTTON_SPACING = 14;
    private static final int DISPLAY_PADDING = 8;
    private static final int TYPES_DEFAULT_SIZE = 4;
    /** 换列偏移（每多一列 x 右移 14 像素，与 RecipeDisplay.addButtons 一致）。 */
    private static final int COLUMN_XOFF = 14;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!EmiGuard.isPresent()) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen == null || !screen.getClass().getName().equals("dev.emi.emi.screen.RecipeScreen")) {
            return;
        }
        RecipeScreen recipeScreen = (RecipeScreen) screen;

        EmiRecipe recipe = getCurrentRecipe(recipeScreen);
        if (recipe == null) {
            Log.warn("RecipeScreen 初始化但未能读取当前配方，不添加按钮。");
            return;
        }

        // 反射拿 currentPage[0]（WidgetGroup 是 EMI 配方屏每个 tab/page 的容器）
        WidgetGroup widgetGroup = getFirstWidgetGroup(recipeScreen);
        int btnX;
        int btnY;
        if (widgetGroup != null) {
            // 按 EMI RecipeDisplay.addButtons 公式算位置（WidgetGroup 局部坐标）
            int rows = Math.max(1, (widgetGroup.height + DISPLAY_PADDING + 2) / BUTTON_SPACING);
            int space = Math.min(DISPLAY_PADDING,
                    widgetGroup.height + DISPLAY_PADDING - (Math.min(rows, TYPES_DEFAULT_SIZE) * BUTTON_SPACING - 2));
            int bottomLocal = widgetGroup.height + DISPLAY_PADDING / 2 - 12 - space / 2;

            // 第 N 个按钮换列次数 = N / rows，列内位置 = N % rows
            // （EMI 右列默认 FILL/TREE/DEFAULT 3 个，我们的齿轮是第 4 个；
            //   rows 足够时同列，不够时自动换到第二列 x += 14）
            int column = BUTTON_INDEX / rows;
            int rowIndex = BUTTON_INDEX % rows;
            int bxLocal = widgetGroup.width + 5 + COLUMN_XOFF * column;
            int byLocal = bottomLocal - BUTTON_SPACING * rowIndex;

            // 转 RecipeScreen 绝对坐标（widgetGroup.x/y 已是 RecipeScreen 局部）
            btnX = widgetGroup.x + bxLocal;
            btnY = widgetGroup.y + byLocal;
            Log.debug("按钮位置: widgetGroup=" + widgetGroup.x + "," + widgetGroup.y
                    + " " + widgetGroup.width + "x" + widgetGroup.height
                    + " rows=" + rows + " space=" + space
                    + " col=" + column + " rowIdx=" + rowIndex
                    + " → absolute=[" + btnX + "," + btnY + "]");
        } else {
            // 兜底：反射读不到 currentPage 时放到配方屏右上角，避免按钮完全丢失。
            dev.emi.emi.api.widget.Bounds bounds = recipeScreen.getBounds();
            btnX = bounds.right() - AutoCraftRecipeButton.RENDER_SIZE - 2;
            btnY = bounds.top() + 4;
            Log.warn("RecipeScreen.currentPage 为空，使用兜底右上角位置 [" + btnX + "," + btnY + "]");
        }

        // **关键**：用 event.addListener（→ addDrawableChild）注册到 RecipeScreen.renderables。
        // 不用 widgetGroup.widgets.add()——后者会被 RecipeScreen.setPage 重建 currentPage 时
        // 整组清空（RecipeScreen.java:381 `currentPage = Lists.newArrayList()`）。
        // Screen 层 addDrawableChild 不受 setPage 影响，按钮不会被切页清掉。
        AutoCraftRecipeButton button = new AutoCraftRecipeButton(btnX, btnY, recipe);
        event.addListener(button);
        Log.debug("已向 RecipeScreen 添加自动合成齿轮按钮，目标=" + recipe);
    }

    /** 反射读取 RecipeScreen.currentPage 的第一个 WidgetGroup（EMI 配方网格容器）。 */
    private static WidgetGroup getFirstWidgetGroup(RecipeScreen screen) {
        try {
            java.lang.reflect.Field field = RecipeScreen.class.getDeclaredField("currentPage");
            field.setAccessible(true);
            java.util.List<?> page = (java.util.List<?>) field.get(screen);
            if (page == null || page.isEmpty()) {
                return null;
            }
            return (WidgetGroup) page.get(0);
        } catch (Exception e) {
            Log.warn("反射读取 RecipeScreen.currentPage 失败：" + e);
            return null;
        }
    }

    /** 反射读取 RecipeScreen 当前显示的配方（tabs/tab/page → RecipeDisplay.recipe）。 */
    private static EmiRecipe getCurrentRecipe(RecipeScreen screen) {
        try {
            java.lang.reflect.Field tabsField = RecipeScreen.class.getDeclaredField("tabs");
            java.lang.reflect.Field tabField = RecipeScreen.class.getDeclaredField("tab");
            java.lang.reflect.Field pageField = RecipeScreen.class.getDeclaredField("page");
            tabsField.setAccessible(true);
            tabField.setAccessible(true);
            pageField.setAccessible(true);

            java.util.List<?> tabs = (java.util.List<?>) tabsField.get(screen);
            int tab = tabField.getInt(screen);
            int page = pageField.getInt(screen);

            if (tabs == null || tabs.isEmpty() || tab < 0 || tab >= tabs.size()) {
                return null;
            }
            Object currentTab = tabs.get(tab);
            java.util.List<?> displays = (java.util.List<?>) currentTab.getClass()
                    .getMethod("getPage", int.class).invoke(currentTab, page);
            if (displays == null || displays.isEmpty()) {
                return null;
            }
            return (EmiRecipe) displays.get(0).getClass().getField("recipe").get(displays.get(0));
        } catch (Exception e) {
            Log.warn("反射读取 RecipeScreen 当前配方失败：" + e);
            return null;
        }
    }
}
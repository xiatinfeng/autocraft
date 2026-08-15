package com.adimn.autocraft.craft;

import java.util.List;

import com.adimn.autocraft.compat.EmiGuard;
import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.util.Log;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * 核心驱动：复用 EMI 的 BoM 树 + syntheticFavorites + performFill，本类只是"自动点那个 +"。
 *
 * 驱动链（全部为 EMI 1.20.1 真实内部 API，javap 核实）：
 *   BoM.setGoal(recipe)          -> 建 MaterialTree
 *   BoM.craftingMode = true      -> 开启树合成模式
 *   EmiFavorites.updateSynthetic(inv) -> 按当前背包重算 syntheticFavorites
 *   for e in syntheticFavorites: EmiRecipeFiller.performFill(e.recipe, screen, CRAFTABLE, INVENTORY, e.batches)
 *
 * 反作弊：每客户端 tick 最多执行一步合成，步间 cooldown = Config.delayTicks（默认 20≈1s）。
 * 绝不 Thread.sleep（会冻结主线程）。
 */
public final class TreeDriver {
    private static CraftState state = CraftState.IDLE;
    private static int cooldown = 0;
    private static int noProgressTicks = 0;
    private static AbstractContainerScreen<?> screen;

    private TreeDriver() {}

    public static CraftState getState() {
        return state;
    }

    public static boolean isDriving() {
        return state == CraftState.DRIVING;
    }

    /** 启动一棵树的驱动。优先复用 EMI 已打开的树，否则从悬停物或指定配方建树。 */
    public static void start() {
        start(null);
    }

    /** 用指定配方启动驱动（配方详情屏按钮入口）。 */
    public static void start(EmiRecipe recipe) {
        if (!EmiGuard.isPresent()) {
            chat("未检测到 EMI，本模组已禁用。");
            return;
        }
        if (state == CraftState.DRIVING) {
            return;
        }
        if (BoM.tree == null) {
            EmiRecipe goal = recipe != null ? recipe : hoveredGoal();
            if (goal == null) {
                chat("请先在 EMI 中打开目标物品的配方树，或悬停该物品再按驱动键。");
                Log.warn("驱动启动失败：未打开/悬停目标配方树。");
                return;
            }
            BoM.setGoal(goal);
        }
        BoM.craftingMode = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // 必须显式调一次，否则 syntheticFavorites 为空，驱动会立即结束。
            // BoM.setGoal() 内部会把 craftingMode 置 false，所以这里先 true 再 update。
            EmiFavorites.updateSynthetic(EmiPlayerInventory.of(mc.player));
        }

        AbstractContainerScreen<?> cur = currentScreen();
        if (cur == null) {
            chat("请打开合成台或背包界面后再驱动。");
            BoM.craftingMode = false;
            Log.warn("驱动启动失败：无有效合成台/背包界面。");
            return;
        }
        screen = cur;
        state = CraftState.DRIVING;
        cooldown = 0;
        noProgressTicks = 0;
        chat("开始自动合成（人速）。松开驱动键停止。");
        Log.info("驱动启动（人速）；界面=" + cur.getClass().getSimpleName());
    }

    public static void stop() {
        if (state == CraftState.DRIVING) {
            BoM.craftingMode = false;
            chat("已停止自动合成。");
            Log.info("驱动停止。");
        }
        state = CraftState.IDLE;
    }

    public static void onClientTick() {
        if (state != CraftState.DRIVING) {
            return;
        }
        AbstractContainerScreen<?> cur = currentScreen();
        if (cur == null) {
            stop();
            return;
        }
        screen = cur;
        if (--cooldown > 0) {
            return;
        }
        cooldown = Math.max(1, Config.delayTicks());

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 背包变了 -> 重算 -> 解锁更深层
        EmiPlayerInventory inv = EmiPlayerInventory.of(mc.player);
        EmiFavorites.updateSynthetic(inv);

        List<EmiFavorite.Synthetic> list = EmiFavorites.syntheticFavorites;
        boolean anyCraftable = false;
        boolean progressed = false;
        for (EmiFavorite.Synthetic e : list) {
            EmiRecipe r = e.getRecipe();
            if (r == null) {
                continue; // 原材料展示行，跳过
            }
            if (e.state < 1) {
                continue; // 0=不可合成；-1=原材料（已 skip）
            }
            anyCraftable = true;
            int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, e.batches));
            if (fill(r, screen, amount)) {
                progressed = true;
                break; // 本 tick 只做一步
            }
        }

        Log.debug("tick: anyCraftable=" + anyCraftable + ", progressed=" + progressed
                + ", noProgressTicks=" + noProgressTicks);

        if (progressed) {
            noProgressTicks = 0;
            return;
        }

        // 本 tick 没合成任何东西
        noProgressTicks++;
        if (!anyCraftable) {
            BoM.craftingMode = false;
            state = CraftState.DONE;
            chat("配方树已完成（或已无更多可合成步骤）。");
            Log.info("驱动结束：配方树已完成（或已无更多可合成步骤）。");
            return;
        }
        if (noProgressTicks >= 3) {
            BoM.craftingMode = false;
            state = CraftState.NEED_MATERIALS;
            chat("无法在当前界面继续合成：请打开正确的合成台/背包界面，或检查材料是否充足。");
            Log.warn("驱动结束：连续无进展，疑似材料不足或界面不匹配（NEED_MATERIALS）。");
        }
    }

    private static boolean fill(EmiRecipe recipe, AbstractContainerScreen<?> s, int amount) {
        Log.debug("performFill: " + recipe + " x" + amount);
        return EmiRecipeFiller.performFill(recipe, s,
                EmiCraftContext.Type.CRAFTABLE, EmiCraftContext.Destination.INVENTORY, amount);
    }

    private static AbstractContainerScreen<?> currentScreen() {
        return EmiApi.getHandledScreen();
    }

    private static EmiRecipe hoveredGoal() {
        EmiStackInteraction hover = EmiApi.getHoveredStack(true);
        if (hover == null) {
            return null;
        }
        return hover.getRecipeContext();
    }

    public static void chat(String msg) {
        if (!Config.chatFeedback()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[EMI-AutoCraft] " + msg));
        }
    }
}

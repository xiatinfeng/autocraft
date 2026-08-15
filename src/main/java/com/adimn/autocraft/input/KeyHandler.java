package com.adimn.autocraft.input;

import com.adimn.autocraft.compat.EmiGuard;
import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.craft.TreeDriver;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.Log;

import com.mojang.blaze3d.platform.InputConstants;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.bom.BoM;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 三分支触发（用户 2026-07-17 拍板）：
 *  ① 全局快捷键 DRIVE（默认 B）：按下启动 / 松开停止。
 *  ② 钉键 PIN（默认 N）：悬停某原料配方时按 -> BoM.addResolution 钉为默认（v1.0 即做）。
 *  ③ 合成台界面 + 背包界面都触发（TreeDriver 通过 EmiApi.getHandledScreen 自动识别有效界面）。
 */
public final class KeyHandler {
    /** 提升为 public static，供 KeyMappings（注册到 Mod 总线）引用注册。 */
    public static final KeyMapping DRIVE = new KeyMapping(
            "key.autocraft.drive", InputConstants.Type.KEYSYM, InputConstants.KEY_B,
            "key.autocraft.category");
    public static final KeyMapping PIN = new KeyMapping(
            "key.autocraft.pin", InputConstants.Type.KEYSYM, InputConstants.KEY_N,
            "key.autocraft.category");
    private static boolean driveWasDown = false;

    private KeyHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!EmiGuard.isPresent()) {
            return;
        }

        boolean down = DRIVE.isDown();
        if (down && !driveWasDown) {
            if (CraftExecutor.isDriving()) {
                CraftExecutor.stop();   // 执行中按驱动键 = 取消
            } else {
                OrderTrigger.orderFromContext();   // M4 新管线：合成桌结果 > EMI 悬停 > 提示
            }
        } else if (!down && driveWasDown) {
            TreeDriver.stop();   // 旧版 EMI BoM 驱动为按住式，松开停止（新管线为一次性触发，无动作）
        }
        driveWasDown = down;

        if (PIN.consumeClick()) {
            pinHovered();
        }

        TreeDriver.onClientTick();
        CraftExecutor.onClientTick();
    }

    private static void pinHovered() {
        if (BoM.tree == null) {
            TreeDriver.chat("钉默认配方需先打开一个 EMI 配方树。");
            Log.warn("钉默认配方失败：尚未打开 EMI 配方树。");
            return;
        }
        EmiStackInteraction hover = EmiApi.getHoveredStack(true);
        if (hover == null) {
            TreeDriver.chat("悬停某个原料配方再按钉键。");
            Log.warn("钉默认配方失败：未悬停任何配方。");
            return;
        }
        EmiRecipe r = hover.getRecipeContext();
        if (r == null) {
            TreeDriver.chat("悬停物没有关联配方，无法钉为默认。");
            Log.warn("钉默认配方失败：悬停物无关联配方。");
            return;
        }
        BoM.addResolution(hover.getStack(), r);
        TreeDriver.chat("已将该原料的默认配方钉为：当前悬停配方。");
        Log.info("已将悬停配方钉为默认：" + r);
    }
}

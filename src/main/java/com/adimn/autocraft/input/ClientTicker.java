package com.adimn.autocraft.input;

import com.adimn.autocraft.craft.CraftExecutor;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端 tick 泵（替代旧 KeyHandler 的执行器驱动职责）。
 *
 * 不依赖 EMI/JEI：无论只装 JEI、只装 EMI 还是两者皆无，
 * {@link CraftExecutor#onClientTick()} 都会在每 tick 被驱动，
 * 命令入口（/autocraft craft）与 JEI/EMI 齿轮按钮因此始终可用。
 */
public final class ClientTicker {
    private ClientTicker() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CraftExecutor.onClientTick();
    }
}

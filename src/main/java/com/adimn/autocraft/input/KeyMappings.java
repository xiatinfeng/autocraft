package com.adimn.autocraft.input;

import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 按键映射注册（Mod 总线事件）。
 *
 * 注意：{@link RegisterKeyMappingsEvent} 是 IModBusEvent，只能注册到 Mod 事件总线；
 * 而 {@link KeyHandler#onClientTick} 监听的 ClientTickEvent 是游戏总线事件。
 * 两者不能放在同一个类里同时注册到两条总线（Forge 扫描 Mod 总线时会因 ClientTickEvent
 * 不是 IModBusEvent 子类而抛 IllegalArgumentException）。故拆分：本类只处理 Mod 总线事件，
 * KeyHandler 只处理游戏总线事件。
 */
public final class KeyMappings {
    private KeyMappings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(KeyHandler.DRIVE);
        event.register(KeyHandler.PIN);
    }
}

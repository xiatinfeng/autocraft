package com.adimn.autocraft.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 可选 mod 反射适配的公共工具：类探测、按类名找槽位、玩家物品栏槽位收集。
 * 所有反射都 try/catch，缺 mod 时安静返回空。
 */
final class CompatReflection {

    private CompatReflection() {
    }

    /** 尝试加载类；缺失返回 null。 */
    static Class<?> clazz(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前菜单中所有属于玩家物品栏的槽位 id。 */
    static List<Integer> playerInventorySlotIds(AbstractContainerMenu menu) {
        List<Integer> ids = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return ids;
        }
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                ids.add(slot.index);
            }
        }
        return ids;
    }

    /** 按槽位类名收集 slot id。 */
    static List<Integer> slotIdsByClassName(AbstractContainerMenu menu, String className) {
        List<Integer> ids = new ArrayList<>();
        Class<?> slotClass = clazz(className);
        if (slotClass == null) {
            return ids;
        }
        for (Slot slot : menu.slots) {
            if (slotClass.isInstance(slot)) {
                ids.add(slot.index);
            }
        }
        return ids;
    }

    /** 按槽位类名找第一个 slot id；找不到返回 -1。 */
    static int firstSlotIdByClassName(AbstractContainerMenu menu, String className) {
        Class<?> slotClass = clazz(className);
        if (slotClass == null) {
            return -1;
        }
        for (Slot slot : menu.slots) {
            if (slotClass.isInstance(slot)) {
                return slot.index;
            }
        }
        return -1;
    }

    /** 安全的 Optional 反射调用：返回 Optional.empty() 而不是抛异常。 */
    static Optional<Object> callOptional(Object target, String methodName, Class<?> paramType, Object arg) {
        try {
            var method = target.getClass().getMethod(methodName, paramType);
            Object result = method.invoke(target, arg);
            if (result instanceof Optional<?> opt) {
                @SuppressWarnings("unchecked")
                Optional<Object> cast = (Optional<Object>) (Optional<?>) opt;
                return cast;
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }
}

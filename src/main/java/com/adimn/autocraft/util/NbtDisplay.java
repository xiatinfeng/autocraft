package com.adimn.autocraft.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * NBT 需求展示的本地化层。
 *
 * 配方图/规划器里存的是规范化指纹（如 "ench=minecraft:protection:4"），
 * 这里在客户端显示时转换成当前语言的名称（如 "保护 IV"）。
 * 非附魔书的自定义 NBT 需求（如拔刀剑的 killCount=500）保持原样，不做猜测翻译。
 */
public final class NbtDisplay {
    private NbtDisplay() {}

    /** 把 NBT 需求文本本地化；空串原样返回。 */
    public static String localize(String requirementText) {
        if (requirementText == null || requirementText.isEmpty()) {
            return "";
        }
        if (requirementText.startsWith("ench=")) {
            return localizeEnchantments(requirementText.substring("ench=".length()));
        }
        return requirementText;
    }

    /** 解析 "id:lvl,id2:lvl2" 并转为本地化附魔名。 */
    private static String localizeEnchantments(String data) {
        List<String> names = new ArrayList<>();
        for (String entry : data.split(",")) {
            if (entry.isEmpty()) {
                continue;
            }
            int lastColon = entry.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == entry.length() - 1) {
                names.add(entry);
                continue;
            }
            String id = entry.substring(0, lastColon);
            int level;
            try {
                level = Integer.parseInt(entry.substring(lastColon + 1));
            } catch (NumberFormatException e) {
                names.add(entry);
                continue;
            }
            ResourceLocation loc = ResourceLocation.tryParse(id);
            Enchantment enchantment = loc == null ? null : ForgeRegistries.ENCHANTMENTS.getValue(loc);
            if (enchantment == null) {
                names.add(entry);
            } else {
                names.add(enchantment.getFullname(level).getString());
            }
        }
        return String.join("、", names);
    }
}

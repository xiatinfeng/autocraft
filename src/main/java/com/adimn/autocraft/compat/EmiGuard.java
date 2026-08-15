package com.adimn.autocraft.compat;

/**
 * EMI 运行时存在性守卫。
 * 本模组依赖 EMI 内部包（bom / registry / runtime），EMI 未安装时触碰这些类会抛 NoClassDefFoundError。
 * 启动时用 Class.forName 探测一次，缺失则整条驱动逻辑安静禁用，绝不崩溃。
 */
public final class EmiGuard {
    private static final boolean PRESENT = check();

    private EmiGuard() {}

    private static boolean check() {
        try {
            Class.forName("dev.emi.emi.bom.BoM");
            Class.forName("dev.emi.emi.bom.MaterialTree");
            Class.forName("dev.emi.emi.runtime.EmiFavorites");
            Class.forName("dev.emi.emi.runtime.EmiFavorite");
            Class.forName("dev.emi.emi.registry.EmiRecipeFiller");
            Class.forName("dev.emi.emi.api.EmiApi");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPresent() {
        return PRESENT;
    }
}

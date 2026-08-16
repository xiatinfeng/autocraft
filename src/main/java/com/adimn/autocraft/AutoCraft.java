package com.adimn.autocraft;

import com.adimn.autocraft.command.AutoCraftCommand;
import com.adimn.autocraft.compat.JeiRecipeScreenHandler;
import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.input.ClientTicker;
import com.adimn.autocraft.util.Log;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.DistExecutor;

@Mod(AutoCraft.MODID)
public class AutoCraft {
    public static final String MODID = "autocraft";

    public AutoCraft() {
        // 仅客户端注册：客户端配置 + 客户端 tick 泵 + 配方屏按钮 + 命令。
        // 用 DistExecutor 包裹，避免专用服务端加载客户端类（ClientTickEvent 等）而崩溃。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            Config.register();
            // 游戏总线：客户端 tick 泵（执行器驱动）。不依赖 EMI/JEI，只装 JEI 也能正常执行。
            MinecraftForge.EVENT_BUS.register(ClientTicker.class);
            // EMI 配方屏齿轮按钮：由 autocraft.mixins.json 的 RecipeDisplayMixin 注入到
            // EMI RecipeScreen 标准按钮列（EMI 缺失时 Mixin 自动跳过）。
            // JEI 配方屏齿轮按钮（JEI 缺失时静默跳过）。
            MinecraftForge.EVENT_BUS.register(JeiRecipeScreenHandler.class);
            MinecraftForge.EVENT_BUS.register(AutoCraftCommand.class);  // 游戏总线：/autocraft plan|craft|stop
            Log.info("AutoCraft 客户端初始化完成（执行器 tick 泵已启用，EMI/JEI 齿轮按钮按需生效）");
        });
    }
}

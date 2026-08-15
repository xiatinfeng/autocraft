package com.adimn.autocraft;

import com.adimn.autocraft.command.AutoCraftCommand;
import com.adimn.autocraft.compat.EmiGuard;
import com.adimn.autocraft.compat.JeiRecipeScreenHandler;
import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.input.KeyHandler;
import com.adimn.autocraft.input.KeyMappings;
import com.adimn.autocraft.ui.RecipeScreenButtonHandler;
import com.adimn.autocraft.util.Log;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;

@Mod(AutoCraft.MODID)
public class AutoCraft {
    public static final String MODID = "autocraft";

    public AutoCraft() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 仅客户端注册：按键映射 + 客户端 tick 监听 + 客户端配置。
        // 用 DistExecutor 包裹，避免专用服务端加载客户端类（KeyMapping / ClientTickEvent）而崩溃。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            Config.register();
            modBus.register(KeyMappings.class);   // Mod 总线：注册按键映射
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(KeyHandler.class);  // 游戏总线：客户端 tick 监听
            // EMI 配方屏齿轮按钮（ScreenEvent + GearButton，12x12 齿轮图标）。
            // 注：EMI 官方 addRecipeDecorator 被 showRecipeDecorators 门控（生产默认关），故与 JEI 侧统一走 ScreenEvent。
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(RecipeScreenButtonHandler.class);  // 游戏总线：EMI 配方屏齿轮按钮
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(JeiRecipeScreenHandler.class);  // 游戏总线：JEI 配方屏齿轮按钮（JEI 缺失时静默跳过）
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(AutoCraftCommand.class);  // 游戏总线：/autocraft plan|craft|stop（M2/M3）
            Log.info("AutoCraft 客户端初始化完成；EMI "
                    + (EmiGuard.isPresent() ? "已安装，驱动可用" : "未安装，驱动已禁用"));
        });
    }
}

package com.adimn.autocraft.craft;

public enum CraftState {
    IDLE,            // 空闲
    DRIVING,         // 驱动中
    DONE,            // 配方树已完成（或已无更多可合成步骤）
    NEED_MATERIALS   // 卡住：缺少材料 / 当前界面不支持 / 背包满
}

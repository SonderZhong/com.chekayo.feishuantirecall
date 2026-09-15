package com.chekayo.feishuantirecall;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Xposed 入口包装类，与桌面配置页面分离，避免普通进程加载 Xposed 依赖。 */
public final class FuckLarkSettingsHook implements IXposedHookLoadPackage {
    /** 将目标应用的加载回调转交给飞书设置注入实现。 */
    @Override public void handleLoadPackage(LoadPackageParam param) {
        new FuckLarkSettings().handleLoadPackage(param);
    }
}

package com.chekayo.feishuantirecall;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * FeishuKit Xposed 入口：hook 飞书设置页注入入口行，点击弹出 {@link SettingsPanel}。
 * 数据查看器在 {@link DataViews}（不依赖 Xposed，桌面入口可直接用）。
 *
 * 入口有两处：
 * 1) 飞书「我 → 设置」页顶部注入「FeishuKit 设置」行；
 * 2) 模块桌面图标 / LSPosed 模块详情「启动」→ LauncherActivity。
 */
public class FuckLarkSettings implements IXposedHookLoadPackage {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";
    static volatile String PKG = PKG_FEISHU;
    static final String ROW_TAG = "fucklark_row";

    /** 飞书设置页候选类（版本差异时逐个探测）。 */
    static final String[] SETTING_HOOK_TARGETS = {
            "com.ss.android.lark.setting.page.function.SettingPageFragment",
            "com.ss.android.lark.setting.page.SettingPageFragment",
            "com.ss.android.lark.setting.SettingFragment",
    };

    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !AntiRecall.isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;
        DataViews.PKG = PKG;
        DataMigration.install();
        hookSettingPages(lpparam.classLoader);
    }

    /** 多目标 hook：任一设置页类存在即挂 onResume，保证有「FeishuKit 设置」入口。 */
    static void hookSettingPages(ClassLoader cl) {
        boolean any = false;
        for (String name : SETTING_HOOK_TARGETS) {
            try {
                XposedHelpers.findAndHookMethod(name, cl, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { inject(param.thisObject); }
                            catch (Throwable t) { XposedBridge.log("[fucklark] inject err " + t); }
                        }
                    });
                XposedBridge.log("[fucklark] 已 hook 设置页 " + name);
                any = true;
            } catch (Throwable ignored) { /* 该版本类名不存在，试下一个 */ }
        }
        // 兜底：hook 宿主 Activity 的 onResume，在内容里找可插入点（覆盖设置页变体）
        if (!any) {
            try {
                XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.app.Activity a = (android.app.Activity) param.thisObject;
                            String n = a.getClass().getName();
                            if (n.contains("setting") || n.contains("Setting")) injectActivity(a);
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[fucklark] 设置页类未命中，已 hook Activity.onResume 兜底");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] hook 设置入口失败 " + t);
            }
        }
    }

    static void inject(Object fragment) {
        Object v = XposedHelpers.callMethod(fragment, "getView");
        if (!(v instanceof View)) return;
        injectIntoRoot((View) v);
    }

    static void injectActivity(android.app.Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content != null) injectIntoRoot(content);
    }

    static void injectIntoRoot(View root) {
        if (!(root instanceof ViewGroup)) return;
        if (root.findViewWithTag(ROW_TAG) != null) return;
        final Context ctx = root.getContext();

        View list = findScrollable((ViewGroup) root);
        ViewGroup parent; int idx;
        if (list != null && (list.getParent() instanceof ViewGroup)) {
            parent = (ViewGroup) list.getParent();
            idx = parent.indexOfChild(list);
        } else {
            parent = (ViewGroup) root; idx = 0;
        }

        TextView row = new TextView(ctx);
        row.setTag(ROW_TAG);
        row.setText("FeishuKit 设置");
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER);
        row.setTextColor(0xFF3B9EFF);
        int px = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        int py = (int) (14 * ctx.getResources().getDisplayMetrics().density);
        row.setPadding(px, py, px, py);
        row.setBackgroundColor(0xF21C1C1E);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPanel(ctx); }
        });
        String pcn = parent.getClass().getName();
        try {
            if (pcn.contains("ConstraintLayout")) {
                Class<?> lpc = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams",
                        true, parent.getClass().getClassLoader());
                Object clp = lpc.getConstructor(int.class, int.class).newInstance(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpc.getField("bottomToBottom").setInt(clp, 0);
                lpc.getField("leftToLeft").setInt(clp, 0);
                lpc.getField("rightToRight").setInt(clp, 0);
                row.setLayoutParams((ViewGroup.LayoutParams) clp);
                parent.addView(row);
            } else {
                row.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                parent.addView(row, Math.min(idx, parent.getChildCount()));
            }
        } catch (Throwable t) {
            try {
                if (parent.getChildCount() == 0) parent.addView(row);
                else parent.addView(row, 0);
            } catch (Throwable ignored) {}
        }
        XposedBridge.log("[fucklark] 设置入口已注入 parent=" + pcn);
    }

    static View findScrollable(ViewGroup vg) {
        View sv = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("RecyclerView")) return c;
            if (sv == null && cn.contains("ScrollView")) sv = c;
            if (c instanceof ViewGroup) {
                View r = findScrollable((ViewGroup) c);
                if (r != null && r.getClass().getName().contains("RecyclerView")) return r;
                if (sv == null && r != null) sv = r;
            }
        }
        return sv;
    }

    static void showPanel(final Context ctx) {
        SettingsPanel.show(ctx);
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}

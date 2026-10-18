package com.chekayo.feishuantirecall;

import java.lang.reflect.Member;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 动态菜单首次出现时安装的 legacy hook 登记表；逻辑 ID 重复安装只执行一次。 */
final class RecallHooks {
    private static final ConcurrentHashMap<String, Member> INSTALLED = new ConcurrentHashMap<String, Member>();

    static synchronized boolean hook(Member member, String id, XC_MethodHook callback) {
        // 同一 ID 若指向另一个宿主方法必须报错，否则会悄悄跳过新版混淆后的入口。
        Member existing = INSTALLED.get(id);
        if (existing != null) {
            if (!existing.equals(member)) throw new IllegalStateException("recall hook ID conflict: " + id);
            return false;
        }
        XposedBridge.hookMethod(member, callback);
        INSTALLED.put(id, member);
        return true;
    }

    static boolean installed(String id) { return INSTALLED.containsKey(id); }

    private RecallHooks() {}
}

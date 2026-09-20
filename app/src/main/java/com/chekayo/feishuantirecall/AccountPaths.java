package com.chekayo.feishuantirecall;

import android.content.Context;

import java.io.File;

/**
 * 飞书账号隔离路径：
 * 全员档案 / 离职名单 / 通知存档 / 退群日志 等按登录账号分子目录，避免多账号混在一起。
 *
 * 目录约定（飞书私有目录下）：
 *   files/accounts/&lt;uid&gt;/resign_tracker/profiles.json
 *   files/accounts/&lt;uid&gt;/resign_tracker/resigned_all.json
 *   files/accounts/&lt;uid&gt;/notif_archive.txt
 *   files/accounts/&lt;uid&gt;/leave_log.txt
 *   files/accounts/&lt;uid&gt;/kicked_*.txt
 *
 * 当前账号：优先用 contact.db 路径里的 sdk_storage/&lt;uid&gt;；再尝试最近修改的 sdk_storage 子目录。
 * 认不出时用 "unknown"，行为退回旧全局路径，避免完全不可用。
 */
public final class AccountPaths {
    public static final String FALLBACK_UID = "unknown";

    /** 进程内当前登录账号 uid；空表示尚未识别。 */
    public static volatile String currentUid = "";
    public static volatile String currentPkg = "com.ss.android.lark";

    private AccountPaths() { }

    /** 在飞书进程里探测当前账号并缓存。 */
    public static synchronized void bind(Context ctx, String pkg) {
        if (pkg != null && !pkg.isEmpty()) currentPkg = pkg;
        currentUid = detectUid(ctx);
        android.util.Log.i("fucklark", "AccountPaths uid=" + currentUid + " pkg=" + currentPkg);
    }

    /** 探测当前飞书账号 uid。 */
    public static String detectUid(Context ctx) {
        // 1) contact.db 路径：databases/sdk_storage/<uid>/contact.db
        try {
            File data = null;
            if (ctx != null) data = ctx.getFilesDir().getParentFile();
            if (data == null) {
                data = new File("/data/data/" + currentPkg);
                if (!data.isDirectory()) data = new File("/data/user/0/" + currentPkg);
            }
            File sdk = new File(data, "databases/sdk_storage");
            File[] subs = sdk.listFiles();
            if (subs != null && subs.length > 0) {
                File best = null;
                long bestT = 0;
                for (File s : subs) {
                    if (!s.isDirectory()) continue;
                    File db = new File(s, "contact.db");
                    long t = db.exists() ? db.lastModified() : s.lastModified();
                    if (t >= bestT) { bestT = t; best = s; }
                }
                if (best != null) {
                    String n = best.getName();
                    if (n.length() > 0 && !".".equals(n) && !"..".equals(n)) return n;
                }
            }
        } catch (Throwable ignored) { }
        // 2) 已缓存
        if (currentUid != null && !currentUid.isEmpty()) return currentUid;
        return FALLBACK_UID;
    }

    /** 非法路径字符清洗。 */
    public static String safeUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) return FALLBACK_UID;
        return uid.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    /** 账号数据根目录：files/accounts/&lt;uid&gt;。 */
    public static File accountRoot(Context ctx, String pkg, String uid) {
        File base = ctx != null ? ctx.getFilesDir() : null;
        if (base == null) {
            String p = (pkg == null || pkg.isEmpty()) ? currentPkg : pkg;
            base = new File("/data/data/" + p + "/files");
            if (!base.isDirectory()) base = new File("/data/user/0/" + p + "/files");
        }
        return new File(base, "accounts/" + safeUid(uid));
    }

    /** 当前账号某文件。 */
    public static File accountFile(Context ctx, String pkg, String uid, String name) {
        return new File(accountRoot(ctx, pkg, uid), name);
    }

    /** 档案目录 resign_tracker（当前账号）。 */
    public static File resignDir(Context ctx, String pkg, String uid) {
        return new File(accountRoot(ctx, pkg, uid), "resign_tracker");
    }

    /**
     * 解析档案文件（读优先级）：
     * 1) 飞书 files/accounts/&lt;uid&gt;/resign_tracker/&lt;name&gt;
     * 2) 模块 files/accounts/&lt;uid&gt;/...
     * 3) 旧全局路径（兼容历史数据）
     */
    public static File resolveArchive(Context ctx, String pkg, String uid, String name) {
        String[] pkgs = { pkg, "com.ss.android.lark", "com.larksuite.suite", "com.chekayo.feishuantirecall" };
        String[] uids = { uid, currentUid, FALLBACK_UID };
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            for (String u : uids) {
                if (u == null || u.isEmpty()) continue;
                File f = accountFile(null, p, u, "resign_tracker/" + name);
                if (f.exists()) return f;
            }
        }
        // 旧全局
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            File f = new File("/data/data/" + p + "/files/resign_tracker/" + name);
            if (f.exists()) return f;
            f = new File("/data/user/0/" + p + "/files/resign_tracker/" + name);
            if (f.exists()) return f;
        }
        try {
            Class<?> c = Class.forName("android.app.ActivityThread");
            Object app = c.getMethod("currentApplication").invoke(null);
            if (app instanceof Context && uid != null) {
                return accountFile((Context) app, pkg, uid, "resign_tracker/" + name);
            }
        } catch (Throwable ignored) { }
        return new File("/data/data/" + (pkg == null ? currentPkg : pkg) + "/files/resign_tracker/" + name);
    }

    /** 消息类文件（通知存档/退群日志等）按账号路径。 */
    public static File resolveMessageFile(Context ctx, String pkg, String uid, String fileName) {
        String[] pkgs = { pkg, "com.ss.android.lark", "com.larksuite.suite", "com.chekayo.feishuantirecall" };
        String[] uids = { uid, currentUid, FALLBACK_UID };
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            for (String u : uids) {
                if (u == null || u.isEmpty()) continue;
                File f = accountFile(null, p, u, fileName);
                if (f.exists()) return f;
            }
        }
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            File f = new File("/data/data/" + p + "/files/" + fileName);
            if (f.exists()) return f;
            f = new File("/data/user/0/" + p + "/files/" + fileName);
            if (f.exists()) return f;
        }
        String p = pkg == null ? currentPkg : pkg;
        String u = uid == null || uid.isEmpty() ? currentUid : uid;
        return accountFile(ctx, p, u, fileName);
    }

    /** 界面用：当前账号短标签。 */
    public static String label(Context ctx) {
        String u = currentUid;
        if (u == null || u.isEmpty()) u = detectUid(ctx);
        if (u == null || u.isEmpty() || FALLBACK_UID.equals(u)) return "未识别账号";
        return "账号 " + (u.length() > 12 ? u.substring(0, 12) + "…" : u);
    }
}

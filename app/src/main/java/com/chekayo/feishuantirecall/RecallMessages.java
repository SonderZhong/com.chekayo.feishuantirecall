package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 防撤回的 Java 层身份索引与原文兜底。
 * 消息的唯一键始终是「账号:会话:消息」；仅他人且身份明确的原文才进入有上限的缓存。
 * native 保存数据库原文和已提交撤回标记，Java 缓存只补救宿主映射器返回的空壳，
 * 不能以通知存档里看似相近的内容代替真实消息。
 */
final class RecallMessages {
    interface AccountReader { String read() throws Throwable; }
    static AccountReader accountReader;
    static volatile boolean applicationReady;
    static RecallMarkStore markerStore;
    private static volatile String hookStatus = "未安装";
    static String status() { return hookStatus; }
    /** 延迟建立标记目录：注入早期尚未确定宿主的 files 路径。 */
    private static synchronized RecallMarkStore markers() {
        if (markerStore == null) markerStore = new RecallMarkStore(new java.io.File(AntiRecall.larkFilesDir(), "recall_marks"));
        return markerStore;
    }
    private static void remember(String uid, String chat, String id) {
        try { markers().add(uid, chat, id); } catch (Throwable ignored) {}
    }
    static void restoreAccounts() {
        // 先在后台加载历史账号索引，再把账号与 SDK 的实际数据库目录绑定给 native。
        // 不能在 UI/宿主启动线程上同步扫描文件。
        try {
            final RecallMarkStore store = markers();
            store.afterLoad(new Runnable() {
                public void run() {
                    for (String uid : store.knownAccounts()) bindStorage(uid);
                }
            });
        } catch (Throwable ignored) {}
    }
    private static final java.util.Set<String> boundAccounts = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> queuedAccounts = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static volatile long lastRefresh;
    private static final int MAX_CONTENTS = 256;
    // accessOrder=true：只保留近期展示过的内容；标记另行持久化，不受缓存淘汰影响。
    private static final Map<String, Entry> contents = new LinkedHashMap<String, Entry>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RecallMessages.Entry> e) { return size() > MAX_CONTENTS; }
    };
    private static final class Entry {
        final Object content;
        boolean recalled;
        Entry(Object content) { this.content = content; }
    }

    /** 宿主 Application 就绪后才查询账号；原文只在可见气泡绑定时采集。 */
    static void install(ClassLoader cl) throws Throwable {
        final Class<?> passport = cl.loadClass("com.ss.android.lark.passport_api.IPassportApi");
        final Method getApi = cl.loadClass("com.ss.android.lark.utils.ApiUtils").getMethod("getApi", Class.class);
        final Method getUid = passport.getMethod("getUserId");
        accountReader = new AccountReader() {
            @Override public String read() throws Throwable {
                // 每次重新获取账号服务；飞书可在不重启进程的情况下切换账号，
                // 长期保存旧服务实例或旧 UID 会使撤回标记串到另一个账号。
                Object api = getApi.invoke(null, passport);
                return api == null ? "" : (String) getUid.invoke(api);
            }
        };
        // 本地标记目录初始化失败不应中断 native 及气泡层的独立防撤回逻辑。
        try { markers(); } catch (Throwable ignored) {}
        hookStatus = "消息身份读取器已安装";
        Diag.once("[fucklark] 防撤回锚点命中: " + hookStatus);
    }

    /** 校验宿主提供的十进制 ID，并拒绝非数字、零和 long 溢出。 */
    static long positive(String s) {
        if (s == null || s.isEmpty()) return 0;
        long n = 0;
        for (int i = 0; i < s.length(); ++i) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9 || n > (Long.MAX_VALUE - d) / 10) return 0;
            n = n * 10 + d;
        }
        return n;
    }
    static String account() {
        // attachBaseContext 时宿主 Application 未初始化完毕，调用 Passport 可能触发
        // 宿主静态管理器崩溃；必须等 callApplicationOnCreate 之后再读取账号。
        if (!applicationReady) return "";
        try {
            String uid = accountReader == null ? "" : accountReader.read();
            if (positive(uid) == 0) return "";
            final String activeUid = uid;
            if (!boundAccounts.contains(uid) && queuedAccounts.add(uid)) {
                // 标记加载与 native 目录绑定交给单线程 worker；queuedAccounts 防止并发重复排队。
                try { markers().afterLoad(new Runnable() {
                    public void run() {
                        try { bindStorage(activeUid); } finally { queuedAccounts.remove(activeUid); }
                    }
                }); } catch (Throwable ignored) { queuedAccounts.remove(uid); }
            }
            return uid;
        } catch (Throwable ignored) { return ""; }
    }
    static void refreshAccount() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 2000) return;
        lastRefresh = now;
        account();
    }
    /** 只向 native 提交实际存在的 SDK 账号目录；未知目录不能猜测归属。 */
    private static void bindStorage(String uid) {
        if (boundAccounts.contains(uid)) return;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(uid.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            java.io.File sdk = new java.io.File(AntiRecall.larkFilesDir().getParentFile(), "databases/sdk_storage");
            String storage = hex.toString();
            if (!new java.io.File(sdk, storage).isDirectory()) {
                storage = uid;
                if (!new java.io.File(sdk, storage).isDirectory()) return;
            }
            AntiRecall.nativeBindRecallAccount(uid, storage);
            boundAccounts.add(uid);
            Diag.once("[fucklark] recall account: verified SDK storage mapping");
        } catch (Throwable ignored) {}
    }
    static String value(Object object, String getter) throws Throwable {
        Object value = XposedHelpers.callMethod(object, getter);
        return value == null ? "" : String.valueOf(value);
    }
    /** 任一字段未知就不生成键；不能用消息 ID 单独识别某个会话。 */
    static String key(String uid, String chat, String id) {
        return positive(uid) > 0 && positive(chat) > 0 && positive(id) > 0 ? uid + ":" + chat + ":" + id : null;
    }
    /** 优先查内存/持久化索引，最后查询 native 已提交快照，不从 UI 线程碰数据库。 */
    static boolean marked(String uid, String chat, String id) {
        String key = key(uid, chat, id);
        if (key == null) return false;
        synchronized (contents) {
            Entry entry = contents.get(key);
            if (entry != null && entry.recalled) return true;
        }
        // 磁盘文件由后台 worker 异步载入；contains 本身只查询内存索引。
        try { if (markers().contains(uid, chat, id)) return true; } catch (Throwable ignored) {}
        try {
            if (!AntiRecall.nativeIsRecallMarked(uid, positive(chat), positive(id))) return false;
            remember(uid, chat, id);
            return true;
        }
        catch (Throwable ignored) { return false; }
    }
    static void clear() { synchronized (contents) { contents.clear(); } }
    /** 部分宿主动作只有消息 ID：只供操作守卫兜底，气泡仍要求完整会话 ID。 */
    static boolean markedMessage(String uid, String id) {
        if (positive(uid) == 0 || positive(id) == 0) return false;
        synchronized (contents) {
            for (Map.Entry<String, Entry> e : contents.entrySet()) {
                if (e.getValue().recalled && e.getKey().startsWith(uid + ":")
                        && e.getKey().endsWith(":" + id)) return true;
            }
        }
        try { return markers().containsMessage(uid, id); }
        catch (Throwable ignored) { return false; }
    }

    /** 只恢复身份明确、非自己的撤回消息；身份不一致时放行宿主原行为。 */
    static void prepare(Object message) {
        prepare(account(), message);
    }

    /** 调用方已取得 UID 时复用它，避免气泡 bind 对同一消息重复查询 Passport。 */
    static void prepare(String uid, Object message) {
        if (message == null || !Config.antirecall
                || AntiRecall.TAMPER == 2) return;
        try {
            String chat = value(message, "getChatId"), id = value(message, "getId");
            String key = key(uid, chat, id);
            if (key == null) return;
            String sender = value(message, "getFromId");
            Object self = XposedHelpers.callMethod(message, "isFromMe");
            // isFromMe=false 和发送者 UID!=当前账号必须同时成立；任一字段不可信，
            // 就不保存/回填原文，让宿主与 native 按原有策略处理。
            if (!Boolean.FALSE.equals(self) || positive(sender) == 0 || uid.equals(sender)) {
                synchronized (contents) { contents.remove(key); }
                return;
            }
            boolean recalled = Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isRecalled"));
            if (!recalled) {
                Object content = XposedHelpers.callMethod(message, "getContent");
                if (content != null) synchronized (contents) {
                    Entry old = contents.get(key);
                    Entry next = new Entry(content);
                    next.recalled = old != null && old.recalled;
                    contents.put(key, next);
                }
                return;
            }
            Entry entry;
            synchronized (contents) { entry = contents.get(key); }
            if (entry == null) return; // 没见过该消息原文时，绝不从别的消息/通知猜测内容。
            Object status = XposedHelpers.callMethod(message, "getStatus");
            if (!(status instanceof Enum)) return;
            Object normal = XposedHelpers.getStaticObjectField(status.getClass(), "NORMAL");
            XposedHelpers.callMethod(message, "setMessageContent", entry.content);
            XposedHelpers.callMethod(message, "setStatus", normal);
            synchronized (contents) { entry.recalled = true; }
            remember(uid, chat, id);
        } catch (Throwable t) {
            Diag.once("[fucklark] 防撤回消息模型锚点失败: " + t.getClass().getSimpleName());
        }
    }
}

package com.chekayo.feishuantirecall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 在原生长按菜单末尾追加单条回执。不调用 triggerReply，不消费任何自动回执缓存。 */
final class ManualReadAction {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.Set<String> IN_FLIGHT = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    static void install(final ClassLoader cl) {
        try {
            final Class<?> actionContext = cl.loadClass("com.ss.android.lark.biz.im.extension.message_action.a");
            final Method messageGetter = RecallActionGuard.findMessageGetter(actionContext,
                    cl.loadClass("com.ss.android.lark.chat.entity.message.Message"));
            Class<?> manager = cl.loadClass("com.ss.android.lark.chat.message.menu.MessageActionManager");
            Method parse = null;
            for (Method m : manager.getDeclaredMethods()) {
                Class<?>[] args = m.getParameterTypes();
                if (args.length != 3 || args[0] != actionContext || !List.class.isAssignableFrom(args[1])) continue;
                Class<?> result = m.getReturnType();
                if (result == void.class || result.isPrimitive()) continue;
                try {
                    if (!List.class.isAssignableFrom(result.getDeclaredField("a").getType())) continue;
                    result.getMethod("getActionList");
                    if (parse != null) throw new IllegalStateException("菜单解析入口不唯一");
                    parse = m;
                } catch (NoSuchFieldException ignored) {} catch (NoSuchMethodException ignored) {}
            }
            if (parse == null) throw new NoSuchMethodException("消息菜单解析入口");
            XposedBridge.hookMethod(parse, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.antiread || AntiRecall.TAMPER == 2 || p.getResult() == null) return;
                        final Object ctx = p.args[0];
                        final Object message = messageGetter.invoke(ctx);
                        final String account = RecallMessages.account();
                        if (!eligible(message, account)) return;
                        final String chat = text(message, "getChatId"), id = text(message, "getId");

                        // 检查消息是否已被标记为已读
                        boolean isMarked = RecallMessages.marked(account, chat, id);

                        Object result = p.getResult();

                        // 调试：探测 result 对象的结构
                        XposedBridge.log("[fucklark-menu-debug] Result 类名: " + result.getClass().getName());
                        XposedBridge.log("[fucklark-menu-debug] Result 所有字段:");
                        for (Field f : result.getClass().getDeclaredFields()) {
                            f.setAccessible(true);
                            try {
                                Object value = f.get(result);
                                String valueStr = (value instanceof List) ? "List[" + ((List<?>)value).size() + "]" : String.valueOf(value);
                                XposedBridge.log("[fucklark-menu-debug]   字段 " + f.getName() + " (" + f.getType().getSimpleName() + "): " + valueStr);
                            } catch (Throwable ignored) {}
                        }
                        XposedBridge.log("[fucklark-menu-debug] Result 所有方法:");
                        for (Method m : result.getClass().getDeclaredMethods()) {
                            if (m.getParameterTypes().length == 0 && m.getName().startsWith("get")) {
                                XposedBridge.log("[fucklark-menu-debug]   方法: " + m.getName() + "() -> " + m.getReturnType().getSimpleName());
                            }
                        }

                        List<?> original = (List<?>) result.getClass().getMethod("getActionList").invoke(result);
                        if (original == null || original.isEmpty()) return; // 不绕过宿主禁止菜单的情形

                        // 动态查找网格项的结束位置
                        int gridEndPos = 0;
                        for (int i = 0; i < original.size(); i++) {
                            try {
                                Object item = original.get(i);
                                Method getItemType = item.getClass().getMethod("c");
                                Object itemType = getItemType.invoke(item);
                                if (itemType instanceof Integer && ((Integer) itemType) == 5) {
                                    // itemType=5 是网格类型
                                    gridEndPos = i + 1;
                                } else {
                                    // 遇到第一个非网格项，停止
                                    break;
                                }
                            } catch (Throwable ignored) {
                                // 如果无法获取类型，假设前4个是网格
                                gridEndPos = Math.min(4, original.size());
                                break;
                            }
                        }

                        Context androidContext = (Context) XposedHelpers.callMethod(ctx, "S");
                        Object item = createItem(cl, androidContext, new Runnable() {
                            @Override public void run() {
                                try { XposedHelpers.callMethod(ctx, "dismiss"); } catch (Throwable ignored) {}
                                mark(message, account, chat, id);
                            }
                        });

                        List<Object> items = new ArrayList<Object>(original);
                        items.add(gridEndPos, item);
                        XposedBridge.log("[fucklark-menu] 插入位置: " + gridEndPos + " (网格项结束位置)");
                        Field list = result.getClass().getDeclaredField("a"); list.setAccessible(true); list.set(result, items);
                    } catch (Throwable t) { Diag.once("[fucklark] 单条已读菜单不可用: " + t); }
                }
            });
            Diag.once("[fucklark] 单条已读菜单已安装");
        } catch (Throwable t) { Diag.once("[fucklark] 单条已读菜单当前版本未适配: " + t); }
    }

    private static boolean eligible(Object message, String account) {
        if (message == null || account.isEmpty() || RecallActionGuard.blockedMessage(message)) return false;
        try {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isFromMe"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isRecalled"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isPreMessage"))) return false;
            // 普通会话消息支持；话题内的回执语义未验证，不能用整段话题位置代替单条。
            Object thread = XposedHelpers.callMethod(message, "getThreadId");
            if (thread != null && !thread.toString().isEmpty() && !"0".equals(thread.toString())) return false;
            return RecallMessages.positive(text(message, "getId")) > 0
                    && RecallMessages.positive(text(message, "getChatId")) > 0;
        } catch (Throwable ignored) { return false; }
    }

    private static Object createItem(ClassLoader cl, Context context, final Runnable click) throws Exception {
        Class<?> callback = cl.loadClass("os3.d");
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] args) {
                if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, args);
                if (m.getName().equals("a") && args != null && args.length == 2 && args[0] instanceof View) click.run();
                return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        });

        // 使用系统默认眼睛图标
        android.graphics.drawable.Drawable icon = context.getDrawable(android.R.drawable.ic_menu_view);

        Class<?> builder = cl.loadClass("os3.b$a");
        Object b = builder.getConstructor(android.graphics.drawable.Drawable.class, String.class)
                .newInstance(icon, "标为已读");
        builder.getMethod("a", callback).invoke(b, proxy);
        builder.getMethod("g", int.class).invoke(b, 5); // itemType=5 (网格类型)

        return builder.getMethod("b").invoke(b);
    }

    private static void mark(final Object message, final String account, final String chat, final String id) {
        if (!Config.antiread || !account.equals(RecallMessages.account()) || !eligible(message, account)) {
            notice("消息状态或账号已变化，请重新长按消息"); return;
        }
        final Method send = AntiRecall.knMethod;
        final AntiRecall.KnState template;
        synchronized (AntiRecall.KN_STATE_LOCK) { template = AntiRecall.KN_STATE.get(chat); }
        if (send == null || template == null) { notice("尚未捕获此会话的已读请求，请重新进入聊天后重试"); return; }
        final String key = account + ":" + chat + ":" + id;
        if (!IN_FLIGHT.add(key)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Boolean previous = AntiRecall.KN_REPLAY.get();
                try {
                    if (!Config.antiread || AntiRecall.TAMPER == 2 || !account.equals(RecallMessages.account())
                            || !eligible(message, account)) throw new IllegalStateException("账号或消息状态已变化");
                    Object channel = XposedHelpers.getObjectField(template.m, "a");
                    if (!chat.equals(AntiRecall.channelId(channel))) throw new IllegalStateException("会话模板不匹配");
                    Object request = ManualReadRequest.create(template.m, id);
                    Class<?> cb = send.getParameterTypes()[1];
                    Object callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                            if (method.getName().equals("onSuccess")) {
                                IN_FLIGHT.remove(key); notice("此条消息已标为已读");
                            } else if (method.getName().equals("onError")) {
                                IN_FLIGHT.remove(key); notice("标为已读失败，请稍后重试");
                            }
                            return null;
                        }
                    });
                    // 仅本线程本次请求直通现有拦截；不设置 READ_WINDOWS，也不重放积压 IDs。
                    AntiRecall.KN_REPLAY.set(Boolean.TRUE);
                    send.invoke(template.impl, request, callback);
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (IN_FLIGHT.remove(key)) notice("未收到已读确认，请检查网络后重试");
                        }
                    }, 15000);
                } catch (Throwable t) {
                    IN_FLIGHT.remove(key); notice("当前版本无法提交单条已读，请稍后重试");
                    Diag.once("[fucklark] 单条已读失败: " + t);
                } finally {
                    if (previous == null) AntiRecall.KN_REPLAY.remove(); else AntiRecall.KN_REPLAY.set(previous);
                }
            }
        }, "feishukit-manual-read").start();
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("equals")) return args != null && proxy == args[0];
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        return "FeishuKitManualRead";
    }
    private static String text(Object message, String getter) { return String.valueOf(XposedHelpers.callMethod(message, getter)); }
    private static void notice(final String text) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                Context c = Config.appContextForSync();
                if (c != null) Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
            }
        });
    }
    private ManualReadAction() {}
}

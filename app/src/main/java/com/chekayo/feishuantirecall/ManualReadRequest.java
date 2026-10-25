package com.chekayo.feishuantirecall;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单条回执的独立载体。不修改浏览缓存、原载体、会话已读位置或回复窗口。 */
final class ManualReadRequest {
    static Object create(Object template, String messageId) throws Exception {
        if (!positiveId(messageId))
            throw new IllegalArgumentException("无效消息 ID");
        Class<?> model = template.getClass();
        // 已核对飞书 8.2.6 ReadChannelMessageParam(m) 的字段与 builder 签名。
        Field channel = field(model, "a");
        Field ids = field(model, "b");
        Field folds = field(model, "c");
        if (!channel.getType().getName().equals("com.ss.android.lark.chat.entity.Channel")
                || !List.class.isAssignableFrom(ids.getType()) || !List.class.isAssignableFrom(folds.getType())
                || field(model, "d").getType() != int.class || field(model, "e").getType() != int.class
                || field(model, "f").getType() != String.class || field(model, "g").getType() != boolean.class)
            throw new NoSuchFieldException("单条已读载体签名未适配");
        Class<?> builder = model.getClassLoader().loadClass(model.getName() + "$a");
        Object value = builder.getDeclaredConstructor().newInstance();
        field(builder, "a").set(value, channel.get(template));
        field(builder, "b").set(value, new ArrayList<String>(Collections.singletonList(messageId)));
        field(builder, "c").set(value, new ArrayList<Long>());
        field(builder, "d").setInt(value, -1); // 不推进 max_position
        field(builder, "e").setInt(value, 0);  // 不清理整个会话未读角标
        field(builder, "f").set(value, "");   // 话题消息由菜单层排除，不能推进话题位置
        field(builder, "g").setBoolean(value, false); // 原生超级群分支会清空 ids，单条回执保持 ids
        Method build = null;
        for (Method m : builder.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == model) {
                if (build != null) throw new NoSuchMethodException("单条已读 builder 不唯一");
                build = m;
            }
        }
        if (build == null) throw new NoSuchMethodException("单条已读 builder");
        build.setAccessible(true);
        return build.invoke(value);
    }
    private static boolean positiveId(String id) {
        if (id == null || id.isEmpty() || id.charAt(0) == '0') return false;
        long value = 0;
        for (int i = 0; i < id.length(); i++) {
            int digit = id.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) return false;
            value = value * 10 + digit;
        }
        return value > 0;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private ManualReadRequest() {}
}

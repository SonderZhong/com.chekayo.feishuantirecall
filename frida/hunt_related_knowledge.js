/**
 * 核对「相关知识」独立推荐条的真实 View 层级（含自绘 View）。
 * 用法: frida -U -n com.ss.android.lark -l frida/hunt_related_knowledge.js
 * 真机资源名: reply_layout → ai_smart_reply_stub → ai_smart_reply_tip。
 */
Java.perform(function () {
    const View = Java.use('android.view.View');
    const ViewGroup = Java.use('android.view.ViewGroup');
    const TextView = Java.use('android.widget.TextView');
    let lastDump = 0;

    function resourceName(view) {
        try { return view.getResources().getResourceEntryName(view.getId()).toString(); }
        catch (_) { return ''; }
    }
    function isTarget(view) {
        const name = resourceName(view);
        return name === 'ai_smart_reply_stub' || name === 'ai_smart_reply_tip';
    }
    function dump(view, depth, budget) {
        if (depth > 4 || budget.remaining-- <= 0) return;
        const lp = view.getLayoutParams();
        let text = '';
        if (TextView.class.isInstance(view)) {
            const value = Java.cast(view, TextView).getText();
            if (value != null) text = value.toString().substring(0, 80);
        }
        console.log('  '.repeat(depth) + view.getClass().getName()
            + ' id=' + resourceName(view)
            + ' lp=' + (lp == null ? 'unattached' : lp.width.value + 'x' + lp.height.value)
            + ' visible=' + view.getVisibility() + ' text=' + JSON.stringify(text));
        if (ViewGroup.class.isInstance(view)) {
            const group = Java.cast(view, ViewGroup);
            for (let i = 0; i < group.getChildCount() && budget.remaining > 0; i++)
                dump(group.getChildAt(i), depth + 1, budget);
        }
    }
    function report(view) {
        if (Date.now() - lastDump < 1000) return;
        lastDump = Date.now();
        const retained = Java.retain(view);
        Java.scheduleOnMainThread(function () {
            try {
                let cur = retained;
                for (let i = 0; i < 3 && cur != null; i++) {
                    console.log('[related-knowledge] ancestor #' + i);
                    dump(cur, 0, { remaining: 32 });
                    const parent = cur.getParent();
                    cur = parent != null && View.class.isInstance(parent) ? Java.cast(parent, View) : null;
                }
            } finally { retained.$dispose(); }
        });
    }
    const setId = View.setId.overload('int');
    setId.implementation = function (id) {
        const result = setId.call(this, id);
        if (isTarget(this)) report(this);
        return result;
    };
    const addView = ViewGroup.addView.overload('android.view.View', 'int', 'android.view.ViewGroup$LayoutParams');
    addView.implementation = function (child, index, params) {
        const result = addView.call(this, child, index, params);
        if (isTarget(child)) report(child);
        return result;
    };
    // 遍历现有 Activity 的树，避免 Java.choose 在某些 ART 环境中的兼容问题。
    Java.scheduleOnMainThread(function () {
        function scan(view, budget) {
            if (budget.remaining-- <= 0) return;
            if (isTarget(view)) report(view);
            if (ViewGroup.class.isInstance(view)) {
                const group = Java.cast(view, ViewGroup);
                for (let i = 0; i < group.getChildCount() && budget.remaining > 0; i++)
                    scan(group.getChildAt(i), budget);
            }
        }
        try {
            const thread = Java.use('android.app.ActivityThread').currentActivityThread();
            const records = thread.mActivities.value.values().toArray();
            const Record = Java.use('android.app.ActivityThread$ActivityClientRecord');
            for (let i = 0; i < records.length; i++) {
                const activity = Java.cast(records[i], Record).activity.value;
                if (activity != null) scan(activity.getWindow().getDecorView(), { remaining: 2000 });
            }
        } catch (e) { console.log('[related-knowledge] snapshot: ' + e); }
    });
    console.log('[related-knowledge] 资源探针已就绪。');
});

package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数据查看器与工具方法：不依赖 Xposed / 飞书类，桌面入口与飞书进程共用。
 * 文件路径按模块进程/飞书进程各自的 PKG 解析；读不到时返回空而不是崩溃。
 */
final class DataViews {
    private DataViews() { }

    /** 目标飞书包名（桌面进程固定国内版路径做只读展示；飞书进程由入口覆盖）。 */
    static volatile String PKG = "com.ss.android.lark";

    /**
     * 解析档案文件：飞书进程读 /data/data/PKG/files/resign_tracker/；
     * 模块桌面进程读不到飞书沙箱，回落模块自己的 resign_tracker 副本（ArchiveSync 推送）。
     */
    static File archiveFile(String name) {
        File f = new File("/data/data/" + PKG + "/files/resign_tracker/" + name);
        if (f.exists()) return f;
        f = new File("/data/user/0/" + PKG + "/files/resign_tracker/" + name);
        if (f.exists()) return f;
        try {
            Class<?> c = Class.forName("android.app.ActivityThread");
            Object app = c.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                File m = new File(((Context) app).getFilesDir(), "resign_tracker/" + name);
                if (m.exists()) return m;
            }
        } catch (Throwable ignored) {}
        return new File("/data/data/" + PKG + "/files/resign_tracker/" + name);
    }


    static volatile boolean updateCheckedThisSession = false;
    static volatile boolean donateShownThisSession = false;

    static final String[] UPDATE_MIRRORS = {
        "https://ghproxy.net/https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://gh-proxy.com/raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://cdn.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://fastly.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json"
    };


static void checkUpdate(final Context ctx, final boolean silent) {
        if (!silent) android.widget.Toast.makeText(ctx, "检查更新中…", android.widget.Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String json = null;
                for (String u : UPDATE_MIRRORS) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        c.setConnectTimeout(5000); c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", "fucklark");
                        if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                        java.io.InputStream is = c.getInputStream();
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                        is.close(); c.disconnect();
                        json = new String(bo.toByteArray(), "UTF-8");
                        break;
                    } catch (Throwable ignore) { /* 换下一个镜像 */ }
                }
                final String result = json;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { showUpdateResult(ctx, result, silent); }
                });
            }
        }, "fucklark-update");
        t.setDaemon(true); t.start();
    }

static void showUpdateResult(final Context ctx, String json, boolean silent) {
        try {
            if (json == null) { if (!silent) android.widget.Toast.makeText(ctx, "检查更新失败（网络不可达）", android.widget.Toast.LENGTH_LONG).show(); return; }
            org.json.JSONObject o = new org.json.JSONObject(json);
            int vc = o.optInt("versionCode", 0);
            String vn = o.optString("versionName", "");
            String notice = o.optString("notice", "").trim();
            String changelog = o.optString("changelog", "").trim();
            String channel = o.optString("channel", "").trim();
            String dl = "";
            org.json.JSONArray da = o.optJSONArray("downloads");
            if (da != null && da.length() > 0) dl = da.optString(0, "");
            if (dl.isEmpty()) dl = o.optString("download", "");

            boolean newer = vc > moduleVersionCode();
            boolean hasNotice = !notice.isEmpty();
            if (!newer && !hasNotice) {
                if (!silent) android.widget.Toast.makeText(ctx, "已是最新（v" + moduleVersion() + "）", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder msg = new StringBuilder();
            if (hasNotice) msg.append("📢 ").append(notice).append("\n\n");
            if (newer) {
                msg.append("发现新版 v").append(vn).append("（当前 v").append(moduleVersion()).append("）");
                if (!changelog.isEmpty()) msg.append("\n\n").append(changelog);
            }
            AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                    .setTitle(newer ? "🔄 有新版本" : "📢 公告")
                    .setMessage(msg.toString());
            final String durl = dl, churl = channel;
            if (newer && !dl.isEmpty())
                b.setPositiveButton("去下载", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openUrl(ctx, durl); }
                });
            if (!channel.isEmpty())
                b.setNeutralButton("讨论群", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openUrl(ctx, churl); }
                });
            b.setNegativeButton("关闭", null).show();
        } catch (Throwable t) {
            if (!silent) android.widget.Toast.makeText(ctx, "更新信息解析失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

static void showDiagLog(final Context ctx) {
        final String log = Diag.read();
        // 只显示尾部(最近的更相关), 避免弹窗过长
        String shown = log;
        int MAX = 12000;
        if (shown.length() > MAX) shown = "...(仅显示最近部分, 复制可得完整)...\n" + shown.substring(shown.length() - MAX);

        final TextView tv = new TextView(ctx);
        tv.setText(shown);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);

        new AlertDialog.Builder(ctx)
                .setTitle("🧾 诊断日志")
                .setView(sv)
                .setPositiveButton("复制到剪贴板", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            String header = "【FeishuKit 诊断日志】\n";
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("fucklark_log", header + log));
                            android.widget.Toast.makeText(ctx, "已复制，粘贴到聊天发给作者即可", android.widget.Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(ctx, "复制失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Diag.clear();
                        android.widget.Toast.makeText(ctx, "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

static void showNotifArchive(final Context ctx) {
        final String content = NotifArchive.read();
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("📨 后台消息存档")
                    .setMessage("暂无记录。\n\n开启「后台消息存档」开关后，后台/离线时【被撤回】的消息会在这里留底原文（普通消息不记）。\n\n前提：飞书通知里开着「消息预览」（否则通知没有正文，无从抓取）。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {   // 倒序: 最新在上
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            String[] c = ln.split("\t", 3);
            if (c.length >= 3)      sb.append(c[0]).append("  ·  ").append(c[1]).append("\n").append(c[2]).append("\n\n");
            else                    sb.append(ln.replace("\t", "  ·  ")).append("\n\n");
        }
        final TextView tv = new TextView(ctx);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        new AlertDialog.Builder(ctx)
                .setTitle("📨 后台消息存档")
                .setView(sv)
                .setPositiveButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("notif_archive", content));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(ctx, "复制失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        NotifArchive.clear();
                        android.widget.Toast.makeText(ctx, "存档已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

static void showLeaveLog(final Context ctx) {
        final java.io.File f = new java.io.File("/data/data/" + PKG + "/files/leave_log.txt");
        String content = "";
        try { if (f.exists()) content = new String(Diag.readBytes(f), "UTF-8"); } catch (Throwable ignored) {}
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("👋 退群/移除记录")
                    .setMessage("暂无记录。开启「退群提醒」开关后，有人退群/被移出你所在的群时会记到这里（并弹 Toast）。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = lines.length - 1; i >= 0; i--) {   // 倒序: 最新在上
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            sb.append(ln.replace("\t", "  ·  ")).append("\n");
            cnt++;
        }
        final String full = content;
        final TextView tv = new TextView(ctx);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        new AlertDialog.Builder(ctx)
                .setTitle("👋 退群/移除记录（" + cnt + "）")
                .setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("fucklark_leave", full));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {}
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try { f.delete(); } catch (Throwable t) {}
                        android.widget.Toast.makeText(ctx, "已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

static void showKickedExports(final Context ctx) {
        java.io.File dir = new java.io.File("/data/data/" + PKG + "/files");
        final java.io.File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(java.io.File d, String n) { return n.startsWith("kicked_") && n.endsWith(".txt"); }
        });
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(ctx).setTitle("📤 被踢群聊天记录")
                    .setMessage("暂无记录。被移出群聊时（需开启「保留被踢群聊天记录」开关）会自动导出到这里。")
                    .setPositiveButton("好", null).show();
            return;
        }
        java.util.Arrays.sort(files, new Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        // 预读每个群的 群名 + 全文(小文件, 供搜索群名/消息内容)
        final String[] gname = new String[files.length];
        final String[] lower = new String[files.length];   // 群名+内容 小写, 供匹配
        for (int i = 0; i < files.length; i++) {
            String label = files[i].getName(), content = "";
            try {
                content = new String(Diag.readBytes(files[i]), "UTF-8");
                int nl = content.indexOf('\n');
                String first = nl > 0 ? content.substring(0, nl) : content;
                if (first.startsWith("群: ")) label = first.substring(3).trim();
            } catch (Throwable t) {}
            gname[i] = label;
            lower[i] = (label + " " + content).toLowerCase();
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 14);
        root.setPadding(p, p, p, dp(ctx, 6));
        final EditText search = new EditText(ctx);
        search.setHint("🔍 搜索群名 / 消息内容");
        search.setTextSize(14);
        search.setSingleLine(true);
        root.addView(search);
        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(listBox);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380));
        sv.setLayoutParams(svlp);
        root.addView(sv);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("📤 被踢群聊天记录（" + files.length + "）")
                .setView(root).setNegativeButton("关闭", null).create();

        final Runnable[] repop = new Runnable[1];
        repop[0] = new Runnable() {
            @Override public void run() {
                String q = search.getText().toString().trim().toLowerCase();
                listBox.removeAllViews();
                int shown = 0;
                for (int i = 0; i < files.length; i++) {
                    if (q.length() > 0 && lower[i].indexOf(q) < 0) continue;
                    final java.io.File f = files[i];
                    TextView row = new TextView(ctx);
                    row.setText("💬 " + gname[i] + "   (" + (f.length() / 1024 + 1) + "KB)");
                    row.setTextSize(16);
                    row.setTextColor(Color.parseColor("#3B9EFF"));
                    row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dlg.dismiss(); showKickedFile(ctx, f); }
                    });
                    listBox.addView(row);
                    shown++;
                }
                if (shown == 0) {
                    TextView e = new TextView(ctx); e.setText("无匹配"); e.setTextColor(Color.parseColor("#9AA0A6"));
                    e.setPadding(0, dp(ctx, 12), 0, 0); listBox.addView(e);
                }
            }
        };
        repop[0].run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { repop[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        dlg.show();
    }

static void showKickedFile(final Context ctx, final java.io.File file) {
        String content;
        try { content = new String(Diag.readBytes(file), "UTF-8"); }
        catch (Throwable t) { content = "读取失败: " + t; }
        String title = file.getName();
        final StringBuilder share = new StringBuilder();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 10);
        box.setPadding(pad, pad, pad, pad);

        for (String line : content.split("\n")) {
            if (line.startsWith("群: ")) { title = line.substring(3).trim(); continue; }
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String time = p[0], name = p[1], text = p[2];
            share.append(name).append(" (").append(time).append("): ").append(text).append("\n");
            // 系统消息: 小灰字居中(native 已拼成"cheky 邀请了 林觉圣")
            if ("系统".equals(name)) {
                String st = text.replace("{from_user}", "某人").replace("{to_chatters}", "某成员");
                if (st.length() > 80) st = st.substring(0, 80);
                TextView sv2 = new TextView(ctx);
                sv2.setText("— " + st + " —");
                sv2.setTextSize(10); sv2.setTextColor(Color.parseColor("#9AA0A6"));
                sv2.setGravity(Gravity.CENTER);
                sv2.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
                box.addView(sv2);
                continue;
            }
            // 头: 昵称 · 时间
            TextView h = new TextView(ctx);
            h.setText(name + "  ·  " + time);
            h.setTextSize(11); h.setTextColor(Color.parseColor("#9AA0A6"));
            h.setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 2));
            box.addView(h);
            // 气泡
            TextView bub = new TextView(ctx);
            bub.setText(text);
            bub.setTextSize(15); bub.setTextColor(Color.parseColor("#111111"));
            bub.setTextIsSelectable(true);
            bub.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#E8F0FE")); bg.setCornerRadius(dp(ctx, 14));
            bub.setBackground(bg);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(bub);
            box.addView(row);
        }
        if (box.getChildCount() == 0) {
            TextView e = new TextView(ctx); e.setText("(无可显示的消息)"); e.setTextColor(Color.parseColor("#DDDDDD"));
            box.addView(e);
        }
        ScrollView sv = new ScrollView(ctx); sv.addView(box);
        final String shareText = "群: " + title + "\n" + share.toString();
        new AlertDialog.Builder(ctx).setTitle("💬 " + title).setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("kicked", shareText));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {}
                    }
                })
                .setNeutralButton("分享", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND); s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_TEXT, shareText);
                            ctx.startActivity(Intent.createChooser(s, "分享被踢群记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) {}
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

/** 赞赏码位图缓存（进程内只解一次）。 */
    private static volatile Bitmap rewardCache;

    /**
     * 加载 assets/reward.png：
     * - 模块进程：直接读本 APK assets（不依赖 MODULE_PATH）；
     * - 飞书进程：从 MODULE_PATH / 模块包 sourceDir 的 APK zip 内读 entry。
     */
    static Bitmap loadReward(Context ctx) {
        Bitmap hit = rewardCache;
        if (hit != null && !hit.isRecycled()) return hit;
        try {
            if (ctx != null && "com.chekayo.feishuantirecall".equals(ctx.getPackageName())) {
                InputStream is = ctx.getAssets().open("reward.png");
                try {
                    Bitmap bm = decodeRewardStream(is);
                    if (bm != null) { rewardCache = bm; return bm; }
                } finally { is.close(); }
            }
            String mp = moduleApkPath();
            if (mp == null && ctx != null) {
                try {
                    mp = ctx.getPackageManager().getApplicationInfo("com.chekayo.feishuantirecall", 0).sourceDir;
                } catch (Throwable ignored) { }
            }
            if (mp != null) {
                ZipFile zf = new ZipFile(mp);
                try {
                    ZipEntry e = zf.getEntry("assets/reward.png");
                    if (e == null) e = zf.getEntry("reward.png");
                    if (e != null) {
                        InputStream is = zf.getInputStream(e);
                        try {
                            Bitmap bm = decodeRewardStream(is);
                            if (bm != null) { rewardCache = bm; return bm; }
                        } finally { is.close(); }
                    }
                } finally { zf.close(); }
            }
        } catch (Throwable t) {
            android.util.Log.w("fucklark", "reward load err " + t);
        }
        return null;
    }

    private static Bitmap decodeRewardStream(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = is.read(buf)) != -1) bo.write(buf, 0, r);
        byte[] b = bo.toByteArray();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 2;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    static void showReward(final Context ctx) {
        Bitmap bmp = loadReward(ctx);
        if (bmp == null) {
            new AlertDialog.Builder(ctx).setTitle("赞赏 FeishuKit")
                    .setMessage("赞赏码资源缺失。可打开讨论群或项目主页支持作者。\n\n赞助纯属鼓励，与功能无关，所有功能开源免费。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        showReward(ctx, bmp);
    }

    static void showReward(final Context ctx, Bitmap bmp) {
        ImageView iv = new ImageView(ctx);
        int sz = dp(ctx, 300);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        iv.setImageBitmap(bmp);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        int p = dp(ctx, 16);
        wrap.setPadding(p, p, p, p);
        wrap.addView(iv);
        new AlertDialog.Builder(ctx)
                .setTitle("赞赏 FeishuKit")
                .setMessage("感谢支持维护与版本适配。\n微信「扫一扫 → 相册」或长按识别二维码。\n赞助纯属鼓励，与功能解锁无关。")
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

static void showResignedList(final Context ctx) {
        try {
            java.io.File rf = archiveFile("resigned_all.json");
            java.io.File pf = archiveFile("profiles.json");
            final JSONObject resigned = rf.exists() ? new JSONObject(Config.read(rf)) : new JSONObject();
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();

            List<String> ids = new ArrayList<String>();
            Iterator<String> it = resigned.keys();
            while (it.hasNext()) ids.add(it.next());
            // 按飞书 update_time(该 chatter 行离职/冻结变更时刻)降序 -> 真正最近离职在最上面。
            // 注意: first_seen 只是"模块首次把这行读进缓存的时刻", 会因你搜索/打开某人而变成今天,
            //       不代表其今天离职(如龙科宇 update_time=4月却 first_seen=今天=旧记录刚被缓存)。
            Collections.sort(ids, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    long ua = optLong(resigned.optJSONObject(a), "update_time");
                    long ub = optLong(resigned.optJSONObject(b), "update_time");
                    return Long.compare(ub, ua);
                }
            });
            long nowSec = System.currentTimeMillis() / 1000L;

            final LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(ctx, 16);
            box.setPadding(p, p, p, p);

            final List<String> idsF = ids;
            final JSONObject resignedF = resigned, profilesF = profiles;
            final long nowSecF = nowSec;
            populateResigned(ctx, box, idsF, resignedF, profilesF, nowSecF, "");

            // 顶部搜索框: 按姓名/部门/邮箱/工号/职务 实时过滤
            final EditText search = new EditText(ctx);
            search.setHint("🔍 搜索 姓名/部门/邮箱/工号/职务");
            search.setTextSize(15);
            search.setSingleLine(true);
            search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    populateResigned(ctx, box, idsF, resignedF, profilesF, nowSecF, e.toString());
                }
            });

            ScrollView sv = new ScrollView(ctx);
            sv.addView(box);
            LinearLayout rootv = new LinearLayout(ctx);
            rootv.setOrientation(LinearLayout.VERTICAL);
            rootv.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            new AlertDialog.Builder(ctx)
                    .setTitle("离职名单（" + ids.size() + " 人）· 按记录更新排序（非离职时间）")
                    .setView(rootv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
        }
    }

static void populateResigned(final Context ctx, LinearLayout box, List<String> ids,
                                 JSONObject resigned, JSONObject profiles, long nowSec, String query) {
        box.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (final String uid : ids) {
            JSONObject r = resigned.optJSONObject(uid);
            JSONObject pr = profiles.optJSONObject(uid);
            String name = r != null ? r.optString("name", uid) : uid;

            if (!q.isEmpty()) {
                StringBuilder hay = new StringBuilder(name).append(' ').append(uid);
                if (r != null) hay.append(' ').append(r.optString("en_us_name", ""));
                if (pr != null) hay.append(' ').append(pr.optString("department", ""))
                        .append(' ').append(pr.optString("email", ""))
                        .append(' ').append(pr.optString("employee_id", ""))
                        .append(' ').append(pr.optString("position", ""))
                        .append(' ').append(pr.optString("leader", ""));
                if (!hay.toString().toLowerCase().contains(q)) continue;
            }

            // 注: 飞书本地无真实离职时间列; update_time 只是"该记录本地最后刷新时刻", 仅供参考排序。
            long rt = optLong(r, "update_time");
            StringBuilder sb = new StringBuilder();
            sb.append("👤 ").append(name);
            if (rt > 0) sb.append("   · 记录更新 ").append(fmtDate(rt));
            if (pr != null) {
                add(sb, "部门", pr.optString("department", ""));
                add(sb, "邮箱", pr.optString("email", ""));
                add(sb, "工号", pr.optString("employee_id", ""));
                add(sb, "职务", pr.optString("position", ""));
                add(sb, "手机", pr.optString("phone", ""));
                add(sb, "上级", pr.optString("leader", ""));
            } else {
                sb.append("\n  （详情未存档——在职时打开过其资料页才会有）");
            }

            TextView row = new TextView(ctx);
            row.setText(sb.toString());
            row.setTextSize(14);
            row.setTextColor(Color.parseColor("#DDDDDD"));
            row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChat(ctx, uid); }
            });
            box.addView(row);
            View div = new View(ctx);
            div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配结果");
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            box.addView(empty);
        }
    }

static void add(StringBuilder sb, String label, String v) {
        if (v != null && !v.isEmpty()) sb.append("\n  ").append(label).append("：").append(v);
    }

static long optLong(JSONObject o, String k) {
        if (o == null) return 0;
        try { return Long.parseLong(o.optString(k, "0")); } catch (Throwable t) { return o.optLong(k, 0); }
    }

static long firstSeenSec(JSONObject o) {
        long t = optLong(o, "first_seen");
        return t > 100000000000L ? t / 1000L : t;   // >~1e11 视为毫秒
    }

static String fmtDate(long sec) {
        if (sec <= 0) return "?";
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(sec * 1000L));
        } catch (Throwable t) { return "?"; }
    }

static void openChat(Context ctx, String uid) {
        try {
            Intent i = new Intent();
            i.setClassName(PKG, "com.ss.android.lark.profile.func.v3.userprofile.UserProfileActivityV3");
            i.putExtra("param_key_user_id", uid);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
        }
    }

static void openUrl(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
        }
    }

static boolean isNight(Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return true; }  // 拿不到就按深色, 保证标题够亮
    }

static int profilesCount() {
        try {
            File f = archiveFile("profiles.json");
            if (!f.exists()) return 0;
            return new org.json.JSONObject(Config.read(f)).length();
        } catch (Throwable t) { return -1; }
    }

static void showAllProfiles(final Context ctx) {
        try {
            File pf = archiveFile("profiles.json");
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();

            final List<String> ids = new ArrayList<String>();
            Iterator<String> it = profiles.keys();
            while (it.hasNext()) ids.add(it.next());
            // 排序: 本公司在前; 组内按部门, 再按姓名
            Collections.sort(ids, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    JSONObject pa = profiles.optJSONObject(a), pb = profiles.optJSONObject(b);
                    boolean ha = pa != null && pa.optBoolean("is_home", false);
                    boolean hb = pb != null && pb.optBoolean("is_home", false);
                    if (ha != hb) return ha ? -1 : 1;
                    String da = pa != null ? pa.optString("department", "") : "";
                    String db = pb != null ? pb.optString("department", "") : "";
                    int c = da.compareTo(db);
                    if (c != 0) return c;
                    String na = pa != null ? pa.optString("name", a) : a;
                    String nb = pb != null ? pb.optString("name", b) : b;
                    return na.compareTo(nb);
                }
            });

            final LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(ctx, 16);
            box.setPadding(p, p, p, p);
            populateProfiles(ctx, box, ids, profiles, "");

            final EditText search = new EditText(ctx);
            search.setHint("🔍 搜索 姓名/部门/邮箱/工号/职务/公司");
            search.setTextSize(15);
            search.setSingleLine(true);
            search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) { populateProfiles(ctx, box, ids, profiles, e.toString()); }
            });

            // 统计: 本公司 / 完整档案 / 外部, 用于顶部说明
            int homeN = 0, homeFull = 0, extN = 0;
            for (String uid : ids) {
                JSONObject pr = profiles.optJSONObject(uid);
                if (pr == null) continue;
                if (pr.optBoolean("is_home", false)) {
                    homeN++;
                    // 有明细 = 部门/邮箱/职务 任一(不强求工号: 化名同事本就无工号)
                    if (pr.optString("department", "").length() > 0
                            || pr.optString("email", "").length() > 0
                            || pr.optString("position", "").length() > 0) homeFull++;
                } else extN++;
            }
            final boolean night = isNight(ctx);
            TextView hint = new TextView(ctx);
            hint.setText("🏢 本公司 " + homeN + "（有明细 " + homeFull + "）· 🌐 外部 " + extN + "\n"
                    + "自动归档，无需逐个点击。想收录更多人 / 补全资料：进飞书【通讯录 → 组织架构】，把各部门展开、上下滑到底，"
                    + "让飞书加载他们的资料，模块随即自动存档。\n"
                    + "离职后仍保留在职时抓到的部门/工号/职务等（只增不删）。点任意一行可打开其资料页。");
            hint.setTextSize(12);
            hint.setTextColor(night ? 0xFFAAB4C0 : 0xFF666666);
            hint.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

            ScrollView sv = new ScrollView(ctx);
            sv.addView(box);
            LinearLayout rootv = new LinearLayout(ctx);
            rootv.setOrientation(LinearLayout.VERTICAL);
            rootv.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            new AlertDialog.Builder(ctx)
                    .setTitle("全员档案（" + ids.size() + " 人）")
                    .setView(rootv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
        }
    }

static void populateProfiles(final Context ctx, LinearLayout box, List<String> ids,
                                 JSONObject profiles, String query) {
        box.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (final String uid : ids) {
            JSONObject pr = profiles.optJSONObject(uid);
            if (pr == null) continue;
            String name = pr.optString("name", uid);
            boolean home = pr.optBoolean("is_home", false);
            String company = pr.optString("company", "");

            if (!q.isEmpty()) {
                StringBuilder hay = new StringBuilder(name).append(' ').append(uid)
                        .append(' ').append(pr.optString("department", ""))
                        .append(' ').append(pr.optString("email", ""))
                        .append(' ').append(pr.optString("employee_id", ""))
                        .append(' ').append(pr.optString("position", ""))
                        .append(' ').append(pr.optString("leader", ""))
                        .append(' ').append(pr.optString("phone", ""))
                        .append(' ').append(company);
                if (!hay.toString().toLowerCase().contains(q)) continue;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(home ? "🏢 " : "🌐 ").append(name);
            if (pr.optBoolean("is_resigned", false)) sb.append("  · 已离职");
            if (!home) add(sb, "公司", company);
            add(sb, "部门", pr.optString("department", ""));
            add(sb, "职务", pr.optString("position", ""));
            add(sb, "工号", pr.optString("employee_id", ""));
            add(sb, "邮箱", pr.optString("email", ""));
            add(sb, "手机", pr.optString("phone", ""));
            add(sb, "上级", pr.optString("leader", ""));
            add(sb, "群昵称", pr.optString("nickname", ""));

            TextView row = new TextView(ctx);
            row.setText(sb.toString());
            row.setTextSize(14);
            row.setTextColor(Color.parseColor(home ? "#DDDDDD" : "#B0C4DE"));
            row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChat(ctx, uid); }
            });
            box.addView(row);
            View div = new View(ctx);
            div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配结果");
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            box.addView(empty);
        }
    }

static int resignCount() {
        try {
            File f = archiveFile("resigned_all.json");
            if (!f.exists()) return 0;
            String s = Config.read(f);
            // 顶层 JSON 对象, 键数 = 人数
            int n = 0, i = 0;
            org.json.JSONObject o = new org.json.JSONObject(s);
            return o.length();
        } catch (Throwable t) { return -1; }
    }

static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    static String moduleApkPath() {
        try {
            // 仅飞书进程有 MODULE_PATH；桌面进程返回 null
            Class<?> c = Class.forName("com.chekayo.feishuantirecall.AntiRecall");
            Object v = c.getField("MODULE_PATH").get(null);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) { return null; }
    }

    static String moduleVersion() { return "1.8.0"; }

    static int moduleVersionCode() { return 22; }
}

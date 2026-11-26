package com.ilatv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 「直播订阅」管理对话框，全屏选台页与直播播放页共用同一份实现。
 *
 * 以前播放页的「订阅管理」会先启动 LiveActivity（全屏选台页）再在它上面弹框，
 * 于是背景里多出一整屏分组/频道列表，还会把正在播放的直播页压到后台。
 * 现在两处都直接在各自界面上弹这个对话框：播放页弹出时直播继续播，
 * 背后不再出现全屏选台页。
 *
 * onChanged：订阅增删改后回调（选台页重载列表 / 播放页重新拉频道）。
 * tracker：播放页需要把每个弹出的对话框登记给选台抽屉，模态期间把
 *          方向键/确定键交还系统，否则遥控器没法操作列表和输入框。
 */
public final class LiveSubsDialog {

    public interface Tracker {
        void track(AlertDialog d);
    }

    private LiveSubsDialog() {}

    public static void show(final Activity act, final Runnable onChanged,
                            final Tracker tracker) {
        final List<Model.LiveSub> subs = Store.liveSubs(act);
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), 0);

        List<String> rows = new ArrayList<>();
        for (Model.LiveSub s : subs) {
            rows.add(s.name + (s.enabled ? "" : "  [已停用]") + "\n" + s.url);
        }
        if (rows.isEmpty()) rows.add("还没有订阅。下方填一份公开 M3U 或 TXT 地址即可。");

        ListView lv = new ListView(act);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                act, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View cv, ViewGroup parent) {
                View v = super.getView(position, cv, parent);
                TextView t = v.findViewById(android.R.id.text1);
                if (t != null) {
                    t.setTextColor(position < subs.size() && !subs.get(position).enabled
                            ? Ui.SUB : Ui.TEXT);
                    t.setTextSize(12);
                }
                return v;
            }
        };
        lv.setAdapter(adapter);

        final AlertDialog[] holder = new AlertDialog[1];
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= subs.size()) return;
                if (holder[0] != null) holder[0].dismiss();
                edit(act, pos, onChanged, tracker);
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                if (pos >= subs.size()) return true;
                AlertDialog confirm = new AlertDialog.Builder(act)
                        .setTitle("删除订阅")
                        .setMessage(subs.get(pos).name + "\n" + subs.get(pos).url)
                        .setPositiveButton("删除", (d, w) -> {
                            Store.removeLiveSub(act, pos);
                            if (holder[0] != null) holder[0].dismiss();
                            changed(act, onChanged);
                        })
                        .setNegativeButton("取消", null)
                        .create();
                track(tracker, confirm);
                confirm.show();
                return true;
            }
        });
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 200)));

        final EditText name = Ui.input(act, "订阅名称（可留空）");
        final EditText url = Ui.input(act, "https://域名/list.m3u / .m3u8 / .txt");
        box.addView(name);
        box.addView(gap(act, 8));
        box.addView(url);

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("直播订阅")
                .setMessage("点击条目编辑 / 启停，长按删除；停用的订阅不再拉取。\n"
                        + "M3U 与 TXT 按内容自动识别：TXT 支持 #genre# 分组、名称,地址、多线路，GBK 自动识别。")
                .setView(box)
                .setPositiveButton("添加订阅", (dlg, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Toast.makeText(act, "请填完整 http(s) 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String n = name.getText().toString().trim();
                    Store.addLiveSub(act, new Model.LiveSub(
                            n.isEmpty() ? "订阅" + (subs.size() + 1) : n, u, true));
                    changed(act, onChanged);
                    Toast.makeText(act, "已添加订阅", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .create();
        holder[0] = d;
        track(tracker, d);
        d.show();
    }

    private static void edit(final Activity act, final int pos,
                             final Runnable onChanged, final Tracker tracker) {
        final List<Model.LiveSub> subs = Store.liveSubs(act);
        if (pos < 0 || pos >= subs.size()) return;
        final Model.LiveSub old = subs.get(pos);

        LinearLayout form = Ui.column(act);
        form.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), 0);
        final EditText name = Ui.input(act, "订阅名称");
        final EditText url = Ui.input(act, "https://域名/list.m3u / .m3u8 / .txt");
        name.setText(old.name);
        url.setText(old.url);
        name.setSelection(name.getText().length());
        url.setSelection(url.getText().length());
        form.addView(name);
        form.addView(gap(act, 10));
        form.addView(url);

        final CheckBox enabled = new CheckBox(act);
        enabled.setText("启用此订阅（停用后不再拉取）");
        enabled.setTextColor(Ui.TEXT);
        enabled.setTextSize(13);
        enabled.setChecked(old.enabled);
        enabled.setPadding(Ui.dp(act, 4), Ui.dp(act, 10), 0, 0);
        form.addView(enabled);

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("编辑订阅")
                .setView(form)
                .setPositiveButton("保存", (dlg, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Toast.makeText(act, "请填完整 http(s) 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (int i = 0; i < subs.size(); i++) {
                        if (i != pos && subs.get(i).url.equals(u)) {
                            Toast.makeText(act, "这个地址已经在列表里了",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    Store.updateLiveSub(act, pos,
                            new Model.LiveSub(n.isEmpty() ? "未命名订阅" : n, u,
                                    enabled.isChecked()));
                    changed(act, onChanged);
                    Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("删除", (dlg, w) -> {
                    Store.removeLiveSub(act, pos);
                    changed(act, onChanged);
                })
                .setNegativeButton("取消", null)
                .create();
        track(tracker, d);
        d.show();
    }

    /** 订阅变了就丢弃旧频道缓存，与网页后台保存订阅后的处理保持一致 */
    private static void changed(Activity act, Runnable onChanged) {
        Store.clearLiveCache(act);
        if (onChanged != null) onChanged.run();
    }

    private static void track(Tracker tracker, AlertDialog d) {
        if (tracker != null) tracker.track(d);
    }

    private static View gap(Activity act, int dp) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(act, dp)));
        return v;
    }
}

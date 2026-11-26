package com.ilatv.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** 安装完成后（系统广播 / PackageInstaller 回调）销毁推送的 APK 安装包 */
public class InstallEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ApkInstaller.cleanup(c);
        try {
            if (ApkInstaller.ACTION_INSTALL_STATUS.equals(i.getAction())) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                Toast.makeText(c, st == PackageInstaller.STATUS_SUCCESS
                        ? "升级安装完成，安装包已清理" : "安装未完成，安装包已清理",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(c, "升级安装完成，安装包已清理", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }
}

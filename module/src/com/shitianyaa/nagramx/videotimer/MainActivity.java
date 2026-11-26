package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 模块状态展示页面。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        status.setTextIsSelectable(true);
        status.setTextSize(16f);

        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(status);
        setContentView(layout);

        status.setText(getApplicationInfo().loadLabel(getPackageManager()) + "\n"
                + "听视频 · 后台播放 · 音乐定时\n\n"
                + "在 LSPosed 中启用模块、设置作用域后，重启目标应用。\n\n"
                + targetStatus());
    }

    private String targetStatus() {
        StringBuilder sb = new StringBuilder();
        for (String packageName : HostProfile.SUPPORTED_PACKAGES) {
            PackageInfo packageInfo = getTargetPackageInfo(packageName);
            if (packageInfo == null) {
                sb.append("未检测到 ").append(packageName).append('\n');
                continue;
            }
            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.getLongVersionCode();
            } else {
                versionCode = packageInfo.versionCode;
            }
            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "unknown";
            sb.append(String.format("目标：%s %s %s (%d)\n",
                    HostProfile.hostDisplayName(packageName),
                    packageName,
                    versionName,
                    versionCode));
        }
        sb.append("\n已验证宿主：NagramX 12.9.2-4335a2e (1260)；NiagramX 13.2.2 (1322)\n")
                .append("不限制 versionCode，运行时按类和方法签名探测兼容性");
        return sb.toString();
    }

    private PackageInfo getTargetPackageInfo(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return getPackageManager().getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                return getPackageManager().getPackageInfo(packageName, 0);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}

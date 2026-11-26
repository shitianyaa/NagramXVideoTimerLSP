package com.shitianyaa.nagramx.videotimer;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import com.shitianyaa.nagramx.videotimer.core.Xp;

/**
 * libxposed 模块主入口。
 */
public class ModuleMain extends XposedModule {

    private boolean isMainProcess = false;
    private boolean hooksInstalled = false;
    private NagramXHooks hooks;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.bind(this);
        isMainProcess = HostProfile.isSupportedPackage(param.getProcessName());
        Xp.log("模块已加载：process=" + param.getProcessName() +
                ", framework=" + getFrameworkName() + "/" + getFrameworkVersionCode() +
                ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Xp.bind(this);
        if (!isMainProcess || hooksInstalled || !param.isFirstPackage() ||
                !HostProfile.isSupportedPackage(param.getPackageName())) {
            return;
        }

        try {
            HostProfile profile = HostProfile.discover(param.getPackageName(), param.getClassLoader(),
                    param.getApplicationInfo(), (msg, t) -> Xp.log(msg, t));
            if (profile == null) return;
            Xp.log("宿主：" + HostProfile.hostDisplayName(param.getPackageName()) +
                    " (" + param.getPackageName() + ")");
            hooks = new NagramXHooks(this, profile);
            hooks.install();
            hooksInstalled = true;
        } catch (Throwable t) {
            Xp.log("安装后台视频定时 Hook 失败", t);
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        Xp.bind(this);
        Xp.log("正在执行模块热重载，卸载旧 Hook...");
        if (hooks != null) {
            hooks.dispose();
            hooks = null;
        }
        Xp.unhookAll();
        hooksInstalled = false;
        return true;
    }
}

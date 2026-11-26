package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.PopupWindow;
import com.shitianyaa.nagramx.videotimer.core.Xp;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** 音乐播放器更多菜单的定时入口，宿主已有菜单项保持原有监听器。 */
final class AudioTimerMenu {
    private static final int TIMER_ITEM_ID = 0x4e565401;
    private final HostProfile host;
    private final HostStrings strings;
    private final AudioSleepTimerAgent agent;
    private final BooleanSupplier prepare;
    private final VideoSleepTimerSheet sheet;
    private final HostBridge.Logger logger;
    private final Method optionsBuilder;
    private final Map<Object, WeakReference<View>> rows = new WeakHashMap<>();
    private WeakReference<Context> lastContext = new WeakReference<>(null);
    private boolean disposed;

    AudioTimerMenu(HostProfile host, HostTheme theme, HostStrings strings, HostBridge.Logger logger,
                   AudioSleepTimerAgent agent, BooleanSupplier prepare) {
        this.host = host;
        this.strings = strings;
        this.agent = agent;
        this.prepare = prepare;
        this.logger = logger;
        optionsBuilder = findOptionsBuilder(host);
        sheet = new VideoSleepTimerSheet(host, theme, strings, logger, true);
    }

    /** 新宿主每次打开更多菜单都重建 ItemOptions，在构造保存子菜单前追加第一项。 */
    boolean install() {
        if (optionsBuilder == null) return true;
        return Xp.hook(optionsBuilder, chain -> {
            Object alert = chain.getThisObject();
            Object button = host.bridge.getField(alert, "optionsButton");
            for (Object argument : chain.getArgs()) {
                if (argument != null && optionsBuilder.getReturnType().isInstance(argument)
                        && host.bridge.getField(argument, "scrimView") == button) {
                    attachOptions(argument, button);
                }
            }
            return chain.proceed();
        }) != null;
    }

    static Method findOptionsBuilder(HostProfile host) {
        return host.bridge.findUniqueByShape(host.playerUi.alertClass, "audioMoreOptions", method -> {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 2) return false;
            Class<?> result = method.getReturnType();
            if (host.bridge.findField(result, "scrimView") == null
                    || host.bridge.findField(result, "actionBarPopupWindow") == null) return false;
            Class<?>[] parameters = method.getParameterTypes();
            return (parameters[0] == result && parameters[1] == host.messageObjectClass)
                    || (parameters[1] == result && parameters[0] == host.messageObjectClass);
        });
    }

    private void attachOptions(Object options, Object button) {
        if (disposed || !(button instanceof View) || !prepare.getAsBoolean() || rows.containsKey(options)) return;
        Context context = ((View) button).getContext();
        Class<?> itemClass = host.bridge.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
        Method add = host.bridge.findUniqueByShape(options.getClass(), "addAudioTimerItem", method ->
                !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == itemClass && method.getReturnType() == void.class);
        if (itemClass == null || add == null) {
            logger.log("音频定时入口不可用：更多菜单缺少原生菜单项追加接口", null);
            return;
        }
        Object provider = host.bridge.getField(options, "resourcesProvider");
        Object added = host.bridge.newInstance(itemClass, context, false, false, provider);
        if (!(added instanceof View)) return;
        View row = (View) added;
        int icon = context.getResources().getIdentifier("photo_timer", "drawable", context.getPackageName());
        host.bridge.invokeNamed(row, "setTextAndIcon", strings.get(context, "VideoSleepTimer"), icon);
        host.bridge.invoke(options, add, row);
        if (row.getParent() == null) return;
        bindRow(options, row, true);
    }

    void attach(Object alert) {
        if (disposed || alert == null) return;
        if (optionsBuilder != null) { refresh(); return; }
        Object menu = host.bridge.getField(alert, "optionsButton");
        if (!(menu instanceof View)) return;
        WeakReference<View> existing = rows.get(menu);
        if (existing != null && existing.get() != null) {
            refresh();
            return;
        }
        if (!prepare.getAsBoolean()) return;
        Context context = ((View) menu).getContext();
        int icon = context.getResources().getIdentifier("photo_timer", "drawable", context.getPackageName());
        Object added = host.bridge.invokeNamed(menu, "addSubItem", TIMER_ITEM_ID, icon,
                (CharSequence) strings.get(context, "VideoSleepTimer"));
        if (!(added instanceof View)) return;
        View row = (View) added;
        // 旧宿主使用固定子菜单，把入口放到第一项以适应矮播放器。
        if (row.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) row.getParent();
            parent.removeView(row);
            parent.addView(row, 0);
        }
        bindRow(menu, row, false);
    }

    private void bindRow(Object menu, View row, boolean options) {
        rows.put(menu, new WeakReference<>(row));
        lastContext = new WeakReference<>(row.getContext());
        WeakReference<Object> menuRef = new WeakReference<>(menu);
        row.setOnClickListener(view -> {
            Object currentMenu = menuRef.get();
            if (currentMenu != null) {
                if (options) {
                    Object popup = host.bridge.getField(currentMenu, "actionBarPopupWindow");
                    if (popup instanceof PopupWindow) ((PopupWindow) popup).dismiss();
                } else {
                    host.bridge.invokeNamed(currentMenu, "closeSubMenu");
                }
            }
            Activity activity = activity(view.getContext());
            if (disposed || activity == null || activity.isFinishing() || activity.isDestroyed()
                    || !prepare.getAsBoolean()) return;
            sheet.show(activity, agent.snapshot(), (mode, minutes) -> {
                if (!disposed && prepare.getAsBoolean()) agent.arm(minutes);
            }, agent::cancel);
        });
        refresh();
    }

    void refresh() {
        if (disposed) return;
        TimerState state = agent.snapshot();
        for (WeakReference<View> reference : rows.values()) {
            View row = reference.get();
            if (row == null) continue;
            boolean available = prepare.getAsBoolean();
            row.setVisibility(available ? View.VISIBLE : View.GONE);
            Context context = row.getContext();
            String text = strings.get(context, "VideoSleepTimer");
            if (state.active) {
                text += " · " + agent.remaining();
            }
            host.bridge.invokeNamed(row, "setText", (CharSequence) text);
        }
    }

    void finished() {
        Context context = lastContext.get();
        if (context != null && !disposed) {
            Toast.makeText(context, strings.get(context, "AudioSleepTimerFinished"), Toast.LENGTH_SHORT).show();
        }
    }

    void dispose() {
        disposed = true;
        for (WeakReference<View> reference : rows.values()) {
            View row = reference.get();
            if (row != null) new Handler(Looper.getMainLooper()).post(() -> {
                row.setOnClickListener(null);
                if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
            });
        }
        rows.clear();
        lastContext.clear();
        new Handler(Looper.getMainLooper()).post(sheet::dismiss);
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return context instanceof Activity ? (Activity) context : null;
    }
}

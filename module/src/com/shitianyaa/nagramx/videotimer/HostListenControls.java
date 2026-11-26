package com.shitianyaa.nagramx.videotimer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class HostListenControls {
    private static final float[] SPEEDS = {0.5f, 1f, 1.2f, 1.5f, 1.7f, 2f};
    private static final String[] SPEED_ICONS = {
            "msg_speed_slow", "msg_speed_normal", "msg_speed_medium",
            "msg_speed_fast", "msg_speed_veryfast", "msg_speed_superfast"
    };
    private static final String[] SPEED_LABELS = {
            "SpeedSlow", "SpeedNormal", "SpeedMedium", "SpeedFast", "SpeedVeryFast", "SpeedSuperFast"
    };
    private static final String[] SKIP_LAYERS = {"Triangle 3", "Triangle 4", "Rectangle 4"};
    private final Context context;
    private final HostProfile host;
    private final HostBridge bridge;
    private final HostStrings strings;
    private final int tint;
    private final int accent;
    private Object speedIcon;
    private View[] speedOptions;

    HostListenControls(Context context, HostProfile host, HostStrings strings, int tint, int accent) {
        this.context = context;
        this.host = host;
        this.bridge = host.bridge;
        this.strings = strings;
        this.tint = tint;
        this.accent = accent;
    }

    View skip(boolean next, Runnable action) {
        String type = "org.telegram.ui.Components.RLottieImageView";
        Object instance = bridge.newInstance(type, context);
        int raw = resource("player_prev", "raw");
        if (!(instance instanceof ImageView) || raw == 0) return hidden();
        ImageView view = (ImageView) instance;
        if (host.niagram1322) bridge.invokeNamed(view, "i", raw, 20, 20, (Object) null);
        else bridge.invokeNamed(view, "setAnimation", raw, 20, 20);
        for (String layer : SKIP_LAYERS) {
            if (host.niagram1322) bridge.invokeNamed(view, "k", tint, layer);
            else bridge.invokeNamed(view, "setLayerColor", layer, tint);
        }
        view.setScaleType(ImageView.ScaleType.CENTER);
        if (next) view.setRotation(180f);
        view.setContentDescription(strings.get(context, next ? "ListenVideoNext" : "ListenVideoPrevious"));
        view.setOnClickListener(button -> {
            bridge.invokeNamed(view, host.niagram1322 ? "f" : "playAnimation");
            action.run();
        });
        return view;
    }

    View action(String icon, String name, Runnable action) {
        View view = menu(name);
        if (view.getVisibility() == View.GONE) return view;
        bridge.invokeNamed(view, "setIcon", resource(icon, "drawable"));
        view.setOnClickListener(button -> action.run());
        return view;
    }

    View speed(ListenVideoAgent player) {
        if (!player.canSetSpeed()) return hidden();
        View menu = menu("ListenVideoSpeed");
        if (menu.getVisibility() == View.GONE) return menu;
        speedIcon = bridge.newInstance("org.telegram.ui.Components.SpeedIconDrawable", true);
        if (speedIcon instanceof Drawable) {
            bridge.invokeNamed(speedIcon, host.niagram1322 ? "b" : "setColor", tint);
            bridge.invokeNamed(menu, "setIcon", speedIcon);
        } else {
            bridge.invokeNamed(menu, "setIcon", resource("msg_speed", "drawable"));
        }
        bridge.invokeNamed(menu, "setLongClickEnabled", false);
        bridge.invokeNamed(menu, "setShowSubmenuByMove", false);
        bridge.invokeNamed(menu, "setAnchor", menu);
        bridge.invokeNamed(menu, "setShowOnTop", true);
        speedOptions = new View[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) {
            float value = SPEEDS[i];
            Object item = bridge.invokeNamed(menu, "addSubItem", i, resource(SPEED_ICONS[i], "drawable"),
                    strings.get(context, SPEED_LABELS[i]));
            if (item instanceof View) {
                View option = (View) item;
                speedOptions[i] = option;
                option.setOnClickListener(view -> {
                    bridge.invokeNamed(menu, "closeSubMenu");
                    player.setSpeed(value);
                });
            }
        }
        // addSubItem initializes the host popup layout lazily.
        bridge.invokeNamed(menu, "setShowedFromBottom", true);
        menu.setOnClickListener(v -> {
            updateSpeed(player.speed());
            bridge.invokeNamed(menu, "toggleSubMenu");
        });
        return menu;
    }

    void updateSpeed(float speed) {
        bridge.invokeNamed(speedIcon, host.niagram1322 ? "c" : "setValue", speed, false);
        if (speedOptions == null) return;
        for (int i = 0; i < speedOptions.length; i++) {
            bridge.invokeNamed(speedOptions[i], "setEnabledByColor", Float.compare(speed, SPEEDS[i]) == 0, tint, accent);
        }
    }

    View seekBar(ListenVideoPage page) {
        Object instance = bridge.newInstance("org.telegram.ui.Components.SeekBarView", context);
        if (!(instance instanceof View)) return hidden();
        View view = (View) instance;
        Method setter = bridge.findMethod(view.getClass(), "setDelegate", 1, method ->
                method.getParameterTypes()[0].isInterface());
        if (setter == null) return hidden();
        Class<?> delegate = setter.getParameterTypes()[0];
        Object callback = Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate}, (proxy, method, args) -> {
            String name = method.getName();
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "Listen video seek delegate";
            if ("getContentDescription".equals(name)) return strings.get(context, "AccDescrPlayerSeekBar");
            if ("onSeekBarDrag".equals(name) || (host.niagram1322 && "e".equals(name))) {
                page.onSeekDrag((Boolean) args[host.niagram1322 ? 1 : 0],
                        ((Number) args[host.niagram1322 ? 0 : 1]).floatValue());
            } else if ("onSeekBarPressed".equals(name) || (host.niagram1322 && "j".equals(name))) {
                page.onSeekPressed((Boolean) args[0]);
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
        bridge.invoke(view, setter, callback);
        bridge.invokeNamed(view, "setReportChanges", true);
        bridge.invokeNamed(view, "setLineWidth", 4);
        bridge.invokeNamed(view, "setOuterColor", accent);
        bridge.invokeNamed(view, "setInnerColor", (tint & 0x00ffffff) | 0x33000000);
        return view;
    }

    void updateProgress(View view, float position) {
        bridge.invokeNamed(view, host.niagram1322 ? "j" : "setProgress", position, false);
    }

    private View menu(String label) {
        Object item = bridge.newInstance("org.telegram.ui.ActionBar.ActionBarMenuItem", context, null, 0, tint, false, null);
        if (!(item instanceof View)) return hidden();
        View view = (View) item;
        view.setContentDescription(strings.get(context, label));
        return view;
    }

    private int resource(String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private View hidden() {
        View view = new View(context);
        view.setVisibility(View.GONE);
        return view;
    }
}

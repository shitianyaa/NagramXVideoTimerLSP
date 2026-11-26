package com.shitianyaa.nagramx.videotimer;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

final class SharedVideoMedia {
    interface Result {
        void loaded(List<?> messages, boolean end);
    }

    private static final int VIDEOS_ONLY = 7;
    private static final int PAGE_SIZE = 40;

    private final HostBridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Class<?> connections;
    private final Class<?> mediaData;
    private final Class<?> notifications;
    private final Method load;
    private final Method addObserver;
    private final Method removeObserver;
    private final String accountAccessor;
    private final String notificationsAccessor;
    private final int event;
    private Runnable cancel;

    SharedVideoMedia(HostBridge bridge, boolean obfuscated) {
        this.bridge = bridge;
        connections = bridge.loadClass("org.telegram.tgnet.ConnectionsManager");
        mediaData = bridge.loadClass("org.telegram.messenger.MediaDataController");
        notifications = bridge.loadClass("org.telegram.messenger.NotificationCenter");
        accountAccessor = obfuscated ? "c0" : "getInstance";
        notificationsAccessor = obfuscated ? "g" : "getInstance";
        load = bridge.findMethod(mediaData, obfuscated ? "P0" : "loadMedia", 12, m -> {
            Class<?>[] parameters = m.getParameterTypes();
            return parameters[0] == long.class && parameters[5] == long.class && parameters[11] == boolean.class;
        });
        addObserver = bridge.findMethod(notifications, obfuscated ? "a" : "addObserver", 2, m ->
                m.getParameterTypes()[0].isInterface() && m.getParameterTypes()[1] == int.class);
        removeObserver = bridge.findMethod(notifications, obfuscated ? "p" : "removeObserver", 2, m ->
                addObserver != null && m.getParameterTypes()[0] == addObserver.getParameterTypes()[0]
                        && m.getParameterTypes()[1] == int.class);
        Object value = bridge.getStaticField(notifications, obfuscated ? "V" : "mediaDidLoad");
        event = value instanceof Number ? ((Number) value).intValue() : -1;
    }

    boolean available() {
        return load != null && addObserver != null && removeObserver != null && event >= 0;
    }

    boolean load(int account, long dialog, long topic, int offset, Result onResult) {
        if (!available() || cancel != null || dialog == 0) return false;
        Object data = bridge.invokeStatic(mediaData, accountAccessor, account);
        Object center = bridge.invokeStatic(notifications, notificationsAccessor, account);
        Object guid = bridge.invokeStatic(connections, "generateClassGuid");
        if (data == null || center == null || !(guid instanceof Integer)) return false;

        Class<?> delegate = addObserver.getParameterTypes()[0];
        Object observer = Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "Shared video observer";
                default:
                    if (args != null && args.length == 3 && Integer.valueOf(event).equals(args[0])
                            && Integer.valueOf(account).equals(args[1]) && args[2] instanceof Object[]) {
                        Object[] payload = (Object[]) args[2];
                        if (payload.length >= 6 && Long.valueOf(dialog).equals(payload[0]) && guid.equals(payload[3])
                                && payload[2] instanceof List && cancel != null) {
                            clear();
                            onResult.loaded((List<?>) payload[2], Boolean.TRUE.equals(payload[5]));
                        }
                    }
                    return null;
            }
        });
        Runnable timeout = () -> {
            clear();
            onResult.loaded(null, false);
        };
        cancel = () -> {
            main.removeCallbacks(timeout);
            bridge.invoke(center, removeObserver, observer, event);
            Object manager = bridge.invokeStatic(connections, "getInstance", account);
            bridge.invokeNamed(manager, "cancelRequestsForGuid", guid);
        };
        bridge.invoke(center, addObserver, observer, event);
        main.postDelayed(timeout, 12_000L);
        bridge.invoke(data, load, dialog, PAGE_SIZE, offset, 0, VIDEOS_ONLY, topic, 1, guid, 0, null, null, true);
        return true;
    }

    void clear() {
        Runnable pending = cancel;
        cancel = null;
        if (pending != null) pending.run();
    }
}

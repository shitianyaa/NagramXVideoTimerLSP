package android.os;

/** JVM fixture: no Android message loop is available in desktop tests. */
public final class Looper {
    public static Looper getMainLooper() { return null; }
    public static Looper myLooper() { return null; }
}

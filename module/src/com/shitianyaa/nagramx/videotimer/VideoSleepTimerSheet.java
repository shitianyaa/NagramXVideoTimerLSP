package com.shitianyaa.nagramx.videotimer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;

/**
 * 定时关闭面板：使用宿主 BottomSheet 与 NumberPicker。
 */
final class VideoSleepTimerSheet {

    private static final int[] PRESET_MINUTES = new int[]{15, 30, 45, 60, 90};
    private static final int DEFAULT_MINUTES = 30;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    public interface OnPickListener {
        void onPick(int mode, int minutes);
    }

    public interface OnCancelListener {
        void onCancel();
    }

    public interface FormatterFunction {
        String format(int value);
    }

    private final HostProfile host;
    private final HostTheme theme;
    private final HostStrings strings;
    private final HostBridge.Logger logger;
    private final HostBridge bridge;
    private final Class<?> bottomSheetClass;
    private final Class<?> numberPickerClass;
    private final boolean audio;
    private WeakReference<Dialog> currentSheet = new WeakReference<>(null);

    VideoSleepTimerSheet(HostProfile host, HostTheme theme, HostStrings strings, HostBridge.Logger logger) {
        this(host, theme, strings, logger, false);
    }

    VideoSleepTimerSheet(HostProfile host, HostTheme theme, HostStrings strings, HostBridge.Logger logger, boolean audio) {
        this.host = host;
        this.theme = theme;
        this.strings = strings;
        this.logger = logger != null ? logger : (msg, t) -> {};
        this.bridge = host.bridge;
        this.bottomSheetClass = bridge.loadClass("org.telegram.ui.ActionBar.BottomSheet");
        this.numberPickerClass = bridge.loadClass("org.telegram.ui.Components.NumberPicker");
        this.audio = audio;
    }

    void dismiss() {
        Dialog dialog = currentSheet.get();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        currentSheet.clear();
    }

    boolean isAvailable() {
        return bottomSheetClass != null;
    }

    boolean show(Activity activity, TimerState state, OnPickListener onPick, OnCancelListener onCancel) {
        if (bottomSheetClass == null) return false;
        try {
            Object sheetObj = bridge.newInstance(bottomSheetClass, (Context) activity, false, null);
            if (sheetObj == null) {
                sheetObj = bridge.newInstance(bottomSheetClass, (Context) activity, false);
            }
            if (!(sheetObj instanceof Dialog)) return false;
            Dialog sheet = (Dialog) sheetObj;

            Method setCustomView = bridge.findMethod(bottomSheetClass, "setCustomView", 1);
            if (setCustomView == null) {
                logger.log("BottomSheet.setCustomView 不可用，放弃自定义面板", null);
                return false;
            }
            View content = buildContent(activity, state, sheet, onPick, onCancel);
            bridge.invoke(sheet, setCustomView, content);
            bridge.invokeNamed(sheet, "fixNavigationBar");
            sheet.show();
            currentSheet = new WeakReference<>(sheet);
            return true;
        } catch (Throwable t) {
            logger.log("弹出定时面板失败", t);
            return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private View buildContent(Context context, TimerState state, Dialog sheet, OnPickListener onPick, OnCancelListener onCancel) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(handle(context), linear(context, 36, 4, 0, 10, 0, 0, Gravity.CENTER_HORIZONTAL));
        root.addView(title(context, strings.get(context, "VideoSleepTimer")), linear(context, MATCH, WRAP, 22, 14, 22, 0, -1));

        TextView sub = subtitle(context, state);
        if (sub != null) {
            root.addView(sub, linear(context, MATCH, WRAP, 22, 4, 22, 0, -1));
        }

        root.addView(sectionLabel(context, strings.get(context, "VideoSleepTimerQuickChoices")), linear(context, MATCH, WRAP, 22, 18, 22, 0, -1));
        root.addView(presets(context, state, sheet, onPick), linear(context, MATCH, WRAP, 0, 10, 0, 0, -1));

        if (!audio) {
            root.addView(afterCurrentRow(context, state, sheet, onPick), linear(context, MATCH, 50, 12, 6, 12, 0, -1));
        }

        root.addView(sectionLabel(context, strings.get(context, "VideoSleepTimerCustom")), linear(context, MATCH, WRAP, 22, 14, 22, 0, -1));
        root.addView(hint(context, strings.get(context, audio ? "AudioSleepTimerCustomHint" : "VideoSleepTimerCustomHint")), linear(context, MATCH, WRAP, 22, 4, 22, 0, -1));

        PickerPair picker = new PickerPair(context, state.selectedMinutes);
        picker.attach(root);

        root.addView(applyButton(context, sheet, picker, onPick), linear(context, MATCH, 48, 16, 16, 16, 0, -1));
        if (state.active) {
            root.addView(cancelButton(context, sheet, onCancel), linear(context, MATCH, 48, 16, 8, 16, 8, -1));
        } else {
            root.addView(new View(context), linear(context, MATCH, 12, 0, 0, 0, 0, -1));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(root, new FrameLayout.LayoutParams(MATCH, WRAP));
        return scroll;
    }

    private View handle(Context context) {
        View view = new View(context);
        view.setBackground(theme.roundRect(context, 2f, theme.alpha(theme.activeColor("sheet_scrollUp"), 0x66)));
        return view;
    }

    private TextView title(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f);
        tv.setTypeface(theme.bold());
        tv.setTextColor(theme.activeColor("dialogTextBlack"));
        tv.setText(text);
        return tv;
    }

    private TextView subtitle(Context context, TimerState state) {
        if (!state.active) return null;
        String text;
        if (state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT) {
            text = strings.get(context, "VideoSleepTimerAfterCurrentSet");
        } else {
            text = strings.format(context, "VideoSleepTimerSetFor", strings.duration(context, Math.max(1, state.remainingMinutes)));
        }
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        tv.setTextColor(theme.activeColor("dialogTextGray2"));
        tv.setText(text);
        return tv;
    }

    private TextView sectionLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        tv.setTypeface(theme.bold());
        tv.setTextColor(theme.activeColor("dialogTextBlue2"));
        tv.setText(text);
        return tv;
    }

    private TextView hint(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        tv.setTextColor(theme.activeColor("dialogTextGray2"));
        tv.setText(text);
        return tv;
    }

    private View presets(Context context, TimerState state, Dialog sheet, OnPickListener onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0);

        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            final int minutes = PRESET_MINUTES[i];
            boolean selected = state.active &&
                    state.mode == VideoBackgroundSession.MODE_DURATION &&
                    state.remainingMinutes == minutes;

            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            chip.setTypeface(theme.bold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0);
            chip.setText(strings.duration(context, minutes));

            if (selected) {
                chip.setTextColor(theme.activeColor("featuredStickers_buttonText"));
                chip.setBackground(theme.rippleRect(context, 18f, theme.activeColor("featuredStickers_addButton"), theme.alpha(HostTheme.WHITE, 0x30)));
            } else {
                int textColor = theme.activeColor("dialogTextBlack");
                chip.setTextColor(textColor);
                chip.setBackground(theme.rippleRect(context, 18f, theme.alpha(textColor, 0x14), theme.activeColor("listSelector")));
            }

            chip.setOnClickListener(v -> {
                onPick.onPick(VideoBackgroundSession.MODE_DURATION, minutes);
                sheet.dismiss();
            });

            row.addView(chip, linear(context, WRAP, 36, i == 0 ? 0 : 8, 0, 0, 0, -1));
        }

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(row, new FrameLayout.LayoutParams(WRAP, WRAP));
        return scroll;
    }

    private View afterCurrentRow(Context context, TimerState state, Dialog sheet, OnPickListener onPick) {
        boolean selected = state.active && state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT;
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(theme.dp(context, 10), 0, theme.dp(context, 10), 0);
        tv.setText(strings.get(context, "VideoSleepTimerAfterCurrent"));
        tv.setTextColor(theme.activeColor(selected ? "dialogTextBlue2" : "dialogTextBlack"));
        if (selected) tv.setTypeface(theme.bold());
        tv.setBackground(theme.rippleTransparent(context, 10f, theme.activeColor("listSelector")));
        tv.setOnClickListener(v -> {
            onPick.onPick(VideoBackgroundSession.MODE_AFTER_CURRENT, 0);
            sheet.dismiss();
        });
        return tv;
    }

    private View applyButton(Context context, Dialog sheet, PickerPair picker, OnPickListener onPick) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setTypeface(theme.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(theme.activeColor("featuredStickers_buttonText"));
        tv.setText(strings.get(context, "VideoSleepTimerApply"));
        tv.setBackground(theme.rippleRect(context, 8f, theme.activeColor("featuredStickers_addButton"), theme.alpha(HostTheme.WHITE, 0x30)));
        tv.setOnClickListener(v -> {
            int minutes = Math.max(1, picker.totalMinutes());
            onPick.onPick(VideoBackgroundSession.MODE_DURATION, minutes);
            sheet.dismiss();
        });
        return tv;
    }

    private View cancelButton(Context context, Dialog sheet, OnCancelListener onCancel) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(theme.activeColor("text_RedRegular"));
        tv.setText(strings.get(context, "VideoSleepTimerCancel"));
        tv.setBackground(theme.rippleTransparent(context, 8f, theme.activeColor("listSelector")));
        tv.setOnClickListener(v -> {
            onCancel.onCancel();
            sheet.dismiss();
        });
        return tv;
    }

    private final class PickerPair {
        private final View hours;
        private final View minutes;

        PickerPair(Context context, int initialMinutes) {
            int h = Math.max(0, Math.min(23, initialMinutes / 60));
            int m = Math.max(0, Math.min(59, initialMinutes % 60));
            hours = createPicker(context, 23, h, val -> strings.format(context, "VideoSleepTimerDurationHours", val));
            minutes = createPicker(context, 59, m, val -> strings.minutes(context, val));
            if (isUsable()) {
                syncMinuteRange(bridge, hours, minutes);
                installHourListener();
            }
        }

        boolean isUsable() {
            return hours != null && minutes != null;
        }

        void attach(LinearLayout root) {
            Context context = root.getContext();
            if (!isUsable()) {
                root.addView(hint(context, strings.get(context, "VideoSleepTimerQuickChoices")),
                        linear(context, MATCH, WRAP, 22, 8, 22, 0, -1));
                return;
            }
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.addView(hours, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(minutes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(row, linear(context, MATCH, 180, 12, 8, 12, 0, -1));
        }

        int totalMinutes() {
            if (!isUsable()) return DEFAULT_MINUTES;
            Object hVal = bridge.invokeNamed(hours, "getValue");
            Object mVal = bridge.invokeNamed(minutes, "getValue");
            int h = (hVal instanceof Number) ? ((Number) hVal).intValue() : 0;
            int m = (mVal instanceof Number) ? ((Number) mVal).intValue() : 0;
            return h * 60 + m;
        }

        private void installHourListener() {
            Method setter = bridge.findMethod(numberPickerClass, "setOnValueChangedListener", 1,
                    method -> method.getParameterTypes()[0].isInterface());
            if (setter == null) {
                logger.log("定时面板小时联动不可用：缺少 NumberPicker 值变更监听接口", null);
                return;
            }
            Class<?> listenerType = setter.getParameterTypes()[0];
            try {
                Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                        (proxy, method, args) -> {
                            if (method.getReturnType() == void.class && method.getParameterCount() == 3
                                    && method.getParameterTypes()[1] == int.class
                                    && method.getParameterTypes()[2] == int.class) {
                                syncMinuteRange(bridge, hours, minutes);
                                return null;
                            }
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            if ("toString".equals(method.getName())) return "SleepTimerHourListener";
                            return null;
                        });
                bridge.invoke(hours, setter, listener);
            } catch (IllegalArgumentException | SecurityException failure) {
                logger.log("创建定时面板小时联动监听失败", failure);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private View createPicker(Context context, int max, int value, FormatterFunction format) {
            if (numberPickerClass == null) return null;
            Object pickerObj = bridge.newInstance(numberPickerClass, context, 20, null);
            if (pickerObj == null) {
                pickerObj = bridge.newInstance(numberPickerClass, context, 20);
            }
            if (pickerObj == null) {
                pickerObj = bridge.newInstance(numberPickerClass, context);
            }
            if (!(pickerObj instanceof View)) return null;
            View picker = (View) pickerObj;

            bridge.invokeNamed(picker, "setItemCount", 5);
            bridge.invokeNamed(picker, "setTextColor", theme.activeColor("dialogTextBlack"));
            bridge.invokeNamed(picker, "setSelectorColor", theme.activeColor("dialogButtonSelector"));
            bridge.invokeNamed(picker, "setMinValue", 0);
            bridge.invokeNamed(picker, "setMaxValue", max);
            bridge.invokeNamed(picker, "setAllItemsCount", max + 1);
            bridge.invokeNamed(picker, "setWrapSelectorWheel", true);
            installFormatter(picker, format);
            bridge.invokeNamed(picker, "setValue", value);

            picker.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (view.getParent() != null) {
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                return false;
            });
            return picker;
        }

        private void installFormatter(View picker, FormatterFunction format) {
            Method setter = bridge.findMethod(numberPickerClass, "setFormatter", 1,
                    method -> method.getParameterTypes()[0].isInterface());
            if (setter == null) {
                return;
            }
            Class<?> formatterClass = setter.getParameterTypes()[0];
            try {
                Object proxy = Proxy.newProxyInstance(
                        formatterClass.getClassLoader(),
                        new Class<?>[]{formatterClass},
                        (proxyObj, method, args) -> {
                            if (method.getReturnType() == String.class && method.getParameterCount() == 1
                                    && method.getParameterTypes()[0] == int.class) {
                                return format.format((Integer) args[0]);
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxyObj);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxyObj == args[0];
                            }
                            if ("toString".equals(method.getName())) {
                                return "NumberPickerFormatter";
                            }
                            return null;
                        }
                );
                bridge.invoke(picker, setter, proxy);
            } catch (IllegalArgumentException | SecurityException t) {
                logger.log("创建 NumberPicker.Formatter 代理失败", t);
            }
        }
    }

    static void syncMinuteRange(HostBridge bridge, Object hours, Object minutes) {
        Object value = bridge.invokeNamed(hours, "getValue");
        int minimum = value instanceof Number && ((Number) value).intValue() > 0 ? 0 : 1;
        Object current = bridge.invokeNamed(minutes, "getValue");
        bridge.invokeNamed(minutes, "setMinValue", minimum);
        bridge.invokeNamed(minutes, "setAllItemsCount", 60 - minimum);
        if (current instanceof Number && ((Number) current).intValue() < minimum) {
            bridge.invokeNamed(minutes, "setValue", minimum);
        }
    }

    private LinearLayout.LayoutParams linear(Context context, int width, int height, int left, int top, int right, int bottom, int gravity) {
        int w = width > 0 ? theme.dp(context, width) : width;
        int h = height > 0 ? theme.dp(context, height) : height;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w, h);
        params.setMargins(theme.dp(context, left), theme.dp(context, top), theme.dp(context, right), theme.dp(context, bottom));
        if (gravity != -1) params.gravity = gravity;
        return params;
    }
}

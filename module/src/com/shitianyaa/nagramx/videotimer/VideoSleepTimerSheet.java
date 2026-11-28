package com.shitianyaa.nagramx.videotimer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
        int sheetBg = theme.activeColor("dialogBackground");
        int accent = theme.accentColor(sheetBg);
        int buttonFill = theme.primaryButtonFill(sheetBg);
        int buttonText = theme.primaryButtonText(buttonFill);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(handle(context), linear(context, 36, 4, 0, 10, 0, 0, Gravity.CENTER_HORIZONTAL));
        root.addView(title(context, strings.get(context, "VideoSleepTimer")), linear(context, MATCH, WRAP, 22, 14, 22, 0, -1));

        TextView sub = subtitle(context, state);
        if (sub != null) {
            root.addView(sub, linear(context, MATCH, WRAP, 22, 4, 22, 0, -1));
        }

        root.addView(sectionLabel(context, strings.get(context, "VideoSleepTimerQuickChoices"), accent),
                linear(context, MATCH, WRAP, 22, 18, 22, 0, -1));
        root.addView(presets(context, state, sheet, accent, onPick), linear(context, MATCH, WRAP, 0, 10, 0, 0, -1));

        if (!audio) {
            root.addView(afterCurrentRow(context, state, sheet, accent, onPick),
                    linear(context, MATCH, 50, 12, 8, 12, 0, -1));
        }

        root.addView(sectionLabel(context, strings.get(context, "VideoSleepTimerCustom"), accent),
                linear(context, MATCH, WRAP, 22, 14, 22, 0, -1));
        root.addView(hint(context, strings.get(context, audio ? "AudioSleepTimerCustomHint" : "VideoSleepTimerCustomHint")),
                linear(context, MATCH, WRAP, 22, 4, 22, 0, -1));

        TextView apply = applyButtonView(context, buttonFill, buttonText);
        PickerPair picker = new PickerPair(context, state.selectedMinutes, accent);
        picker.attach(root);
        updateApplyLabel(apply, context, picker);
        picker.onPreview = () -> updateApplyLabel(apply, context, picker);
        apply.setOnClickListener(v -> {
            HostTheme.tapHaptic(v);
            int minutes = Math.max(1, picker.totalMinutes());
            onPick.onPick(VideoBackgroundSession.MODE_DURATION, minutes);
            sheet.dismiss();
        });

        root.addView(apply, linear(context, MATCH, 48, 16, 16, 16, 0, -1));
        if (state.active) {
            root.addView(cancelButton(context, sheet, onCancel), linear(context, MATCH, 48, 16, 8, 16, 12, -1));
        } else {
            root.addView(new View(context), linear(context, MATCH, 14, 0, 0, 0, 0, -1));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(root, new FrameLayout.LayoutParams(MATCH, WRAP));
        return scroll;
    }

    private View handle(Context context) {
        View view = new View(context);
        view.setBackground(theme.roundRect(context, 2f, theme.alpha(theme.activeColor("dialogTextGray2"), 0x70)));
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

    private TextView sectionLabel(Context context, String text, int accent) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        tv.setTypeface(theme.bold());
        tv.setTextColor(accent);
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

    private View presets(Context context, TimerState state, Dialog sheet, int accent, OnPickListener onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0);

        int textColor = theme.activeColor("dialogTextBlack");
        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            final int minutes = PRESET_MINUTES[i];
            boolean selected = state.active &&
                    state.mode == VideoBackgroundSession.MODE_DURATION &&
                    state.remainingMinutes == minutes;

            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            chip.setTypeface(theme.bold());
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(theme.dp(context, 11), 0, theme.dp(context, 11), 0);
            chip.setText(strings.presetDuration(context, minutes));

            if (selected) {
                chip.setTextColor(accent);
                chip.setBackground(theme.rippleRoundBorderRect(
                        context, 18f, theme.alpha(accent, 0x29), 1, theme.alpha(accent, 0x55), theme.alpha(accent, 0x40)));
            } else {
                chip.setTextColor(textColor);
                chip.setBackground(theme.rippleRect(context, 18f, theme.alpha(textColor, 0x12), theme.activeColor("listSelector")));
            }

            chip.setOnClickListener(v -> {
                HostTheme.tickHaptic(v);
                onPick.onPick(VideoBackgroundSession.MODE_DURATION, minutes);
                sheet.dismiss();
            });

            row.addView(chip, linear(context, WRAP, 36, i == 0 ? 0 : 6, 0, 0, 0, -1));
        }

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(theme.dp(context, 24));
        scroll.setClipToPadding(false);
        scroll.addView(row, new FrameLayout.LayoutParams(WRAP, WRAP));
        return scroll;
    }

    private View afterCurrentRow(Context context, TimerState state, Dialog sheet, int accent, OnPickListener onPick) {
        boolean selected = state.active && state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(theme.dp(context, 10), 0, theme.dp(context, 10), 0);
        row.setBackground(theme.rippleTransparent(context, 10f, theme.activeColor("listSelector")));

        int iconId = context.getResources().getIdentifier("photo_timer", "drawable", context.getPackageName());
        if (iconId == 0) {
            iconId = context.getResources().getIdentifier("msg_autodelete", "drawable", context.getPackageName());
        }
        if (iconId != 0) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(iconId);
            icon.setColorFilter(selected ? accent : theme.activeColor("dialogTextGray2"), PorterDuff.Mode.SRC_IN);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            row.addView(icon, linear(context, 22, 22, 0, 0, 12, 0, Gravity.CENTER_VERTICAL));
        }

        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setText(strings.get(context, "VideoSleepTimerAfterCurrent"));
        tv.setTextColor(selected ? accent : theme.activeColor("dialogTextBlack"));
        if (selected) tv.setTypeface(theme.bold());
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        row.addView(tv, labelParams);

        if (selected) {
            View check = new View(context);
            check.setBackground(new CheckmarkDrawable(accent, theme.dp(context, 2)));
            row.addView(check, linear(context, 18, 18, 8, 0, 2, 0, Gravity.CENTER_VERTICAL));
        }

        row.setOnClickListener(v -> {
            HostTheme.tickHaptic(v);
            onPick.onPick(VideoBackgroundSession.MODE_AFTER_CURRENT, 0);
            sheet.dismiss();
        });
        return row;
    }

    private TextView applyButtonView(Context context, int fillColor, int textColor) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setTypeface(theme.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0);
        tv.setTextColor(textColor);
        tv.setBackground(theme.rippleRect(context, 10f, fillColor, theme.alpha(textColor, 0x2E)));
        return tv;
    }

    private void updateApplyLabel(TextView button, Context context, PickerPair picker) {
        int minutes = Math.max(1, picker.totalMinutes());
        button.setText(strings.get(context, "VideoSleepTimerApply") + " · " + strings.duration(context, minutes));
    }

    private View cancelButton(Context context, Dialog sheet, OnCancelListener onCancel) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(theme.activeColor("text_RedRegular"));
        tv.setText(strings.get(context, "VideoSleepTimerCancel"));
        tv.setBackground(theme.rippleTransparent(context, 10f, theme.activeColor("listSelector")));
        tv.setOnClickListener(v -> {
            HostTheme.tapHaptic(v);
            onCancel.onCancel();
            sheet.dismiss();
        });
        return tv;
    }

    private final class PickerPair {
        private final View hours;
        private final View minutes;
        private final int wheelHeightDp;
        Runnable onPreview;

        PickerPair(Context context, int initialMinutes, int accent) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float density = Math.max(1f, metrics.density);
            int screenH = Math.round(metrics.heightPixels / density);
            boolean landscape = metrics.widthPixels > metrics.heightPixels;
            wheelHeightDp = pickerHeightDp(screenH, landscape);
            int itemCount = pickerItemCount(screenH, landscape);

            int h = Math.max(0, Math.min(23, initialMinutes / 60));
            int m = Math.max(0, Math.min(59, initialMinutes % 60));
            hours = createPicker(context, 23, h, accent, itemCount, val -> strings.format(context, "VideoSleepTimerDurationHours", val));
            minutes = createPicker(context, 59, m, accent, itemCount, val -> strings.minutes(context, val));
            if (isUsable()) {
                syncMinuteRange(bridge, hours, minutes);
                installChangeListeners();
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
            root.addView(row, linear(context, MATCH, wheelHeightDp, 12, 8, 12, 0, -1));
        }

        int totalMinutes() {
            if (!isUsable()) return DEFAULT_MINUTES;
            Object hVal = bridge.invokeNamed(hours, "getValue");
            Object mVal = bridge.invokeNamed(minutes, "getValue");
            int h = (hVal instanceof Number) ? ((Number) hVal).intValue() : 0;
            int m = (mVal instanceof Number) ? ((Number) mVal).intValue() : 0;
            return h * 60 + m;
        }

        private void installChangeListeners() {
            Method setter = bridge.findMethod(numberPickerClass, "setOnValueChangedListener", 1,
                    method -> method.getParameterTypes()[0].isInterface());
            if (setter == null) {
                logger.log("定时面板小时联动不可用：缺少 NumberPicker 值变更监听接口", null);
                return;
            }
            Class<?> listenerType = setter.getParameterTypes()[0];
            installListener(hours, setter, listenerType, true);
            installListener(minutes, setter, listenerType, false);
        }

        private void installListener(View picker, Method setter, Class<?> listenerType, boolean isHours) {
            try {
                Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                        (proxy, method, args) -> {
                            if (method.getReturnType() == void.class && method.getParameterCount() == 3
                                    && method.getParameterTypes()[1] == int.class
                                    && method.getParameterTypes()[2] == int.class) {
                                if (isHours) {
                                    syncMinuteRange(bridge, hours, minutes);
                                }
                                HostTheme.tickHaptic(picker);
                                if (onPreview != null) {
                                    onPreview.run();
                                }
                                return null;
                            }
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            if ("toString".equals(method.getName())) return "SleepTimerPickerListener";
                            return null;
                        });
                bridge.invoke(picker, setter, listener);
            } catch (IllegalArgumentException | SecurityException failure) {
                logger.log("创建定时面板滚轮监听失败", failure);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private View createPicker(Context context, int max, int value, int accent, int itemCount, FormatterFunction format) {
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

            bridge.invokeNamed(picker, "setItemCount", itemCount);
            bridge.invokeNamed(picker, "setTextColor", theme.activeColor("dialogTextBlack"));
            bridge.invokeNamed(picker, "setSelectorColor", theme.alpha(accent, 0x48));
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

    static int pickerItemCount(int screenHeightDp, boolean landscape) {
        if (landscape && screenHeightDp < 440) return 3;
        if (landscape || screenHeightDp < 760) return 5;
        return 7;
    }

    static int pickerHeightDp(int screenHeightDp, boolean landscape) {
        if (landscape) return screenHeightDp < 440 ? 126 : 180;
        return screenHeightDp >= 760 ? 252 : 180;
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

    private static final class CheckmarkDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CheckmarkDrawable(int color, int strokePx) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, strokePx));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float w = bounds.width();
            float h = bounds.height();
            path.reset();
            path.moveTo(bounds.left + w * 0.20f, bounds.top + h * 0.52f);
            path.lineTo(bounds.left + w * 0.42f, bounds.top + h * 0.74f);
            path.lineTo(bounds.left + w * 0.82f, bounds.top + h * 0.28f);
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override @Deprecated public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

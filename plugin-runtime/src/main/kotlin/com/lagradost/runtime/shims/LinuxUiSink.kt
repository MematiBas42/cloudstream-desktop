package com.lagradost.runtime.shims

import com.lagradost.common.logging.AppLogger

/**
 * Universal Dead UI neutralization sink for Linux desktop.
 *
 * Intercepts calls from plugins to Android UI classes (`android.view.*` and `android.widget.*`),
 * silently neutralizing layout and widget interactions that are irrelevant or non-functional
 * in headless/desktop scraping contexts.
 */
object LinuxUiSink {

    // --- Universal catch-all sinks (vararg) ---

    @JvmStatic
    fun sink(vararg args: Any?): Any? {
        AppLogger.d("LinuxUiSink", "Neutralized UI call: sink(args=${args.size})")
        return null
    }

    @JvmStatic
    fun sinkVoid(vararg args: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized UI call: sinkVoid(args=${args.size})")
    }

    @JvmStatic
    fun sinkObject(vararg args: Any?): Any? = null

    @JvmStatic
    fun sinkBoolean(vararg args: Any?): Boolean = false

    @JvmStatic
    fun sinkInt(vararg args: Any?): Int = 0

    @JvmStatic
    fun sinkLong(vararg args: Any?): Long = 0L

    @JvmStatic
    fun sinkFloat(vararg args: Any?): Float = 0f

    @JvmStatic
    fun sinkDouble(vararg args: Any?): Double = 0.0

    // --- Generic fallback sinks with specific argument counts to avoid vararg array allocation ---

    @JvmStatic
    fun sinkVoid() {}

    @JvmStatic
    fun sinkVoid(a: Any?) {}

    @JvmStatic
    fun sinkVoid(a: Any?, b: Any?) {}

    @JvmStatic
    fun sinkVoid(a: Any?, b: Any?, c: Any?) {}

    @JvmStatic
    fun sinkVoid(a: Any?, b: Any?, c: Any?, d: Any?) {}

    @JvmStatic
    fun sinkVoid(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?) {}

    @JvmStatic
    fun sinkObject(): Any? = null

    @JvmStatic
    fun sinkObject(a: Any?): Any? = null

    @JvmStatic
    fun sinkObject(a: Any?, b: Any?): Any? = null

    @JvmStatic
    fun sinkObject(a: Any?, b: Any?, c: Any?): Any? = null

    @JvmStatic
    fun sinkObject(a: Any?, b: Any?, c: Any?, d: Any?): Any? = null

    @JvmStatic
    fun sinkObject(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?): Any? = null

    @JvmStatic
    fun sinkBoolean(): Boolean = false

    @JvmStatic
    fun sinkBoolean(a: Any?): Boolean = false

    @JvmStatic
    fun sinkBoolean(a: Any?, b: Any?): Boolean = false

    @JvmStatic
    fun sinkBoolean(a: Any?, b: Any?, c: Any?): Boolean = false

    @JvmStatic
    fun sinkInt(): Int = 0

    @JvmStatic
    fun sinkInt(a: Any?): Int = 0

    @JvmStatic
    fun sinkInt(a: Any?, b: Any?): Int = 0

    @JvmStatic
    fun sinkInt(a: Any?, b: Any?, c: Any?): Int = 0

    // --- Zero-arg methods (static calls) ---

    @JvmStatic
    fun generateViewId(): Int = 1

    // --- 1-arg methods (receiver only) ---

    @JvmStatic
    fun getId(view: Any?): Int = 0

    @JvmStatic
    fun getVisibility(view: Any?): Int = 0

    @JvmStatic
    fun getLayoutParams(view: Any?): Any? = null

    @JvmStatic
    fun getPaddingLeft(view: Any?): Int = 0

    @JvmStatic
    fun getPaddingTop(view: Any?): Int = 0

    @JvmStatic
    fun getPaddingRight(view: Any?): Int = 0

    @JvmStatic
    fun getPaddingBottom(view: Any?): Int = 0

    @JvmStatic
    fun isEnabled(view: Any?): Boolean = true

    @JvmStatic
    fun isFocusable(view: Any?): Boolean = true

    @JvmStatic
    fun isClickable(view: Any?): Boolean = true

    @JvmStatic
    fun requestFocus(view: Any?): Boolean = true

    @JvmStatic
    fun clearFocus(view: Any?) {}

    @JvmStatic
    fun getTag(view: Any?): Any? = null

    @JvmStatic
    fun getContext(view: Any?): Any? = null

    @JvmStatic
    fun getResources(view: Any?): Any? = null

    @JvmStatic
    fun getBackground(view: Any?): Any? = null

    @JvmStatic
    fun invalidate(view: Any?) {}

    @JvmStatic
    fun postInvalidate(view: Any?) {}

    @JvmStatic
    fun requestLayout(view: Any?) {}

    @JvmStatic
    fun bringToFront(view: Any?) {}

    @JvmStatic
    fun getText(view: Any?): Any? = ""

    @JvmStatic
    fun getGravity(view: Any?): Int = 0

    @JvmStatic
    fun getTypeface(view: Any?): Any? = null

    @JvmStatic
    fun getHint(view: Any?): Any? = null

    @JvmStatic
    fun isChecked(view: Any?): Boolean = false

    @JvmStatic
    fun toggle(view: Any?) {}

    @JvmStatic
    fun removeAllViews(view: Any?) {}

    @JvmStatic
    fun getChildCount(view: Any?): Int = 0

    @JvmStatic
    fun getOrientation(view: Any?): Int = 0

    @JvmStatic
    fun getAlpha(view: Any?): Float = 1.0f

    @JvmStatic
    fun getTranslationX(view: Any?): Float = 0.0f

    @JvmStatic
    fun getTranslationY(view: Any?): Float = 0.0f

    @JvmStatic
    fun getWidth(view: Any?): Int = 0

    @JvmStatic
    fun getHeight(view: Any?): Int = 0

    @JvmStatic
    fun getMeasuredWidth(view: Any?): Int = 0

    @JvmStatic
    fun getMeasuredHeight(view: Any?): Int = 0

    @JvmStatic
    fun animate(view: Any?): Any? = null

    @JvmStatic
    fun clearAnimation(view: Any?) {}

    @JvmStatic
    fun performClick(view: Any?): Boolean = true

    @JvmStatic
    fun selectAll(view: Any?) {}

    @JvmStatic
    fun show(dialogOrToast: Any?) {}

    @JvmStatic
    fun dismiss(dialog: Any?) {}

    @JvmStatic
    fun cancel(dialog: Any?) {}

    // --- 2-arg methods (receiver + 1 arg) ---

    @JvmStatic
    fun setVisibility(view: Any?, visibility: Int) {
        AppLogger.d("LinuxUiSink", "Neutralized setVisibility($visibility)")
    }

    @JvmStatic
    fun setId(view: Any?, id: Int) {}

    @JvmStatic
    fun setOnClickListener(view: Any?, listener: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized setOnClickListener")
    }

    @JvmStatic
    fun setOnFocusChangeListener(view: Any?, listener: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized setOnFocusChangeListener")
    }

    @JvmStatic
    fun setOnLongClickListener(view: Any?, listener: Any?): Boolean = true

    @JvmStatic
    fun setOnTouchListener(view: Any?, listener: Any?): Boolean = true

    @JvmStatic
    fun setOnKeyListener(view: Any?, listener: Any?): Boolean = true

    @JvmStatic
    fun setLayoutParams(view: Any?, params: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized setLayoutParams")
    }

    @JvmStatic
    fun setBackgroundColor(view: Any?, color: Int) {
        AppLogger.d("LinuxUiSink", "Neutralized setBackgroundColor($color)")
    }

    @JvmStatic
    fun setBackground(view: Any?, background: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized setBackground")
    }

    @JvmStatic
    fun setBackgroundResource(view: Any?, resId: Int) {}

    @JvmStatic
    fun setBackgroundDrawable(view: Any?, background: Any?) {}

    @JvmStatic
    fun setEnabled(view: Any?, enabled: Boolean) {
        AppLogger.d("LinuxUiSink", "Neutralized setEnabled($enabled)")
    }

    @JvmStatic
    fun setFocusable(view: Any?, focusable: Boolean) {
        AppLogger.d("LinuxUiSink", "Neutralized setFocusable($focusable)")
    }

    @JvmStatic
    fun setClickable(view: Any?, clickable: Boolean) {
        AppLogger.d("LinuxUiSink", "Neutralized setClickable($clickable)")
    }

    @JvmStatic
    fun setTag(view: Any?, tag: Any?) {}

    @JvmStatic
    fun findViewWithTag(view: Any?, tag: Any?): Any? = null

    @JvmStatic
    fun findViewById(view: Any?, id: Int): Any? = null

    @JvmStatic
    fun post(view: Any?, action: Any?): Boolean = true

    @JvmStatic
    fun removeCallbacks(view: Any?, action: Any?): Boolean = true

    @JvmStatic
    fun setText(view: Any?, text: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized setText($text)")
    }

    @JvmStatic
    fun setTextColor(view: Any?, color: Int) {}

    @JvmStatic
    fun setTextSize(view: Any?, size: Float) {}

    @JvmStatic
    fun setGravity(view: Any?, gravity: Int) {}

    @JvmStatic
    fun setTypeface(view: Any?, typeface: Any?) {}

    @JvmStatic
    fun setHint(view: Any?, hint: Any?) {}

    @JvmStatic
    fun setAllCaps(view: Any?, allCaps: Boolean) {}

    @JvmStatic
    fun setMaxLines(view: Any?, maxLines: Int) {}

    @JvmStatic
    fun setMinLines(view: Any?, minLines: Int) {}

    @JvmStatic
    fun setLines(view: Any?, lines: Int) {}

    @JvmStatic
    fun setImageResource(view: Any?, resId: Int) {}

    @JvmStatic
    fun setImageDrawable(view: Any?, drawable: Any?) {}

    @JvmStatic
    fun setImageBitmap(view: Any?, bitmap: Any?) {}

    @JvmStatic
    fun setImageURI(view: Any?, uri: Any?) {}

    @JvmStatic
    fun setScaleType(view: Any?, scaleType: Any?) {}

    @JvmStatic
    fun setChecked(view: Any?, checked: Boolean) {}

    @JvmStatic
    fun setOnCheckedChangeListener(view: Any?, listener: Any?) {}

    @JvmStatic
    fun addView(view: Any?, child: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized addView")
    }

    @JvmStatic
    fun removeView(view: Any?, child: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized removeView")
    }

    @JvmStatic
    fun removeViewAt(view: Any?, index: Int) {}

    @JvmStatic
    fun getChildAt(view: Any?, index: Int): Any? = null

    @JvmStatic
    fun setOrientation(view: Any?, orientation: Int) {}

    @JvmStatic
    fun from(context: Any?): Any? = null

    @JvmStatic
    fun setAlpha(view: Any?, alpha: Float) {}

    @JvmStatic
    fun setTranslationX(view: Any?, translationX: Float) {}

    @JvmStatic
    fun setTranslationY(view: Any?, translationY: Float) {}

    @JvmStatic
    fun startAnimation(view: Any?, animation: Any?) {}

    @JvmStatic
    fun setWillNotDraw(view: Any?, willNotDraw: Boolean) {}

    @JvmStatic
    fun setError(view: Any?, error: Any?) {}

    @JvmStatic
    fun setFilters(view: Any?, filters: Any?) {}

    @JvmStatic
    fun setSelection(view: Any?, index: Int) {}

    @JvmStatic
    fun addTextChangedListener(view: Any?, watcher: Any?) {}

    @JvmStatic
    fun removeTextChangedListener(view: Any?, watcher: Any?) {}

    // --- 3-arg methods (receiver + 2 args) ---

    @JvmStatic
    fun setTag(view: Any?, key: Int, tag: Any?) {}

    @JvmStatic
    fun postDelayed(view: Any?, action: Any?, delayMillis: Long): Boolean = true

    @JvmStatic
    fun setTextSize(view: Any?, unit: Int, size: Float) {}

    @JvmStatic
    fun setTypeface(view: Any?, typeface: Any?, style: Int) {}

    @JvmStatic
    fun setLineSpacing(view: Any?, add: Float, mult: Float) {}

    @JvmStatic
    fun addView(view: Any?, child: Any?, params: Any?) {
        AppLogger.d("LinuxUiSink", "Neutralized addView with params")
    }

    @JvmStatic
    fun addView(view: Any?, child: Any?, index: Int) {}

    @JvmStatic
    fun inflate(a: Any?, b: Any?, c: Any?): Any? {
        AppLogger.d("LinuxUiSink", "Neutralized inflate")
        return null
    }

    @JvmStatic
    fun inflate(a: Any?, b: Any?): Any? {
        AppLogger.d("LinuxUiSink", "Neutralized inflate")
        return null
    }

    @JvmStatic
    fun measure(view: Any?, widthMeasureSpec: Int, heightMeasureSpec: Int) {}

    @JvmStatic
    fun setSelection(view: Any?, start: Int, stop: Int) {}

    // --- 4-arg methods (receiver + 3 args) ---

    @JvmStatic
    fun addView(view: Any?, child: Any?, width: Int, height: Int) {}

    @JvmStatic
    fun addView(view: Any?, child: Any?, index: Int, params: Any?) {}

    // --- 5-arg methods (receiver + 4 args) ---

    @JvmStatic
    fun setPadding(view: Any?, left: Int, top: Int, right: Int, bottom: Int) {
        AppLogger.d("LinuxUiSink", "Neutralized setPadding($left, $top, $right, $bottom)")
    }

    @JvmStatic
    fun layout(view: Any?, l: Int, t: Int, r: Int, b: Int) {}

    @JvmStatic
    fun setCompoundDrawablesWithIntrinsicBounds(view: Any?, left: Any?, top: Any?, right: Any?, bottom: Any?) {}

    @JvmStatic
    fun setCompoundDrawables(view: Any?, left: Any?, top: Any?, right: Any?, bottom: Any?) {}
}

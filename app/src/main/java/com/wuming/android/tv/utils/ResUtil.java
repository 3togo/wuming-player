package com.wuming.android.tv.utils;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.AnimRes;
import androidx.annotation.ArrayRes;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.DimenRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.wuming.android.tv.App;
import com.wuming.android.tv.ProductCore;

public class ResUtil {

    public static DisplayMetrics getDisplayMetrics() {
        return getDisplayMetrics(App.get());
    }

    public static DisplayMetrics getDisplayMetrics(Context context) {
        return context.getResources().getDisplayMetrics();
    }

    public static int getScreenWidth() {
        return getScreenWidth(App.get());
    }

    @SuppressWarnings("deprecation")
    public static int getScreenWidth(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return getDisplayMetrics(context).widthPixels;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect rect = windowManager.getCurrentWindowMetrics().getBounds();
            return rect.width();
        } else {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
            return displayMetrics.widthPixels;
        }
    }

    public static int getScreenHeight() {
        return getScreenHeight(App.get());
    }

    @SuppressWarnings("deprecation")
    public static int getScreenHeight(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return getDisplayMetrics(context).heightPixels;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect rect = windowManager.getCurrentWindowMetrics().getBounds();
            return rect.height();
        } else {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
            return displayMetrics.heightPixels;
        }
    }

    @SuppressWarnings("deprecation")
    public static int getScreenOrientation(Context context) {
        int orientation = context.getResources().getConfiguration().orientation;
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        if (orientation == Configuration.ORIENTATION_PORTRAIT) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) return rotation == Surface.ROTATION_90 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    public static boolean isEdge(Context context, MotionEvent e, int edge) {
        return e.getRawX() < edge || e.getRawX() > getScreenWidth(context) - edge || e.getRawY() < edge || e.getRawY() > getScreenHeight(context) - edge;
    }

    public static boolean isLand(Context context) {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public static boolean isPad() {
        return App.get().getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    public static int sp2px(int sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getDisplayMetrics());
    }

    public static int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getDisplayMetrics());
    }

    public static int getDimensionPixelSize(@DimenRes int resId) {
        return App.get().getResources().getDimensionPixelSize(resId);
    }

    public static String getString(@StringRes int resId) {
        return App.get().getResources().getString(resId);
    }

    public static String getString(@StringRes int resId, Object... formatArgs) {
        return App.get().getResources().getString(resId, formatArgs);
    }

    public static String[] getStringArray(@ArrayRes int resId) {
        return App.get().getResources().getStringArray(resId);
    }

    public static TypedArray getTypedArray(@ArrayRes int resId) {
        return App.get().getResources().obtainTypedArray(resId);
    }

    public static Drawable getDrawable(@DrawableRes int resId) {
        return ContextCompat.getDrawable(App.get(), resId);
    }

    public static int getColor(@ColorRes int resId) {
        return ContextCompat.getColor(App.get(), resId);
    }

    public static Animation getAnim(@AnimRes int resId) {
        return AnimationUtils.loadAnimation(App.get(), resId);
    }

    public static Display getDisplay(Context context) {
        return ContextCompat.getDisplayOrDefault(context);
    }

    private static final Paint textWidthPaint = new Paint();

    public static int getTextWidth(String content, int size) {
        textWidthPaint.setTextSize(sp2px(size));
        return (int) textWidthPaint.measureText(content);
    }

    // Screen diagonal-based sizing with thresholds (inspired by TVBoxOS)
    // This approach provides better sizing across different device types (phone, tablet, TV)

    /**
     * Calculate screen diagonal size in inches
     * @return Screen diagonal in inches
     */
    public static double getScreenDiagonal() {
        return getScreenDiagonal(App.get());
    }

    @SuppressWarnings("deprecation")
    public static double getScreenDiagonal(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 7.0; // Default to phone size

        DisplayMetrics dm = new DisplayMetrics();

        // Use modern API for Android R and above, deprecated API for older versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
            android.graphics.Rect bounds = windowMetrics.getBounds();

            // Get display metrics for DPI
            // context.getDisplay() can throw UnsupportedOperationException for non-visual contexts
            try {
                android.view.Display display = context.getDisplay();
                if (display != null) {
                    display.getMetrics(dm);
                } else {
                    // Fallback if display is null - use resources display metrics directly
                    dm = context.getResources().getDisplayMetrics();
                }
            } catch (UnsupportedOperationException e) {
                // Non-visual context (e.g., dialog context in some cases)
                // Use resources display metrics as fallback
                dm = context.getResources().getDisplayMetrics();
            }

            // Calculate using bounds and DPI
            double x = Math.pow(bounds.width() / dm.xdpi, 2);
            double y = Math.pow(bounds.height() / dm.ydpi, 2);
            return Math.sqrt(x + y);
        } else {
            // Use deprecated API for older Android versions
            wm.getDefaultDisplay().getMetrics(dm);
            double x = Math.pow(dm.widthPixels / dm.xdpi, 2);
            double y = Math.pow(dm.heightPixels / dm.ydpi, 2);
            return Math.sqrt(x + y);
        }
    }

    /**
     * Get site button height proportional to screen diagonal.
     * Uses SiteListConfig for consistent sizing across devices.
     */
    public static int getSiteButtonHeight() {
        double screenDiagonal = getScreenDiagonal();
        int heightDp = SiteListConfig.getButtonHeight(screenDiagonal);
        return dp2px(heightDp);
    }

    /**
     * Get site text size proportional to screen width.
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteTextSize() {
        return FontScale.siteText();
    }

    /**
     * Get site text size for dialog (larger than search results).
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteDialogTextSize() {
        return FontScale.siteDialogText(1);
    }

    /**
     * Get site icon/checkbox size proportional to screen diagonal.
     * Uses SiteListConfig for consistent sizing across devices.
     */
    public static int getSiteIconSize() {
        double screenDiagonal = getScreenDiagonal();
        int iconDp = SiteListConfig.getIconSize(screenDiagonal);
        return dp2px(iconDp);
    }

    /**
     * Get site icon/checkbox size for dialog (larger than search results).
     * Uses SiteListConfig for consistent sizing across devices.
     */
    public static int getSiteDialogIconSize() {
        double screenDiagonal = getScreenDiagonal();
        int iconDp = SiteListConfig.getDialogIconSize(screenDiagonal);
        return dp2px(iconDp);
    }

    // Ranking-related sizing methods using SiteListConfig
    
    /**
     * Get ranking row height based on screen diagonal
     * Uses universal SiteListConfig for consistent sizing
     */
    public static int getSiteRankingRowHeight() {
        double screenDiagonal = getScreenDiagonal();
        int heightDp = SiteListConfig.getRankingRowHeight(screenDiagonal);
        return dp2px(heightDp);
    }

    /**
     * Get ranking text size for rank number
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteRankTextSize() {
        return FontScale.rankText();
    }

    /**
     * Get ranking text size for site name
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteRankNameSize() {
        return FontScale.rankName();
    }

    /**
     * Get ranking text size for score
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteRankScoreSize() {
        return FontScale.rankScore();
    }

    /**
     * Get ranking text size for stats
     * Uses FontScale for consistent, orientation-aware sizing.
     */
    public static int getSiteRankStatsSize() {
        return FontScale.rankStats();
    }

    /**
     * Get padding for ranking items
     * Uses universal SiteListConfig for consistent sizing
     */
    public static int getSiteRankingPadding() {
        double screenDiagonal = getScreenDiagonal();
        int paddingDp = SiteListConfig.getRankingPadding(screenDiagonal);
        return dp2px(paddingDp);
    }

    // ==================== Player Overlay Info/Clock Text ====================

    /** Reference height (dp) the overlay sizes were designed against. */
    private static final float OVERLAY_REFERENCE_HEIGHT_DP = 873f;
    private static final float TV_OVERLAY_REFERENCE_HEIGHT_DP = 1080f;

    /**
     * Scale the video player overlay info/clock text so its size is relative to
     * the displayed screen and 40.5% smaller than the original
     * overlay size, with a top margin of one quarter of the resulting text size.
     *
     * <p>Mobile scales against the stable long edge (≈873dp on a phone), TV against
     * the 1080dp reference height, so the overlay keeps the same proportions on
     * every screen. The margin uses the resolved text size in pixels rather than
     * the original layout margin, so repeated calls keep the same spacing.</p>
     */
    public static void scalePlayerOverlay(TextView... views) {
        for (TextView view : views) {
            if (view == null) continue;
            float scale = playerOverlayScale(view.getContext());
            // Explicit overlay variant is resolved from its semantic role, never the last text size.
            TextScale.setPlayerOverlayScale(view, scale * 1.6f * 0.7f * 0.85f);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp != null) {
                lp.topMargin = Math.max(1, Math.round(view.getTextSize() * 0.25f));
                view.setLayoutParams(lp);
            }
        }
    }

    private static float playerOverlayScale(Context context) {
        // Use the display bounds and native density. Resource display metrics can be
        // rewritten by layout scaling and must not determine playback overlay size.
        float density = context.getResources().getConfiguration().densityDpi / 160f;
        float widthDp = getScreenWidth(context) / density;
        float heightDp = getScreenHeight(context) / density;
        if (ProductCore.getDeviceType() == 0) return heightDp / TV_OVERLAY_REFERENCE_HEIGHT_DP;
        return Math.max(widthDp, heightDp) / OVERLAY_REFERENCE_HEIGHT_DP;
    }

}

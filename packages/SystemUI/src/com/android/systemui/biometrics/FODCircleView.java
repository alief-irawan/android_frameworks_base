/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Point;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements ConfigurationListener {

    private final String SCREEN_BRIGHTNESS = "system:" + Settings.System.SCREEN_BRIGHTNESS;
    private final int[][] BRIGHTNESS_ALPHA_ARRAY = {
        new int[]{0, 255},
        new int[]{1, 224},
        new int[]{2, 213},
        new int[]{3, 211},
        new int[]{4, 208},
        new int[]{5, 206},
        new int[]{6, 203},
        new int[]{8, 200},
        new int[]{10, 196},
        new int[]{15, 186},
        new int[]{20, 176},
        new int[]{30, 160},
        new int[]{45, 139},
        new int[]{70, 114},
        new int[]{100, 90},
        new int[]{150, 56},
        new int[]{227, 14},
        new int[]{255, 0}
    };

    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final boolean mDimIcon;
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager.LayoutParams mPressedParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;
    private IFingerprintInscreen mDaemon = null;

    private int mDreamingOffsetY;

    private int mColor;

    private int mCurrentBrightness;
    private int mDefaultScreenBrightness;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsKeyguard;
    private boolean mIsCircleShowing;
    private boolean mIsShowing;
    private boolean mIsScreenTurnedOn;
    private boolean mPressedViewDisplayed = false;

    private Handler mHandler;

    private final ImageView mPressedView;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;

    private FODAnimation mFODAnimation;
    private boolean mIsRecognizingAnimEnabled;
    private boolean mShouldRemoveIconOnAOD;
    private boolean mScreenOffFodEnabled;
    private boolean mScreenOffFodIconEnabled;

    private PowerManager mPowerManager;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
                if (mShouldRemoveIconOnAOD) {
                    resetFODIcon(false);
                }
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                updatePosition();
            }
            
            if (mShouldRemoveIconOnAOD && !dreaming) {
                resetFODIcon(true);
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;

            updateSettings();

            if (mFODAnimation != null) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
        }

        @Override
        public void onScreenTurnedOff() {
            mIsScreenTurnedOn = false;
            hideCircle();
        }

        @Override
        public void onScreenTurnedOn() {
            mIsScreenTurnedOn = true;
        }

        @Override
        public void onStartedWakingUp() {
            if (!mIsScreenTurnedOn &&
                    mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT &&
                    msgId == -1) { // Auth error
                hideCircle();
                mHandler.post(() -> mFODAnimation.hideFODanimation());
            }
        }
    };

    private boolean mCutoutMasked;
    private int mStatusbarHeight;

    public FODCircleView(Context context) {
        super(context);

        setScaleType(ScaleType.CENTER);

        mDaemon = getFingerprintInScreenDaemon();
        if (mDaemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = mDaemon.shouldBoostBrightness();
            mPositionX = mDaemon.getPositionX();
            mPositionY = mDaemon.getPositionY();
            mSize = mDaemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mColor = res.getColor(R.color.config_fodColor);
        mPaintFingerprint.setColor(mColor);
        mPaintFingerprint.setAntiAlias(true);

        mDimIcon = res.getBoolean(R.bool.config_fodIconDim);

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_FINGERPRINT;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_FINGERPRINT_HIGH_LIGHT;
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        mPressedView = new ImageView(context)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprint);
                }
                super.onDraw(canvas);
            }
        };
        mPressedView.setImageResource(R.drawable.fod_icon_pressed);

        mWindowManager.addView(this, mParams);

        mPowerManager = context.getSystemService(PowerManager.class);

        mFODAnimation = new FODAnimation(context, mPositionX, mPositionY);

        mCustomSettingsObserver.observe();
        mCustomSettingsObserver.update();
        updateSettings();
        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        updateCutoutFlags();

        Dependency.get(ConfigurationController.class).addCallback(this);

        mDefaultScreenBrightness = mPowerManager.getDefaultScreenBrightnessSetting();
    }

    private CustomSettingsObserver mCustomSettingsObserver = new CustomSettingsObserver(mHandler);
    private class CustomSettingsObserver extends ContentObserver {

        CustomSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS))) {
                updateIconDim();
            }
        }

        public void update() {
            updateIconDim();
        }
    }

    private int interpolate(int i, int i2, int i3, int i4, int i5) {
        int i6 = i5 - i4;
        int i7 = i - i2;
        int i8 = ((i6 * 2) * i7) / (i3 - i2);
        int i9 = i8 / 2;
        int i10 = i2 - i3;
        return i4 + i9 + (i8 % 2) + ((i10 == 0 || i6 == 0) ? 0 : (((i7 * 2) * (i - i3)) / i6) / i10);
    }

    private int getDimAlpha() {
        int length = BRIGHTNESS_ALPHA_ARRAY.length;
        int i = 0;
        while (i < length && BRIGHTNESS_ALPHA_ARRAY[i][0] < mCurrentBrightness) {
            i++;
        }
        if (i == 0) {
            return BRIGHTNESS_ALPHA_ARRAY[0][1];
        }
        if (i == length) {
            return BRIGHTNESS_ALPHA_ARRAY[length - 1][1];
        }
        int[][] iArr = BRIGHTNESS_ALPHA_ARRAY;
        int i2 = i - 1;
        return interpolate(mCurrentBrightness, iArr[i2][0], iArr[i][0], iArr[i2][1], iArr[i][1]);
    }

    public void updateIconDim() {
        mCurrentBrightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mDefaultScreenBrightness, UserHandle.USER_CURRENT);
        if (mDimIcon) {
            mHandler.post(() -> setColorFilter(Color.argb(getDimAlpha(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateSettings();
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        try {
            mDaemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        try {
            mDaemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        try {
            mDaemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        try {
            mDaemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchPress();
        });

        if (mIsRecognizingAnimEnabled) {
            mHandler.post(() -> mFODAnimation.showFODanimation());
        }

        setImageDrawable(null);
        updatePosition();
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setFODIcon();
        invalidate();

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchRelease();
        });
        setDim(false);

        mHandler.post(() -> mFODAnimation.hideFODanimation());

        setKeepScreenOn(false);
    }

    private void resetFODIcon(boolean show) {
        if (show) {
            setFODIcon();
        } else {
            this.setImageResource(0);
        }
    }

    private void setFODIcon() {
        if (mIsDreaming && mShouldRemoveIconOnAOD) {
            return;
        }

        this.setImageResource(R.drawable.fod_icon_default);
        this.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    public void show() {
        if (mIsShowing) {
            // Ignore show calls when already shown
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        mIsShowing = true;

        updatePosition();

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchShow();
        });
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;
        setVisibility(View.GONE);
        hideCircle();
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchHide();
        });
    }

    private void updateAlpha() {
        setAlpha(mIsDreaming ? 0.5f : 1.0f);
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int cutoutMaskedExtra = mCutoutMasked ? mStatusbarHeight : 0;

        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize - cutoutMaskedExtra;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsKeyguard) {
            mParams.x = mPositionX;
            mParams.y = mPositionY - cutoutMaskedExtra;
        }

        if (mFODAnimation != null) {
            mFODAnimation.updateParams(mParams.y);
        }

        if (mIsDreaming && !mIsCircleShowing) {
            mParams.y += mDreamingOffsetY;
        }

        mWindowManager.updateViewLayout(this, mParams);

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int dimAmount = 0;

            try {
                dimAmount = mDaemon.getDimAmount(mCurrentBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }

            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (!mPressedViewDisplayed && mIsShowing) {
                mPressedViewDisplayed = true;
                mWindowManager.addView(mPressedView, mPressedParams);
            } else if (mPressedViewDisplayed) {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            mPressedParams.screenBrightness = 0.0f;
            mPressedParams.dimAmount = 0.0f;
            if (mPressedViewDisplayed) {
                mPressedViewDisplayed = false;
                mWindowManager.removeViewImmediate(mPressedView);
            }
        }
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;
            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };

    @Override
    public void onOverlayChanged() {
        updateCutoutFlags();
    }

    private void updateCutoutFlags() {
        mStatusbarHeight = getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_portrait);
        boolean cutoutMasked = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        if (mCutoutMasked != cutoutMasked) {
            mCutoutMasked = cutoutMasked;
            updatePosition();
        }
    }

    private void updateSettings() {
        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 1) != 0;
        mScreenOffFodEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_FOD, 0) != 0;
        mScreenOffFodIconEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_FOD_ICON, 1) != 0;
        mShouldRemoveIconOnAOD = mScreenOffFodEnabled && !mScreenOffFodIconEnabled;
    }
}
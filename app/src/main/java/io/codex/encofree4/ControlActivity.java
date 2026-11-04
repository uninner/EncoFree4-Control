package io.codex.encofree4;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import java.util.function.Consumer;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Bottom floating panel: local frosted glass over the existing app, without a wallpaper switch. */
public final class ControlActivity extends Activity implements EncoController.Listener {
    private static final int REQUEST_BLUETOOTH = 4101;

    private static final int COLOR_TITLE_WHITE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF5F5F7;
    private static final int COLOR_TEXT_SECONDARY = 0xD9FFFFFF;
    private static final int COLOR_TEXT_MUTED = 0xB3FFFFFF;
    private static final int COLOR_DIVIDER = 0x30FFFFFF;
    private static final int COLOR_CAPSULE_UNSELECTED_FG = 0xDEFFFFFF;
    private static final int COLOR_CAPSULE_SELECTED_FG = 0xFFFFFFFF;
    private static final int COLOR_TAG_TEXT = 0xEBFFFFFF;

    private static final String STATE_PREFS = "enco_panel_state";
    // v1.8.0 cached button taps before device confirmation; do not migrate that value.
    private static final String KEY_NOISE_MODE = "last_confirmed_noise_mode";
    private static final long MODE_CONFIRM_TIMEOUT_MS = 4000;
    private static final long MODE_READ_TIMEOUT_MS = 5000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VolumeSyncPolicy volumeSync = new VolumeSyncPolicy();
    private boolean volumeReadScheduled;
    private final Runnable volumeReadTask = () -> {
        volumeReadScheduled = false;
        if (volumeSync.isActive() && canRender()) {
            syncVolumeFromSystem();
        }
    };
    private final Runnable volumePoll = new Runnable() {
        @Override
        public void run() {
            if (!volumeSync.isActive() || !canRender()) {
                return;
            }
            syncVolumeFromSystem();
            mainHandler.postDelayed(this, volumeSync.nextDelay(SystemClock.uptimeMillis()));
        }
    };
    private PanelState state;
    private boolean connected;
    private int failedMode = -1;
    private final Runnable modeTimeoutTask = () -> {
        if (!canRender() || state.pendingMode < 0) {
            return;
        }
        failedMode = state.expireRequest();
        if (state.confirmedMode < 0) {
            modeReadTimedOut = true;
        }
        renderMode(state.confirmedMode);
        showStatus(getString(R.string.mode_confirmation_timeout),
                getString(state.confirmedMode < 0 ? R.string.refresh_mode : R.string.retry),
                state.confirmedMode < 0 ? this::refreshNoiseMode
                        : () -> requestNoiseMode(failedMode));
    };
    private final Runnable modeReadTimeoutTask = () -> {
        if (!canRender() || !connected || state.confirmedMode >= 0 || state.pendingMode >= 0) {
            return;
        }
        modeReadTimedOut = true;
        renderModeTag();
        showStatus(getString(R.string.mode_read_failed),
                getString(R.string.refresh_mode), this::refreshNoiseMode);
    };

    private EncoSession.Client controller;
    private SharedPreferences statePrefs;
    private FrameLayout panel;
    private GlassSurfaceDrawable panelMaterial;
    private ValueAnimator panelAnimator;
    private float panelFraction = 1f, panelDrag;
    private int panelBaseY;
    private int appliedBlurRadius = -1;
    private boolean blurSupported, blurRegistered;
    private final Consumer<Boolean> blurListener = this::updatePanelBlur;
    private TextView connectionStatusLabel;
    private TextView moreTitle;
    private String connectionIssueMessage;
    private String connectionIssueActionLabel;
    private Runnable connectionIssueAction;
    private String modeIssueMessage;
    private String modeIssueActionLabel;
    private Runnable modeIssueAction;
    private AlertDialog modeIssueDialog;
    private PanelDragHandleView dragHandle;
    private BatteryIndicatorView batteryLeft;
    private BatteryIndicatorView batteryRight;
    private BatteryIndicatorView batteryCase;
    private boolean bothEarbudsInCase;
    private TextView transparencyButton;
    private TextView adaptiveButton;
    private ImageView deviceArt;
    private TextView earLabel;
    private View noiseSection;
    private View colorSwatch;
    private boolean leftInBox, rightInBox, hasEarState, earDetailSeen;
    private int colorIndex;
    private int confirmedDepth = -1;
    private TextView ancButton;
    private TextView offButton;
    private TextView[] modeButtons;
    private TextView noiseModeSummary;
    private VolumeBarView volumeBar;
    private AudioManager audioManager;
    private ContentObserver volumeObserver;
    private boolean volumeObserverRegistered;
    private boolean connecting;
    private boolean modeReadTimedOut;
    private boolean permissionRequestInFlight;
    private boolean reconnectOnResume;
    private boolean dismissing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        statePrefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        state = new PanelState(statePrefs.getInt(KEY_NOISE_MODE, -1));
        controller = EncoSession.get(this).attach(this, true);
        setContentView(createContent());
        configurePanelBounds();
        renderBattery();
        renderMode(state.confirmedMode);
        startPanelAnimation();
        ensureBluetoothPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ensureBluetoothPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            getWindowManager().addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
            blurRegistered = true;
        } catch (RuntimeException ignored) { updatePanelBlur(false); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) controller.setVisible(true);
        volumeSync.resume(SystemClock.uptimeMillis());
        registerVolumeObserver();
        syncVolumeFromSystem();
        mainHandler.removeCallbacks(volumePoll);
        mainHandler.post(volumePoll);
        if (reconnectOnResume) {
            reconnectOnResume = false;
            ensureBluetoothPermission();
        }
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.setVisible(false);
        volumeSync.pause();
        mainHandler.removeCallbacks(volumePoll);
        mainHandler.removeCallbacks(volumeReadTask);
        volumeReadScheduled = false;
        if (dragHandle != null) {
            dragHandle.cancelGesture();
        }
        unregisterVolumeObserver();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (canRender() && controller != null && !permissionRequestInFlight
                && !controller.isKeptForWidgets() && (connected || connecting)) {
            reconnectOnResume = true;
            controller.pausePanel();
            renderConnection(false, getString(R.string.panel_paused));
        }
        if (blurRegistered) {
            getWindowManager().removeCrossWindowBlurEnabledListener(blurListener);
            blurRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelPanelAnimation();
        getWindow().setBackgroundBlurRadius(0);
        if (modeIssueDialog != null) {
            modeIssueDialog.dismiss();
        }
        mainHandler.removeCallbacksAndMessages(null);
        unregisterVolumeObserver();
        if (controller != null) {
            controller.close();
            controller = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH) {
            return;
        }
        permissionRequestInFlight = false;
        if (!canRender()) {
            return;
        }
        if (grantResults.length > 0 && allPermissionsGranted(grantResults)) {
            renderConnection(false, getString(R.string.connecting));
            controller.connect();
        } else {
            renderConnection(false, getString(R.string.permission_denied));
        }
    }

    @Override
    public void onConnectionChanged(boolean isConnected, String message) {
        if (!canRender()) {
            return;
        }
        renderConnection(isConnected, message);
        if (isConnected) {
            applyModelFeatures();
        }
    }

    /** Hides features the connected model does not expose, and refreshes the artwork. */
    private void applyModelFeatures() {
        String name = currentDeviceName();
        if (name != null) {
            getSharedPreferences("enco_widgets", MODE_PRIVATE)
                    .edit().putString("device_name", name).apply();
        }
        if (adaptiveButton != null) {
            adaptiveButton.setVisibility(
                    EncoModels.hasAdaptiveMode(name) ? View.VISIBLE : View.GONE);
        }
        if (noiseSection != null) {
            noiseSection.setVisibility(
                    EncoModels.hasNoiseControl(name) ? View.VISIBLE : View.GONE);
        }
        renderDeviceArt();
    }

    @Override
    public void onEarDetail(boolean left, boolean right) {
        if (!canRender() || !connected) {
            return;
        }
        leftInBox = left;
        rightInBox = right;
        hasEarState = true;
        earDetailSeen = true;
        renderEarState();
        renderDeviceArt();
    }

    @Override
    public void onBattery(EncoProtocol.Battery battery) {
        if (!canRender() || !connected) {
            return;
        }
        state.mergeBattery(battery);
        renderBattery();
        // The firmware pushes an ear/case event only when something changes, so a fresh
        // connection leaves the state unknown. Charging is a reliable substitute: earbuds
        // only charge while they sit in the case. A real event still wins.
        if (!earDetailSeen && battery.count >= 2) {
            boolean left = battery.left >= 0 && battery.leftCharging;
            boolean right = battery.right >= 0 && battery.rightCharging;
            if (!hasEarState || left != leftInBox || right != rightInBox) {
                leftInBox = left;
                rightInBox = right;
                hasEarState = true;
                renderEarState();
                renderDeviceArt();
            }
        }
    }

    @Override
    public void onEarStatus(boolean bothInCase) {
        if (!canRender() || !connected) {
            return;
        }
        bothEarbudsInCase = bothInCase;
        renderBattery();
    }

    @Override
    public void onNoiseMode(int mode) {
        if (!canRender() || !connected || !PanelState.validMode(mode)) {
            return;
        }
        state.observeMode(mode);
        saveNoiseMode(mode);
        modeReadTimedOut = false;
        mainHandler.removeCallbacks(modeReadTimeoutTask);
        if (state.pendingMode < 0) {
            mainHandler.removeCallbacks(modeTimeoutTask);
            failedMode = -1;
            hideStatus();
        }
        renderMode(state.confirmedMode);
    }

    @Override
    public void onNoiseDepth(int depth) {
        if (!canRender() || !connected || depth <= 0) {
            return;
        }
        confirmedDepth = depth;
        renderModeTag();
    }

    @Override
    public void onBackPressed() {
        dismissPanel();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE) {
            requestVolumeSync();
        }
        return handled;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.setDimAmount(0f);
        window.setWindowAnimations(0);
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        window.setDecorFitsSystemWindows(true);
        window.setElevation(0f);
        panelBaseY = dp(12);
        panelMaterial = GlassSurfaceDrawable.panel(dp(28), getResources().getDisplayMetrics().density);
        window.setBackgroundDrawable(panelMaterial);
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.getDecorView().setClipToOutline(true);
        try { updatePanelBlur(getWindowManager().isCrossWindowBlurEnabled()); }
        catch (RuntimeException ignored) { updatePanelBlur(false); }
        setFinishOnTouchOutside(true);
    }

    private void configurePanelBounds() {
        Window window = getWindow();
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(480));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        applyPanelFrame();
    }

    private void updatePanelBlur(boolean enabled) {
        blurSupported = enabled;
        if (panelMaterial != null) panelMaterial.setBlurEnabled(enabled);
        applyPanelFrame();
    }

    private void applyPanelFrame() {
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        // With BOTTOM gravity, a smaller y moves the whole window down with the finger.
        int y = panelBaseY - Math.round(panelDrag + dp(20) * (1f - panelFraction));
        if (attributes.alpha != panelFraction || attributes.y != y || attributes.getBlurBehindRadius() != 0) {
            attributes.alpha = panelFraction;
            attributes.y = y;
            attributes.setBlurBehindRadius(0);
            window.setAttributes(attributes);
        }
        // Avoid redundant WindowManager work when a blur callback repeats the current state.
        int blur = blurSupported ? Math.round(Math.min(dp(18), 64) * panelFraction) : 0;
        if (appliedBlurRadius != blur) {
            window.setBackgroundBlurRadius(blur);
            appliedBlurRadius = blur;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        View decor = getWindow().getDecorView();
        boolean outside = event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                || event.getActionMasked() == MotionEvent.ACTION_DOWN
                && (event.getX() < 0 || event.getY() < 0
                || event.getX() >= decor.getWidth() || event.getY() >= decor.getHeight());
        if (outside) { dismissPanel(); return true; }
        return super.onTouchEvent(event);
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(false);

        panel = new FrameLayout(this);
        // The window owns the glass and blur so material and content move together.
        panel.setBackground(null);
        panel.setElevation(0f);
        panel.setClipToOutline(true);
        panel.setClickable(true);
        panel.setOnClickListener(view -> {
            // 拦截卡片内部点击，防止误触外部遮罩关闭
        });

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(panel, panelParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        panel.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), dp(10));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        dragHandle = new PanelDragHandleView(this, new PanelDragHandleView.Listener() {
            @Override public float dismissDistance() {
                return Math.max(dp(64), Math.min(dp(120), panel.getHeight() * 0.18f));
            }
            @Override public void onDragStarted() {
                if (canRender()) {
                    cancelPanelAnimation();
                    panelFraction = 1f;
                    applyPanelFrame();
                }
            }
            @Override public void onDrag(float distance) {
                if (canRender()) { panelDrag = distance; applyPanelFrame(); }
            }
            @Override public void onDragCancelled() {
                if (canRender()) animatePanel(1f, 0, 160, null);
            }
            @Override public void onDismiss() {
                dismissPanel(true);
            }
        });
        content.addView(dragHandle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        // 2. 顶部标题栏（16sp Medium 纯白标题 + 右侧极简圆点状态指示器）
        content.addView(createHeader());

        // 耳机立绘舞台（温润高光、细腻阴影、上下留出充裕呼吸感）
        View heroStage = createEarbudsHeroStage();
        content.addView(heroStage);

        // 顶部/中部精致电量胶囊区（L 🔋 --% / R 🔋 --% / 盒 🔋 --%）
        View batteryRow = createBatteryRow();
        content.addView(batteryRow);

        // 音量调节卡片
        View volumeCard = createVolumeCard();
        content.addView(volumeCard);

        // Glass mode controls retain the existing three-button layout.
        noiseSection = createNoiseSection();
        noiseSection.setVisibility(
                EncoModels.hasNoiseControl(currentDeviceName()) ? View.VISIBLE : View.GONE);
        content.addView(noiseSection);

        // 4. 底部杂讯清理：移除原先突兀红字，并将“更多设置”改为极简扁平化底栏
        content.addView(createFlatFooter());

        return root;
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), 0);
        header.setMinimumHeight(dp(48));

        connectionStatusLabel = text(getString(R.string.connection_label_connecting),
                12, 0xFFF2CF88, Typeface.NORMAL);
        connectionStatusLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        connectionStatusLabel.setGravity(Gravity.CENTER);
        connectionStatusLabel.setSingleLine(true);
        connectionStatusLabel.setIncludeFontPadding(false);
        connectionStatusLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        connectionStatusLabel.setMinHeight(dp(32));
        connectionStatusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionStatusLabel.setFocusable(false);
        int badgeWidth = Math.max(dp(68), (int) Math.ceil(
                connectionStatusLabel.getPaint().measureText(getString(R.string.connection_label_connecting)))
                + dp(20));

        TextView title = text(getString(R.string.device_name), 16, COLOR_TITLE_WHITE, Typeface.NORMAL);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.01f);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                badgeWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.leftMargin = dp(10);
        header.addView(connectionStatusLabel, badgeParams);
        connectionStatusLabel.setClickable(false);
        connectionStatusLabel.setLongClickable(false);
        updateConnectionLabel();
        return header;
    }

    private void updateConnectionLabel() {
        if (connectionStatusLabel == null) {
            return;
        }
        int label = connected ? R.string.connection_label_connected
                : connecting || permissionRequestInFlight ? R.string.connection_label_connecting
                : R.string.connection_label_disconnected;
        int foreground = connected ? 0xFFA5EDC0
                : connecting || permissionRequestInFlight ? 0xFFF2CF88 : 0xFFE8A1AB;
        int background = connected ? 0x283D9269
                : connecting || permissionRequestInFlight ? 0x30B58840 : 0x309E4B60;
        connectionStatusLabel.setText(label);
        connectionStatusLabel.setTextColor(foreground);
        connectionStatusLabel.setBackground(roundedWithStroke(background, dp(10), dp(1), 0x50FFFFFF));
        connectionStatusLabel.setContentDescription(getString(label)
                + (connectionIssueMessage == null ? "" : "，" + connectionIssueMessage));
        if (moreTitle != null) moreTitle.setText(connectionIssueMessage == null ? "更多设置" : "连接帮助");
        connectionStatusLabel.setTooltipText(connectionIssueMessage);
    }

    private void showConnectionIssue() {
        if (!canRender() || connectionIssueMessage == null) {
            return;
        }
        Runnable action = connectionIssueAction;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.connection_label_disconnected)
                .setMessage(connectionIssueMessage)
                .setNegativeButton(R.string.close_details, null);
        if (action != null) {
            dialog.setPositiveButton(connectionIssueActionLabel, (whichDialog, which) -> {
                if (canRender()) {
                    action.run();
                }
            });
        }
        dialog.show();
    }

    private void showStatus(String message, String actionLabel, Runnable action) {
        hideStatus();
        modeIssueMessage = message;
        modeIssueActionLabel = actionLabel;
        modeIssueAction = action;
        renderModeTag();
    }

    private void hideStatus() {
        modeIssueMessage = null;
        modeIssueActionLabel = null;
        modeIssueAction = null;
        if (modeIssueDialog != null) {
            modeIssueDialog.dismiss();
            modeIssueDialog = null;
        }
        renderModeTag();
    }

    private void showModeIssue() {
        if (!canRender() || !connected || modeIssueMessage == null || modeIssueDialog != null) {
            return;
        }
        Runnable action = modeIssueAction;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.mode_issue_title)
                .setMessage(modeIssueMessage)
                .setNegativeButton(R.string.close_details, null);
        if (action != null) {
            builder.setPositiveButton(modeIssueActionLabel, (dialog, which) -> {
                if (canRender() && connected && modeIssueAction == action) {
                    action.run();
                }
            });
        }
        modeIssueDialog = builder.create();
        modeIssueDialog.setOnDismissListener(dialog -> modeIssueDialog = null);
        modeIssueDialog.show();
    }

    private boolean canRender() {
        return !dismissing && !isFinishing() && !isDestroyed();
    }

    private void openConnectionSettings(boolean permissionSettings) {
        Intent settings = permissionSettings
                ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
                : new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        try {
            reconnectOnResume = true;
            startActivity(settings);
        } catch (RuntimeException error) {
            reconnectOnResume = false;
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private View createEarbudsHeroStage() {
        FrameLayout stage = new FrameLayout(this);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(156)
        );
        stageParams.topMargin = dp(14);
        stageParams.bottomMargin = dp(10);
        stage.setLayoutParams(stageParams);

        // 柔和环境背光光晕（提升暗房玻璃舞台的深邃感）
        View ambientGlow = new View(this);
        GradientDrawable glowDrawable = new GradientDrawable();
        glowDrawable.setShape(GradientDrawable.OVAL);
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(dp(78));
        glowDrawable.setColors(new int[]{
                Color.argb(36, 210, 228, 255),
                Color.argb(12, 160, 190, 255),
                Color.TRANSPARENT
        });
        ambientGlow.setBackground(glowDrawable);
        FrameLayout.LayoutParams glowParams = new FrameLayout.LayoutParams(dp(156), dp(156));
        glowParams.gravity = Gravity.CENTER;
        stage.addView(ambientGlow, glowParams);

        // 优先使用带温润高光与细腻阴影的高清渲染图 / 精致立体矢量图
        colorIndex = loadDeviceColor();
        deviceArt = new ImageView(this);
        deviceArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams renderParams = new FrameLayout.LayoutParams(
                dp(142),
                dp(132)
        );
        renderParams.gravity = Gravity.CENTER;
        stage.addView(deviceArt, renderParams);
        renderDeviceArt();

        earLabel = new TextView(this);
        earLabel.setTextSize(10f);
        earLabel.setTextColor(0x99FFFFFF);
        FrameLayout.LayoutParams earParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        earParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        stage.addView(earLabel, earParams);
        renderEarState();

        // The headset never reports its colour, so the swatch lets the user pick it.
        colorSwatch = new View(this);
        FrameLayout.LayoutParams swatchParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        swatchParams.gravity = Gravity.TOP | Gravity.START;
        swatchParams.setMargins(dp(18), dp(18), 0, 0);
        colorSwatch.setOnClickListener(view -> cycleDeviceColor());
        stage.addView(colorSwatch, swatchParams);
        renderSwatch();

        return stage;
    }

    private void renderDeviceArt() {
        if (deviceArt != null) {
            deviceArt.setImageResource(EncoModels.deviceImage(
                    currentDeviceName(), deviceColor(), bothInCase()));
        }
    }

    private boolean bothInCase() {
        return hasEarState && leftInBox && rightInBox;
    }

    private void renderEarState() {
        if (earLabel == null) {
            return;
        }
        earLabel.setText(hasEarState
                ? "左耳 " + (leftInBox ? "在盒" : "佩戴") + " · 右耳 " + (rightInBox ? "在盒" : "佩戴")
                : "");
    }

    private void renderSwatch() {
        if (colorSwatch == null) {
            return;
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(colorDot(deviceColor()));
        dot.setStroke(dp(1), 0x66FFFFFF);
        colorSwatch.setBackground(dot);
    }

    /** Swatch fill for a colour key, shared by every supported model. */
    private int colorDot(String color) {
        switch (color) {
            case "purple":
                return 0xFFB7B2C7;
            case "pink":
                return 0xFFF0D2CC;
            case "grey":
                return 0xFF4A4F55;
            case "sea":
                return 0xFFC7D8E8;
            default:
                return 0xFFF4F4F4;
        }
    }

    private void cycleDeviceColor() {
        String[] colors = EncoModels.colorsFor(currentDeviceName());
        colorIndex = (colorIndex + 1) % colors.length;
        renderDeviceArt();
        renderSwatch();
        getSharedPreferences("enco_widgets", MODE_PRIVATE)
                .edit()
                .putString("device_color", deviceColor())
                .apply();
        // The widget caches its decoded artwork, so drop it and refresh the widgets.
        WidgetRenderer.invalidateDevice();
        EncoWidgets.scheduleUpdate(this);
    }

    private String deviceColor() {
        String[] colors = EncoModels.colorsFor(currentDeviceName());
        return colors[Math.floorMod(colorIndex, colors.length)];
    }

    private int loadDeviceColor() {
        String[] colors = EncoModels.colorsFor(currentDeviceName());
        String saved = getSharedPreferences("enco_widgets", MODE_PRIVATE)
                .getString("device_color", colors[0]);
        for (int i = 0; i < colors.length; i++) {
            if (colors[i].equals(saved)) {
                return i;
            }
        }
        return 0;
    }

    private View createBatteryRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(2);
        rowParams.bottomMargin = dp(14);
        row.setLayoutParams(rowParams);

        batteryLeft = new BatteryIndicatorView(this, getString(R.string.battery_left));
        batteryRight = new BatteryIndicatorView(this, getString(R.string.battery_right));
        batteryCase = new BatteryIndicatorView(this, getString(R.string.battery_case));
        row.addView(batteryLeft, batteryIndicatorParams());
        row.addView(batteryRight, batteryIndicatorParams());
        row.addView(batteryCase, batteryIndicatorParams());
        return row;
    }

    private LinearLayout.LayoutParams batteryIndicatorParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(78), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(5), 0, dp(5), 0);
        return params;
    }

    private View createVolumeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(glass(dp(18), false));
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(16);
        card.setLayoutParams(cardParams);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        ImageView volumeIcon = new ImageView(this);
        volumeIcon.setImageResource(R.drawable.ic_volume);
        volumeIcon.setColorFilter(COLOR_TEXT_PRIMARY, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(22),
                dp(22)
        );
        iconParams.rightMargin = dp(12);
        card.addView(volumeIcon, iconParams);

        volumeBar = new VolumeBarView(this);
        volumeBar.setOnVolumeChangeListener(new VolumeBarView.OnVolumeChangeListener() {
            @Override
            public void onVolumeChanged(float fraction) {
                if (audioManager == null) {
                    return;
                }
                int maximum = Math.max(
                        1,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                );
                int target = Math.round(fraction * maximum);
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                } catch (RuntimeException ignored) {
                    // 忽略固定音量策略异常
                }
            }

            @Override
            public void onVolumeChangeFinished() {
                requestVolumeSync();
            }
        });
        LinearLayout.LayoutParams volumeBarParams = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
        );
        card.addView(volumeBar, volumeBarParams);
        return card;
    }

    private View createNoiseSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sectionParams.bottomMargin = dp(14);
        section.setLayoutParams(sectionParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text(
                getString(R.string.noise_control),
                13,
                COLOR_TEXT_SECONDARY,
                Typeface.NORMAL
        );
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(2), 0, 0, 0);
        header.addView(label, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        // 右上角“当前：通透”精致胶囊标签
        noiseModeSummary = text("", 11, COLOR_TAG_TEXT, Typeface.NORMAL);
        noiseModeSummary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        noiseModeSummary.setGravity(Gravity.CENTER);
        noiseModeSummary.setSingleLine(true);
        noiseModeSummary.setEllipsize(TextUtils.TruncateAt.END);
        noiseModeSummary.setMinHeight(dp(28));
        int panelWidth = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(480));
        noiseModeSummary.setMaxWidth(Math.max(dp(110), panelWidth - dp(120)));
        noiseModeSummary.setOnClickListener(view -> showModeIssue());
        noiseModeSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        noiseModeSummary.setPadding(dp(10), dp(4), dp(10), dp(4));
        noiseModeSummary.setBackground(glass(dp(999), false));
        header.addView(noiseModeSummary);

        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        headerParams.bottomMargin = dp(10);
        section.addView(header, headerParams);

        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);

        transparencyButton = noiseButton(
                R.drawable.ic_transparency,
                getString(R.string.transparency),
                EncoProtocol.MODE_TRANSPARENCY
        );
        ancButton = noiseButton(
                R.drawable.ic_anc,
                getString(R.string.noise_cancel),
                EncoProtocol.MODE_ANC
        );
        adaptiveButton = noiseButton(
                R.drawable.ic_anc,
                getString(R.string.adaptive),
                EncoProtocol.MODE_ADAPTIVE
        );
        offButton = noiseButton(
                R.drawable.ic_noise_off,
                getString(R.string.noise_off),
                EncoProtocol.MODE_OFF
        );
        modeButtons = new TextView[]{offButton, transparencyButton, ancButton, adaptiveButton};
        // Long press on the cancellation button walks the four cancellation steps.
        ancButton.setOnLongClickListener(view -> cycleNoiseDepth());

        // Display order: cancellation, adaptive, transparency, off. Each button keeps the
        // mode it was created with, so the order only affects placement, not behaviour.
        selector.addView(ancButton, weightedButton(0));
        selector.addView(adaptiveButton, weightedButton(1));
        selector.addView(transparencyButton, weightedButton(2));
        selector.addView(offButton, weightedButton(3));
        section.addView(selector, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(82)
        ));
        return section;
    }

    private View createFlatFooter() {
        LinearLayout footerWrap = new LinearLayout(this);
        footerWrap.setOrientation(LinearLayout.VERTICAL);

        // 极细微光顶部分割线
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        );
        dividerParams.bottomMargin = dp(4);
        footerWrap.addView(divider, dividerParams);

        // 简约扁平化“更多设置”底栏（带右向箭头 ›，减少视觉干扰）
        LinearLayout moreSettingsRow = new LinearLayout(this);
        moreSettingsRow.setOrientation(LinearLayout.HORIZONTAL);
        moreSettingsRow.setGravity(Gravity.CENTER_VERTICAL);
        moreSettingsRow.setPadding(dp(8), dp(10), dp(8), dp(10));

        GradientDrawable mask = rounded(Color.WHITE, dp(12));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                null,
                mask
        );
        moreSettingsRow.setBackground(ripple);
        moreSettingsRow.setClickable(true);
        moreSettingsRow.setFocusable(true);
        moreSettingsRow.setOnClickListener(view -> {
            if (connectionIssueMessage != null) {
                showConnectionIssue();
                return;
            }
            Intent settings = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(settings);
            dismissPanel();
        });

        moreTitle = text("更多设置", 14, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        moreTitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        moreSettingsRow.addView(moreTitle, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView chevron = text("›", 18, COLOR_TEXT_MUTED, Typeface.NORMAL);
        moreSettingsRow.addView(chevron);

        footerWrap.addView(moreSettingsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        return footerWrap;
    }

    private TextView noiseButton(int iconRes, String label, int mode) {
        TextView button = text(label, 13, COLOR_CAPSULE_UNSELECTED_FG, Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawablePadding(dp(6));
        button.setPadding(dp(6), dp(12), dp(6), dp(12));
        // Keep the long-press for step cycling instead of the system text picker.
        button.setTextIsSelectable(false);
        setButtonIcon(button, iconRes, COLOR_CAPSULE_UNSELECTED_FG);
        button.setOnClickListener(view -> requestNoiseMode(mode));
        return button;
    }

    private void requestNoiseMode(int mode) {
        if (!canRender() || !connected || controller == null || !state.requestMode(mode)) {
            return;
        }
        if (!controller.setNoiseMode(mode)) {
            state.expireRequest();
            renderMode(state.confirmedMode);
            return;
        }
        failedMode = -1;
        mainHandler.removeCallbacks(modeReadTimeoutTask);
        modeReadTimedOut = false;
        hideStatus();
        renderMode(state.confirmedMode);
        mainHandler.removeCallbacks(modeTimeoutTask);
        mainHandler.postDelayed(modeTimeoutTask, MODE_CONFIRM_TIMEOUT_MS);
    }

    /** Long-press handler on the cancellation button: walks the four cancellation steps. */
    /**
     * Name of the bonded earbud, used to pick the matching artwork. Returns null when no
     * supported model is bonded, in which case the generic drawing is kept.
     */
    @SuppressLint("MissingPermission")
    private String currentDeviceName() {
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                return null;
            }
            // Prefer the connected model; several Enco models may be bonded at once.
            try {
                int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET};
                for (int profile : profiles) {
                    for (BluetoothDevice device : manager.getConnectedDevices(profile)) {
                        String name = device.getName();
                        if (EncoModels.isSupported(name)) {
                            return name;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Host-side tests expose no Bluetooth profile support; use the bonded list.
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (EncoModels.isSupported(name)) {
                    return name;
                }
            }
        } catch (SecurityException ignored) {
            // Without the permission the panel keeps the generic artwork.
        }
        return null;
    }

    private boolean cycleNoiseDepth() {
        if (!canRender() || !connected || controller == null) {
            return true;
        }
        // Step from the step the earbud actually reports, so the first long press always
        // moves one step instead of repeating the current one.
        int next = confirmedDepth >= 1 && confirmedDepth < 4 ? confirmedDepth + 1 : 1;
        if (controller.setNoiseDepth(next)) {
            Toast.makeText(this, depthLabel(next), Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /**
     * Names for the four cancellation steps, in ascending bitmap order.
     *
     * <p>Calibrated on Enco X4 firmware 116.116.100 by selecting each step in the
     * vendor app on a second phone and reading the bitmap back, so these are the
     * vendor's own labels rather than inferred ones.
     */
    private String depthLabel(int depth) {
        switch (depth) {
            case 1:
                return getString(R.string.depth_1);
            case 2:
                return getString(R.string.depth_2);
            case 3:
                return getString(R.string.depth_3);
            case 4:
                return getString(R.string.depth_4);
            default:
                return "";
        }
    }

    private void renderMode(int mode) {
        if (modeButtons == null) {
            return;
        }
        int[] values = {
                EncoProtocol.MODE_OFF,
                EncoProtocol.MODE_TRANSPARENCY,
                EncoProtocol.MODE_ANC,
                EncoProtocol.MODE_ADAPTIVE
        };
        int[] icons = {
                R.drawable.ic_noise_off,
                R.drawable.ic_transparency,
                R.drawable.ic_anc,
                R.drawable.ic_anc
        };
        renderModeTag();

        for (int i = 0; i < modeButtons.length; i++) {
            TextView button = modeButtons[i];
            boolean selected = values[i] == mode;
            boolean enabled = connected && state.pendingMode < 0;
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.55f);
            button.setSelected(selected);
            if (selected) {
                button.setBackground(glass(dp(16), true));
                button.setElevation(0f);
                button.setTextColor(COLOR_CAPSULE_SELECTED_FG);
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                setButtonIcon(button, icons[i], COLOR_CAPSULE_SELECTED_FG);
            } else {
                button.setBackground(glass(dp(16), false));
                button.setElevation(0f);
                button.setTextColor(COLOR_CAPSULE_UNSELECTED_FG);
                button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                setButtonIcon(button, icons[i], COLOR_CAPSULE_UNSELECTED_FG);
            }
        }
    }

    private void renderModeTag() {
        if (noiseModeSummary == null) {
            return;
        }
        boolean hasIssue = connected && modeIssueMessage != null;
        switch (state.modeDisplay(connected, modeReadTimedOut, hasIssue)) {
            case WAITING_CONNECTION:
                noiseModeSummary.setText(R.string.mode_waiting_connection);
                break;
            case SWITCHING:
                noiseModeSummary.setText(getString(R.string.mode_switching, modeName(state.pendingMode)));
                break;
            case ISSUE:
                noiseModeSummary.setText(failedMode >= 0
                        ? R.string.mode_switch_retry_tag : R.string.mode_read_retry_tag);
                break;
            case CURRENT:
                noiseModeSummary.setText(
                        state.confirmedMode == EncoProtocol.MODE_ANC && confirmedDepth > 0
                                ? getString(R.string.mode_current_cancel, depthLabel(confirmedDepth))
                                : getString(R.string.mode_current, modeName(state.confirmedMode)));
                break;
            case READ_FAILED:
                noiseModeSummary.setText(R.string.mode_read_failed_tag);
                break;
            case READING:
                noiseModeSummary.setText(R.string.mode_reading);
                break;
        }
        noiseModeSummary.setTextColor(hasIssue ? 0xFFF2CF88 : COLOR_TAG_TEXT);
        noiseModeSummary.setClickable(hasIssue);
        noiseModeSummary.setFocusable(hasIssue);
        noiseModeSummary.setContentDescription(hasIssue
                ? getString(R.string.mode_issue_details, modeIssueMessage)
                : noiseModeSummary.getText());
        noiseModeSummary.setTooltipText(hasIssue ? modeIssueMessage : null);
    }

    private void refreshNoiseMode() {
        if (!canRender() || !connected || controller == null || state.pendingMode >= 0) {
            return;
        }
        modeReadTimedOut = false;
        mainHandler.removeCallbacks(modeReadTimeoutTask);
        renderModeTag();
        hideStatus();
        controller.refreshNoiseMode();
        mainHandler.postDelayed(modeReadTimeoutTask, MODE_READ_TIMEOUT_MS);
    }

    private void renderConnection(boolean isConnected, String message) {
        boolean newlyConnected = isConnected && !connected;
        connected = isConnected;
        connecting = !isConnected && message != null
                && (message.contains("正在连接") || message.contains("连接中")
                || message.contains("正在重连"));
        if (!isConnected) {
            mainHandler.removeCallbacks(modeTimeoutTask);
            mainHandler.removeCallbacks(modeReadTimeoutTask);
            modeReadTimedOut = false;
            state.disconnect();
            failedMode = -1;
            bothEarbudsInCase = false;
        }
        if (newlyConnected && state.confirmedMode < 0) {
            mainHandler.removeCallbacks(modeReadTimeoutTask);
            mainHandler.postDelayed(modeReadTimeoutTask, MODE_READ_TIMEOUT_MS);
        }
        renderBattery();
        renderMode(state.confirmedMode);
        hideStatus();
        connectionIssueMessage = null;
        connectionIssueActionLabel = null;
        connectionIssueAction = null;
        if (!isConnected && !connecting && !permissionRequestInFlight
                && !getString(R.string.connection_label_disconnected).equals(message)) {
            connectionIssueMessage = message == null ? getString(R.string.connection_failed) : message;
            if (message != null && message.contains("权限")) {
                connectionIssueActionLabel = getString(R.string.open_permission_settings);
                connectionIssueAction = () -> openConnectionSettings(true);
            } else if (message != null
                    && (message.contains("蓝牙未开启") || message.contains("配对"))) {
                connectionIssueActionLabel = getString(R.string.open_bluetooth_settings);
                connectionIssueAction = () -> openConnectionSettings(false);
            } else {
                connectionIssueActionLabel = getString(R.string.retry);
                connectionIssueAction = this::ensureBluetoothPermission;
            }
        }
        updateConnectionLabel();
    }

    private void ensureBluetoothPermission() {
        if (!canRender() || controller == null || connected || connecting
                || permissionRequestInFlight) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            renderConnection(false, getString(R.string.connecting));
            controller.connect();
            return;
        }
        permissionRequestInFlight = true;
        renderConnection(false, getString(R.string.permission_needed));
        requestPermissions(
                new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT
                },
                REQUEST_BLUETOOTH
        );
    }

    private void registerVolumeObserver() {
        if (volumeObserverRegistered) {
            return;
        }
        volumeObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                String setting = uri == null ? null : uri.getLastPathSegment();
                if (setting == null || setting.startsWith("volume_")) {
                    requestVolumeSync();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
        );
        volumeObserverRegistered = true;
    }

    private void unregisterVolumeObserver() {
        if (!volumeObserverRegistered || volumeObserver == null) {
            return;
        }
        getContentResolver().unregisterContentObserver(volumeObserver);
        volumeObserverRegistered = false;
    }

    private void syncVolumeFromSystem() {
        if (audioManager == null || volumeBar == null || volumeBar.isDragging()) {
            return;
        }
        int maximum = Math.max(
                1,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        );
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeBar.setSystemVolume(current, maximum);
    }

    private void requestVolumeSync() {
        if (!volumeSync.isActive() || !canRender()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        volumeSync.noteInteraction(now);
        // Keep the first queued read so repeated volume events cannot postpone it forever.
        if (!volumeReadScheduled) {
            volumeReadScheduled = true;
            mainHandler.postDelayed(volumeReadTask, 35);
        }
        mainHandler.removeCallbacks(volumePoll);
        mainHandler.postDelayed(volumePoll, volumeSync.nextDelay(now));
    }

    private void saveNoiseMode(int mode) {
        if (statePrefs != null && mode >= 0) {
            statePrefs.edit().putInt(KEY_NOISE_MODE, mode).apply();
        }
    }

    private String modeName(int mode) {
        switch (mode) {
            case EncoProtocol.MODE_OFF:
                return getString(R.string.noise_off);
            case EncoProtocol.MODE_TRANSPARENCY:
                return getString(R.string.transparency);
            case EncoProtocol.MODE_ANC:
                return getString(R.string.noise_cancel);
            case EncoProtocol.MODE_ADAPTIVE:
                return getString(R.string.adaptive);
            default:
                return "--";
        }
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startPanelAnimation() {
        panelFraction = 0f;
        panelDrag = 0;
        applyPanelFrame();
        animatePanel(1f, 0, 220, null);
    }

    private void cancelPanelAnimation() {
        if (panelAnimator != null) { panelAnimator.cancel(); panelAnimator = null; }
    }

    private void animatePanel(float fraction, float drag, long duration, Runnable finished) {
        cancelPanelAnimation();
        float fromFraction = panelFraction, fromDrag = panelDrag;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        panelAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(frame -> {
            float t = (float) frame.getAnimatedValue();
            panelFraction = fromFraction + (fraction - fromFraction) * t;
            panelDrag = fromDrag + (drag - fromDrag) * t;
            applyPanelFrame();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (panelAnimator == animator) panelAnimator = null;
                if (!cancelled && finished != null) finished.run();
            }
        });
        animator.start();
    }

    private void dismissPanel() {
        dismissPanel(false);
    }

    private void dismissPanel(boolean dragged) {
        if (dismissing) {
            return;
        }
        dismissing = true;
        volumeSync.pause();
        mainHandler.removeCallbacks(volumePoll);
        mainHandler.removeCallbacks(volumeReadTask);
        volumeReadScheduled = false;
        hideStatus();
        mainHandler.removeCallbacks(modeTimeoutTask);
        mainHandler.removeCallbacks(modeReadTimeoutTask);
        if (controller != null) {
            controller.close();
        }
        animatePanel(0f, dragged ? panel.getHeight() + dp(24) : 0,
                dragged ? 200 : 160, this::finish);
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setShadowLayer(dp(1.5f), 0, dp(0.5f), 0x90000000);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private void setButtonIcon(TextView view, int iconRes, int color) {
        Drawable icon = getDrawable(iconRes).mutate();
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        int iconSize = dp(24);
        icon.setBounds(0, 0, iconSize, iconSize);
        view.setCompoundDrawables(null, icon, null, null);
    }

    private GlassSurfaceDrawable glass(float radius, boolean selected) {
        return new GlassSurfaceDrawable(radius, getResources().getDisplayMetrics().density, selected);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundedWithStroke(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = rounded(color, radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams weightedButton(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        );
        int left = index == 0 ? 0 : dp(5);
        int right = index == 3 ? 0 : dp(5);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private void renderBattery() {
        if (batteryLeft == null || batteryRight == null || batteryCase == null) {
            return;
        }
        EncoProtocol.Battery battery = state.battery;
        batteryLeft.setBattery(battery == null ? -1 : battery.left,
                battery != null && battery.leftCharging);
        batteryRight.setBattery(battery == null ? -1 : battery.right,
                battery != null && battery.rightCharging);
        boolean showCase = battery != null && bothEarbudsInCase && battery.caseBattery >= 0;
        batteryCase.setBattery(showCase ? battery.caseBattery : -1,
                showCase && battery.caseCharging);
        batteryCase.setVisibility(showCase || (!connected && battery == null)
                ? View.VISIBLE : View.GONE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

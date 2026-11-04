package io.codex.encofree4;

import android.content.Context;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.ArrayList;

/** Main-thread owner of the one RFCOMM session shared by the panel and both widgets. */
final class EncoSession implements EncoController.Listener {
    @SuppressLint("StaticFieldLeak") // Constructor receives only getApplicationContext(), never an Activity.
    private static EncoSession instance;
    static synchronized EncoSession get(Context context) {
        if (instance == null) instance = new EncoSession(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Client> clients = new ArrayList<>();
    private final PanelState state = new PanelState(-1);
    private EncoController controller;
    private boolean connected, connecting, keepingWidgets, bothInCase, refreshing;
    private boolean leftInBox, rightInBox;
    private int noiseDepth = -1;
    private boolean interactive = true, pendingFromWidget;
    private String message = "已断开";
    private final Runnable modeTimeout = this::expireMode;
    private void expireMode() {
        if (state.pendingMode < 0) return;
        state.expireRequest();
        publishWidgets();
        if (pendingFromWidget) Toast.makeText(context, "切换失败，请重试", Toast.LENGTH_SHORT).show();
        pendingFromWidget = false;
    }
    private final Runnable refreshTimeout = () -> { refreshing = false; publishWidgets(); };

    private EncoSession(Context context) { this.context = context; }

    final class Client {
        private final EncoController.Listener listener;
        private boolean closed, visible;
        private Client(EncoController.Listener listener, boolean visible) {
            this.listener = listener;
            this.visible = visible;
        }
        void connect() {
            if (closed) return;
            if (EncoWidgets.hasWidgets(context) && !keepingWidgets) {
                EncoWidgetService.start(context, EncoWidgetService.CONNECT);
            }
            connectInternal();
            replay(this);
        }
        boolean setNoiseMode(int mode) { return !closed && requestMode(mode, false); }

        boolean setNoiseDepth(int depth) {
            if (closed || !connected || controller == null) return false;
            controller.setNoiseDepth(depth);
            return true;
        }
        void refreshNoiseMode() { if (!closed && controller != null) controller.refreshNoiseMode(); }
        void disconnect() { if (!closed) disconnectInternal(true); }
        void pausePanel() { if (!closed && !keepingWidgets) disconnectInternal(false); }
        boolean isKeptForWidgets() { return keepingWidgets; }
        void setVisible(boolean visible) { this.visible = visible; updatePolling(); }
        void close() {
            if (closed) return;
            closed = true;
            clients.remove(this);
            updatePolling();
            releaseIfUnused();
        }
    }

    Client attach(EncoController.Listener listener, boolean visible) {
        Client client = new Client(listener, visible);
        clients.add(client);
        updatePolling();
        handler.post(() -> replay(client));
        return client;
    }

    private void replay(Client client) {
        if (client.closed) return;
        client.listener.onConnectionChanged(connected, message);
        if (connected) {
            if (state.battery != null) client.listener.onBattery(state.battery);
            client.listener.onEarStatus(bothInCase);
            if (state.confirmedMode >= 0) client.listener.onNoiseMode(state.confirmedMode);
            if (noiseDepth > 0) client.listener.onNoiseDepth(noiseDepth);
        }
    }

    WidgetSnapshot snapshot() { return new WidgetSnapshot(connected, connecting, state, refreshing); }
    boolean isConnected() { return connected; }
    boolean isConnecting() { return connecting; }
    boolean manuallyPaused() {
        return context.getSharedPreferences("enco_widgets", Context.MODE_PRIVATE).getBoolean("paused", false);
    }
    void setPaused(boolean paused) {
        context.getSharedPreferences("enco_widgets", Context.MODE_PRIVATE).edit().putBoolean("paused", paused).apply();
    }
    void keepForWidgets(boolean keep) {
        keepingWidgets = keep;
        if (!keep) releaseIfUnused();
    }
    void setInteractive(boolean value) { interactive = value; updatePolling(); }

    void connectInternal() {
        setPaused(false);
        if (connected || connecting) return;
        if (controller == null) controller = new EncoController(context, this);
        connected = false;
        connecting = true;
        message = "正在连接…";
        state.disconnect();
        publishWidgets();
        updatePolling();
        controller.connect();
    }

    void disconnectInternal(boolean manual) {
        if (manual) setPaused(true);
        handler.removeCallbacks(modeTimeout);
        handler.removeCallbacks(refreshTimeout);
        refreshing = false;
        if (controller != null) controller.disconnect();
        onConnectionChanged(false, "已断开");
    }

    boolean requestMode(int mode, boolean fromWidget) {
        if (!connected || controller == null || !PanelState.validMode(mode)
                || mode == state.confirmedMode || !state.requestMode(mode)) return false;
        pendingFromWidget = fromWidget;
        publishWidgets();
        controller.setNoiseMode(mode);
        handler.removeCallbacks(modeTimeout);
        handler.postDelayed(modeTimeout, 4000);
        return true;
    }

    void refresh() {
        if (!connected || controller == null || refreshing) return;
        refreshing = true;
        publishWidgets();
        controller.refreshState();
        handler.removeCallbacks(refreshTimeout);
        handler.postDelayed(refreshTimeout, 1800);
    }

    private void updatePolling() {
        if (controller == null) return;
        boolean panelVisible = false;
        for (Client c : clients) panelVisible |= c.visible && !c.closed;
        controller.setPollIntervalMs(!interactive ? 120000 : panelVisible ? 5000 : 30000);
    }

    private void releaseIfUnused() {
        if (keepingWidgets || !clients.isEmpty()) return;
        if (controller != null) { controller.close(); controller = null; }
        onConnectionChanged(false, "已断开");
    }

    private void publishWidgets() { EncoWidgets.scheduleUpdate(context); }

    @Override public void onConnectionChanged(boolean value, String text) {
        connected = value;
        connecting = !value && text != null && (text.contains("正在连接") || text.contains("连接中") || text.contains("正在重连"));
        message = text == null ? "已断开" : text;
        if (!value) {
            state.disconnect();
            bothInCase = false;
            noiseDepth = -1;
            refreshing = false;
            handler.removeCallbacks(modeTimeout);
            handler.removeCallbacks(refreshTimeout);
        }
        for (Client c : new ArrayList<>(clients)) if (!c.closed) c.listener.onConnectionChanged(value, message);
        publishWidgets();
    }
    @Override public void onBattery(EncoProtocol.Battery battery) {
        if (!connected) return;
        state.mergeBattery(battery);
        refreshing = false;
        handler.removeCallbacks(refreshTimeout);
        for (Client c : new ArrayList<>(clients)) if (!c.closed) c.listener.onBattery(battery);
        publishWidgets();
    }
    @Override public void onEarStatus(boolean value) {
        if (!connected) return;
        bothInCase = value;
        for (Client c : new ArrayList<>(clients)) if (!c.closed) c.listener.onEarStatus(value);
    }
    @Override public void onEarDetail(boolean leftInBox, boolean rightInBox) {
        if (!connected) return;
        this.leftInBox = leftInBox;
        this.rightInBox = rightInBox;
        for (Client c : new ArrayList<>(clients)) if (!c.closed) {
            c.listener.onEarDetail(leftInBox, rightInBox);
        }
    }
    @Override public void onNoiseMode(int mode) {
        if (!connected || !PanelState.validMode(mode)) return;
        state.observeMode(mode);
        if (state.pendingMode < 0) {
            handler.removeCallbacks(modeTimeout);
            pendingFromWidget = false;
        }
        for (Client c : new ArrayList<>(clients)) if (!c.closed) c.listener.onNoiseMode(mode);
        publishWidgets();
    }
    @Override public void onNoiseDepth(int depth) {
        if (!connected || depth <= 0) return;
        noiseDepth = depth;
        for (Client c : new ArrayList<>(clients)) if (!c.closed) c.listener.onNoiseDepth(depth);
    }
}

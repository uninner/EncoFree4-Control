package io.codex.encofree4;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/** Connected-device sync. Android requires an internal FGS notice; POST_NOTIFICATIONS is not requested. */
public final class EncoWidgetService extends Service implements EncoController.Listener {
    static final String CONNECT = "io.codex.encofree4.widget.CONNECT";
    static final String REFRESH = "io.codex.encofree4.widget.REFRESH";
    static final String ANC = "io.codex.encofree4.widget.ANC";
    static final String TRANSPARENCY = "io.codex.encofree4.widget.TRANSPARENCY";
    private static final String CHANNEL = "enco_widget_connection";
    private static final int NOTIFICATION = 1900;
    private EncoSession session;
    private EncoSession.Client client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean registered, foreground;
    private final Runnable reconnect = () -> {
        if (foreground && !session.manuallyPaused()) session.connectInternal();
    };

    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean start(Context context, String action) {
        if (!allowed(context)) return false;
        try {
            context.startForegroundService(new Intent(context, EncoWidgetService.class).setAction(action));
            return true;
        } catch (IllegalStateException | SecurityException error) {
            // A launcher may not allow a start from its add/restore callback. A direct tap can retry.
            EncoWidgets.scheduleUpdate(context);
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        session = EncoSession.get(this);
        if (!allowed(this) || !EncoWidgets.hasWidgets(this)) { stopSelf(); return; }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "桌面小部件同步", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("让桌面小部件同步耳机的电量、连接与降噪模式");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            foreground = true;
        } catch (IllegalStateException | SecurityException error) { stopSelf(); return; }
        session.keepForWidgets(true);
        PowerManager power=getSystemService(PowerManager.class);
        if(power!=null) session.setInteractive(power.isInteractive());
        client = session.attach(this, false);
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(events, filter, Context.RECEIVER_EXPORTED);
        registered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground || !EncoWidgets.hasWidgets(this)) { stopSelf(); return START_NOT_STICKY; }
        String action = intent == null ? CONNECT : intent.getAction();
        if (CONNECT.equals(action)) {
            if (intent == null && session.manuallyPaused()) { stopSelf(); return START_NOT_STICKY; }
            session.connectInternal();
        } else if (REFRESH.equals(action)) {
            if (session.isConnected()) session.refresh(); else disconnectedHint();
        } else if (ANC.equals(action) || TRANSPARENCY.equals(action)) {
            if (session.isConnected()) session.requestMode(ANC.equals(action) ? EncoProtocol.MODE_ANC : EncoProtocol.MODE_TRANSPARENCY, true);
            else disconnectedHint();
        }
        return START_STICKY;
    }

    private void disconnectedHint() { Toast.makeText(this, "请先点击连接状态，连接耳机", Toast.LENGTH_SHORT).show(); }

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                session.setInteractive(true);
                session.refresh();
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) { session.setInteractive(false); return; }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    handler.removeCallbacks(reconnect);
                    session.disconnectInternal(false);
                } else if (state == BluetoothAdapter.STATE_ON) scheduleReconnect();
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (!isTarget(device)) return;
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                handler.removeCallbacks(reconnect);
                session.disconnectInternal(false);
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) scheduleReconnect();
        }
    };

    private void scheduleReconnect() {
        if (session.manuallyPaused() || session.isConnected() || session.isConnecting()) return;
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, 900);
    }

    @SuppressLint("MissingPermission")
    private boolean isTarget(BluetoothDevice device) {
        if (device == null || !allowed(this)) return false;
        try {
            return EncoModels.isSupported(device.getName());
        } catch (SecurityException error) { return false; }
    }

    private Notification notification() {
        // Required by startForeground even with notification permission absent.
        // No notification-drawer controls, status updates, sound, or permission prompt.
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_qs_earbuds)
                .setContentTitle("Enco Free4")
                .setContentText("桌面小部件同步")
                .setOngoing(true).setOnlyAlertOnce(true).setDefaults(0)
                .setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override public void onConnectionChanged(boolean connected, String message) { }
    @Override public void onBattery(EncoProtocol.Battery battery) { }
    @Override public void onEarStatus(boolean value) { }
    @Override public void onNoiseMode(int mode) { }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(events);
        if (session != null) session.keepForWidgets(false);
        if (client != null) client.close();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        EncoWidgets.scheduleUpdate(this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

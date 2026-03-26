package com.zcshou.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.zcshou.gogogo.R;
import com.zcshou.gogogo.RunningLoopActivity;

/**
 * RunningLoopService — 绕圈模拟跑步后台服务
 *
 * 核心算法：
 *   给定圆心 (centerLat, centerLng)、半径 R（米）、速度 v（km/h）、方向、圈数，
 *   按照圆的参数方程生成位置序列，通过 LocationManager.setTestProviderLocation
 *   持续推送虚拟定位。
 *
 *   位置更新频率：200ms（约5Hz），与 ServiceGo 的100ms相比稍低，
 *   跑步场景足够流畅且更省电。
 *
 * 圆心坐标系处理：
 *   百度地图使用 BD-09 坐标系，本服务直接使用用户从百度地图选取的坐标。
 *   圆形位置点计算采用球面近似：
 *     lat(θ) = centerLat + (R / 110574) * sin(θ)   [纬度每米约 1/110574 度]
 *     lng(θ) = centerLng + (R / (111320 * cos(centerLat * π/180))) * cos(θ)
 *
 * 速度与方向设置：
 *   - 顺时针：θ 从 0 向 -2π 减少（即从正上方向右方旋转）
 *   - 逆时针：θ 从 0 向 +2π 增加
 *   - bearing 角度由当前→下一点的方向向量计算
 */
public class RunningLoopService extends Service {

    private static final String TAG = "RunningLoopService";

    // ── Intent Extras ─────────────────────────────────────────────
    public static final String EXTRA_CENTER_LAT  = "extra_center_lat";
    public static final String EXTRA_CENTER_LNG  = "extra_center_lng";
    public static final String EXTRA_RADIUS      = "extra_radius";         // 米
    public static final String EXTRA_SPEED_KMH   = "extra_speed_kmh";     // km/h
    public static final String EXTRA_TOTAL_LAPS  = "extra_total_laps";    // 0=无限
    public static final String EXTRA_CLOCKWISE   = "extra_clockwise";     // boolean

    // 通知 Action
    public static final String ACTION_PAUSE_RESUME = "action_pause_resume";
    public static final String ACTION_STOP         = "action_stop";

    // ── 通知 ─────────────────────────────────────────────────────
    private static final String NOTIFICATION_CHANNEL_ID   = "running_loop_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "跑步模拟";
    private static final int    NOTIFICATION_ID            = 2001;

    // ── 位置更新间隔（ms） ──────────────────────────────────────
    private static final int UPDATE_INTERVAL_MS = 200;

    // ── Handler 消息 ─────────────────────────────────────────────
    private static final int MSG_TICK = 1;

    // ─────────────────────────────────────────────────────────────
    //  跑步状态枚举
    // ─────────────────────────────────────────────────────────────
    public enum RunState { STOPPED, RUNNING, PAUSED }

    // ─────────────────────────────────────────────────────────────
    //  回调接口
    // ─────────────────────────────────────────────────────────────
    public interface LocationCallback {
        /** 每次位置更新时回调 */
        void onLocationUpdate(double lat, double lng, int completedLaps, double totalDistance);
        /** 跑步完成（设定圈数跑完）时回调 */
        void onRunFinished(int totalLaps, double totalDistance);
        /** 状态变化时回调 */
        void onStatusChanged(RunState state);
    }

    // ─────────────────────────────────────────────────────────────
    //  Binder
    // ─────────────────────────────────────────────────────────────
    public class RunningBinder extends Binder {
        public RunningLoopService getService() {
            return RunningLoopService.this;
        }
    }

    private final IBinder mBinder = new RunningBinder();

    // ─────────────────────────────────────────────────────────────
    //  成员变量
    // ─────────────────────────────────────────────────────────────

    // 跑步参数
    private double mCenterLat;
    private double mCenterLng;
    private int    mRadius;       // 米
    private float  mSpeedKmh;
    private int    mTotalLaps;    // 0=无限
    private boolean mClockwise;

    // 运行状态
    private volatile RunState mRunState = RunState.STOPPED;
    private int    mCompletedLaps   = 0;
    private double mTotalDistance   = 0.0; // 累计米数
    private double mCurrentAngle    = Math.PI / 2.0; // 起始角度：正北（90°）

    // 位置管理
    private LocationManager mLocationManager;

    // Handler Thread（后台循环）
    private HandlerThread mHandlerThread;
    private Handler       mHandler;

    // 回调
    private LocationCallback mCallback;
    private Handler mMainHandler; // 主线程 Handler（用于回调到 UI）

    // 通知
    private NotificationManager mNotificationManager;

    // ─────────────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        setupTestProviders();
        startLocationHandlerThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        // 处理通知栏操作
        String action = intent.getAction();
        if (ACTION_PAUSE_RESUME.equals(action)) {
            if (mRunState == RunState.RUNNING) pause();
            else if (mRunState == RunState.PAUSED) resume();
            return START_STICKY;
        } else if (ACTION_STOP.equals(action)) {
            stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 读取跑步参数
        mCenterLat  = intent.getDoubleExtra(EXTRA_CENTER_LAT,  36.667662);
        mCenterLng  = intent.getDoubleExtra(EXTRA_CENTER_LNG,  117.027707);
        mRadius     = intent.getIntExtra(EXTRA_RADIUS,         200);
        mSpeedKmh   = intent.getFloatExtra(EXTRA_SPEED_KMH,   6.0f);
        mTotalLaps  = intent.getIntExtra(EXTRA_TOTAL_LAPS,     0);
        mClockwise  = intent.getBooleanExtra(EXTRA_CLOCKWISE,  true);

        // 重置状态
        mCompletedLaps  = 0;
        mTotalDistance  = 0.0;
        mCurrentAngle   = Math.PI / 2.0; // 从正北起步

        Log.d(TAG, String.format("Start: center=(%.6f,%.6f), R=%dm, v=%.1fkm/h, laps=%d, cw=%b",
                mCenterLat, mCenterLng, mRadius, mSpeedKmh, mTotalLaps, mClockwise));

        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification());

        // 开始跑步
        startTick();
        changeState(RunState.RUNNING);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopTick();
        removeTestProviders();
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  公开控制方法
    // ─────────────────────────────────────────────────────────────

    public void pause() {
        if (mRunState == RunState.RUNNING) {
            stopTick();
            changeState(RunState.PAUSED);
        }
    }

    public void resume() {
        if (mRunState == RunState.PAUSED) {
            startTick();
            changeState(RunState.RUNNING);
        }
    }

    public void stop() {
        stopTick();
        changeState(RunState.STOPPED);
        stopSelf();
    }

    public RunState getRunState() {
        return mRunState;
    }

    public void setLocationCallback(LocationCallback callback) {
        mCallback = callback;
    }

    // ─────────────────────────────────────────────────────────────
    //  核心位置计算与推送循环
    // ─────────────────────────────────────────────────────────────

    private void startLocationHandlerThread() {
        mHandlerThread = new HandlerThread("RunningLoopThread",
                Process.THREAD_PRIORITY_FOREGROUND);
        mHandlerThread.start();

        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.what == MSG_TICK && mRunState == RunState.RUNNING) {
                    doTick();
                    // 下一帧
                    sendEmptyMessageDelayed(MSG_TICK, UPDATE_INTERVAL_MS);
                }
            }
        };
    }

    private void startTick() {
        mHandler.removeMessages(MSG_TICK);
        mHandler.sendEmptyMessage(MSG_TICK);
    }

    private void stopTick() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_TICK);
        }
    }

    /**
     * 核心 Tick：
     *   1. 计算本帧角度步长 dθ
     *   2. 推进当前角度
     *   3. 计算当前位置 (lat, lng)
     *   4. 推送虚拟定位
     *   5. 检测是否完成一圈
     */
    private void doTick() {
        // 每帧移动的弧长（米）
        double distancePerTick = (mSpeedKmh * 1000.0 / 3600.0) * (UPDATE_INTERVAL_MS / 1000.0);

        // 弧长对应的圆心角（弧度）= 弧长 / 半径
        double dTheta = distancePerTick / mRadius;

        // 顺时针 → θ 减少（标准数学坐标系下顺时针为负方向）
        double prevAngle = mCurrentAngle;
        if (mClockwise) {
            mCurrentAngle -= dTheta;
        } else {
            mCurrentAngle += dTheta;
        }

        // 检测是否跨过起始角（完成一圈）
        // 逆时针：从 π/2 增大，跨过 π/2 + 2π
        // 顺时针：从 π/2 减小，跨过 π/2 - 2π
        boolean lapDone = false;
        if (mClockwise) {
            // 检测 mCurrentAngle 是否穿越了 (π/2 - 2π*n) 这些值
            // 等价：floor((π/2 - prevAngle) / 2π) != floor((π/2 - currentAngle) / 2π)
            double prevCycles = Math.floor((Math.PI / 2.0 - prevAngle) / (2 * Math.PI));
            double curCycles  = Math.floor((Math.PI / 2.0 - mCurrentAngle) / (2 * Math.PI));
            lapDone = curCycles > prevCycles;
        } else {
            double prevCycles = Math.floor((mCurrentAngle - Math.PI / 2.0) / (2 * Math.PI));
            double curCycles  = Math.floor((mCurrentAngle - prevAngle) / (2 * Math.PI) +
                    (prevAngle - Math.PI / 2.0) / (2 * Math.PI));
            lapDone = Math.floor((mCurrentAngle - Math.PI / 2.0) / (2 * Math.PI)) >
                      Math.floor((prevAngle    - Math.PI / 2.0) / (2 * Math.PI));
        }

        if (lapDone) {
            mCompletedLaps++;
            Log.d(TAG, "Completed lap: " + mCompletedLaps);
        }

        // 计算当前坐标（球面近似）
        double lat = mCenterLat + (mRadius / 110574.0) * Math.sin(mCurrentAngle);
        double lng = mCenterLng + (mRadius / (111320.0 * Math.cos(Math.toRadians(mCenterLat))))
                     * Math.cos(mCurrentAngle);

        // 计算 bearing（朝向角度，0=北，顺时针）
        double nextAngle = mClockwise ? mCurrentAngle - 0.01 : mCurrentAngle + 0.01;
        double nextLat = mCenterLat + (mRadius / 110574.0) * Math.sin(nextAngle);
        double nextLng = mCenterLng + (mRadius / (111320.0 * Math.cos(Math.toRadians(mCenterLat))))
                         * Math.cos(nextAngle);
        float bearing = computeBearing(lat, lng, nextLat, nextLng);

        // 速度（m/s）
        float speedMs = mSpeedKmh * 1000.0f / 3600.0f;

        // 推送虚拟定位
        pushLocation(lat, lng, bearing, speedMs);

        // 累计距离
        mTotalDistance += distancePerTick;

        // 通知 UI
        final double finalLat = lat;
        final double finalLng = lng;
        final int    finalLaps = mCompletedLaps;
        final double finalDist = mTotalDistance;

        if (mCallback != null) {
            mMainHandler.post(() -> mCallback.onLocationUpdate(finalLat, finalLng,
                    finalLaps, finalDist));
        }

        // 检查是否跑完指定圈数
        if (mTotalLaps > 0 && mCompletedLaps >= mTotalLaps) {
            stopTick();
            changeState(RunState.STOPPED);
            if (mCallback != null) {
                final int tl = mTotalLaps;
                final double td = mTotalDistance;
                mMainHandler.post(() -> mCallback.onRunFinished(tl, td));
            }
            stopSelf();
        }

        // 更新通知栏
        updateNotification();
    }

    // ─────────────────────────────────────────────────────────────
    //  虚拟定位推送
    // ─────────────────────────────────────────────────────────────

    private void pushLocation(double lat, double lng, float bearing, float speed) {
        try {
            Location gpsLoc = buildLocation(LocationManager.GPS_PROVIDER,
                    lat, lng, bearing, speed);
            mLocationManager.setTestProviderLocation(
                    LocationManager.GPS_PROVIDER, gpsLoc);

            Location netLoc = buildLocation(LocationManager.NETWORK_PROVIDER,
                    lat, lng, bearing, speed);
            mLocationManager.setTestProviderLocation(
                    LocationManager.NETWORK_PROVIDER, netLoc);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "pushLocation error: " + e.getMessage());
        }
    }

    private Location buildLocation(String provider, double lat, double lng,
                                   float bearing, float speed) {
        Location loc = new Location(provider);
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        loc.setAltitude(50.0);
        loc.setBearing(bearing);
        loc.setSpeed(speed);
        loc.setAccuracy(5.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.setBearingAccuracyDegrees(1.0f);
            loc.setSpeedAccuracyMetersPerSecond(0.5f);
            loc.setVerticalAccuracyMeters(3.0f);
        }
        return loc;
    }

    // ─────────────────────────────────────────────────────────────
    //  测试定位 Provider
    // ─────────────────────────────────────────────────────────────

    private void setupTestProviders() {
        addTestProvider(LocationManager.GPS_PROVIDER);
        addTestProvider(LocationManager.NETWORK_PROVIDER);
    }

    private void addTestProvider(String provider) {
        try {
            mLocationManager.removeTestProvider(provider);
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.location.provider.ProviderProperties props =
                        new android.location.provider.ProviderProperties.Builder()
                                .setHasAltitudeSupport(true)
                                .setHasSpeedSupport(true)
                                .setHasBearingSupport(true)
                                .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW)
                                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE)
                                .build();
                mLocationManager.addTestProvider(provider, false, false, false,
                        false, true, true, true, props);
            } else {
                mLocationManager.addTestProvider(provider,
                        false, false, false, false,
                        true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            }
            mLocationManager.setTestProviderEnabled(provider, true);
        } catch (Exception e) {
            Log.e(TAG, "addTestProvider(" + provider + ") error: " + e.getMessage());
        }
    }

    private void removeTestProviders() {
        removeTestProvider(LocationManager.GPS_PROVIDER);
        removeTestProvider(LocationManager.NETWORK_PROVIDER);
    }

    private void removeTestProvider(String provider) {
        try {
            mLocationManager.setTestProviderEnabled(provider, false);
            mLocationManager.removeTestProvider(provider);
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────
    //  通知
    // ─────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("跑步模拟定位通知");
            mNotificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        // 点击通知打开 Activity
        Intent openIntent = new Intent(this, RunningLoopActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 暂停/继续 Action
        Intent pauseIntent = new Intent(this, RunningLoopService.class);
        pauseIntent.setAction(ACTION_PAUSE_RESUME);
        PendingIntent pausePi = PendingIntent.getService(this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 停止 Action
        Intent stopIntent = new Intent(this, RunningLoopService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String statusText = buildNotificationText();
        String pauseLabel = mRunState == RunState.PAUSED ? "继续" : "暂停";

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("跑步模拟中")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_run_notification)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_pause, pauseLabel, pausePi)
                .addAction(R.drawable.ic_stop,  "停止",    stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private String buildNotificationText() {
        String distStr;
        if (mTotalDistance >= 1000) {
            distStr = String.format("%.2f km", mTotalDistance / 1000.0);
        } else {
            distStr = String.format("%.0f m", mTotalDistance);
        }
        String lapStr = mTotalLaps > 0
                ? String.format("第 %d/%d 圈", mCompletedLaps, mTotalLaps)
                : String.format("已跑 %d 圈", mCompletedLaps);
        return String.format("%.1f km/h  |  %s  |  %s", mSpeedKmh, distStr, lapStr);
    }

    private void updateNotification() {
        // 每5次 tick 更新一次通知（避免频繁刷新）
        mNotificationUpdateCount++;
        if (mNotificationUpdateCount % 5 == 0) {
            mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private int mNotificationUpdateCount = 0;

    // ─────────────────────────────────────────────────────────────
    //  状态变更
    // ─────────────────────────────────────────────────────────────

    private void changeState(RunState newState) {
        mRunState = newState;
        if (mCallback != null) {
            mMainHandler.post(() -> mCallback.onStatusChanged(newState));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  地理计算工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 计算从点A到点B的方位角（bearing），0=北，顺时针，单位度
     */
    private static float computeBearing(double lat1, double lng1,
                                        double lat2, double lng2) {
        double dLng = Math.toRadians(lng2 - lng1);
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double x = Math.sin(dLng) * Math.cos(radLat2);
        double y = Math.cos(radLat1) * Math.sin(radLat2)
                 - Math.sin(radLat1) * Math.cos(radLat2) * Math.cos(dLng);

        double bearing = Math.toDegrees(Math.atan2(x, y));
        return (float) ((bearing + 360) % 360);
    }
}

package com.zcshou.gogogo;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.CircleOptions;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.OverlayOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.zcshou.service.RunningLoopService;
import com.zcshou.service.RunningLoopService.RunningBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自定义绕圈模拟跑步 Activity
 *
 * 功能：
 *   1. 在地图上点击或手动输入选定圆心
 *   2. 配置半径（50m ~ 2000m）
 *   3. 配置跑步速度（1 ~ 20 km/h）
 *   4. 配置圈数（1 ~ 200圈，0表示无限）
 *   5. 选择顺时针/逆时针方向
 *   6. 启动/暂停/停止虚拟定位模拟
 *   7. 实时在地图上显示当前模拟位置和跑过轨迹
 */
public class RunningLoopActivity extends BaseActivity {

    private static final String TAG = "RunningLoopActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;

    // ── UI 组件 ──────────────────────────────────────────────────
    private MapView mMapView;
    private BaiduMap mBaiduMap;

    private EditText mEtLat;
    private EditText mEtLng;
    private EditText mEtRadius;
    private EditText mEtLaps;
    private SeekBar mSeekBarSpeed;
    private TextView mTvSpeedLabel;
    private RadioGroup mRgDirection;

    private Button mBtnSetCenter;
    private Button mBtnStart;
    private Button mBtnPause;
    private Button mBtnStop;

    private TextView mTvStatus;
    private TextView mTvProgress;

    // ── 状态 ─────────────────────────────────────────────────────
    private LatLng mCenterPoint = null;
    private boolean mIsSelectingCenter = false;

    private RunningLoopService mService;
    private boolean mIsBound = false;

    // 圆圈和轨迹 Overlay
    private List<LatLng> mTrailPoints = new ArrayList<>();
    private static final int MAX_TRAIL_POINTS = 500; // 最多保留500个轨迹点

    // 当前速度值(km/h)
    private float mCurrentSpeed = 6.0f; // 默认6km/h

    // ─────────────────────────────────────────────────────────────

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RunningBinder runBinder = (RunningBinder) binder;
            mService = runBinder.getService();
            mIsBound = true;
            // 注册回调，接收实时位置更新
            mService.setLocationCallback(new RunningLoopService.LocationCallback() {
                @Override
                public void onLocationUpdate(double lat, double lng, int completedLaps, double totalDistance) {
                    runOnUiThread(() -> updateMapAndProgress(lat, lng, completedLaps, totalDistance));
                }

                @Override
                public void onRunFinished(int totalLaps, double totalDistance) {
                    runOnUiThread(() -> onRunningFinished(totalLaps, totalDistance));
                }

                @Override
                public void onStatusChanged(RunningLoopService.RunState state) {
                    runOnUiThread(() -> updateButtonState(state));
                }
            });
            Log.d(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsBound = false;
            mService = null;
            Log.d(TAG, "Service disconnected");
        }
    };

    // ─────────────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_running_loop);

        initViews();
        initMap();
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
        // 绑定服务（如果已经在运行）
        bindLoopService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMapView.onDestroy();
        if (mIsBound) {
            unbindService(mServiceConnection);
            mIsBound = false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  初始化
    // ─────────────────────────────────────────────────────────────

    private void initViews() {
        // 标题栏返回
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.running_loop_title);
        }

        mMapView    = findViewById(R.id.mapview_loop);
        mEtLat      = findViewById(R.id.et_center_lat);
        mEtLng      = findViewById(R.id.et_center_lng);
        mEtRadius   = findViewById(R.id.et_radius);
        mEtLaps     = findViewById(R.id.et_laps);
        mSeekBarSpeed  = findViewById(R.id.seekbar_speed);
        mTvSpeedLabel  = findViewById(R.id.tv_speed_label);
        mRgDirection   = findViewById(R.id.rg_direction);

        mBtnSetCenter  = findViewById(R.id.btn_set_center);
        mBtnStart      = findViewById(R.id.btn_start);
        mBtnPause      = findViewById(R.id.btn_pause);
        mBtnStop       = findViewById(R.id.btn_stop);

        mTvStatus   = findViewById(R.id.tv_status);
        mTvProgress = findViewById(R.id.tv_progress);

        // 速度 SeekBar (1~20 km/h，默认6)
        mSeekBarSpeed.setMax(19); // 0~19 => 1~20 km/h
        mSeekBarSpeed.setProgress(5); // 默认 index=5 => 6km/h
        updateSpeedLabel(6.0f);
        mSeekBarSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mCurrentSpeed = progress + 1;
                updateSpeedLabel(mCurrentSpeed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 按钮点击
        mBtnSetCenter.setOnClickListener(v -> toggleSelectCenterMode());
        mBtnStart.setOnClickListener(v -> startRunning());
        mBtnPause.setOnClickListener(v -> pauseRunning());
        mBtnStop.setOnClickListener(v -> stopRunning());

        // 初始按钮状态
        updateButtonState(RunningLoopService.RunState.STOPPED);
    }

    private void initMap() {
        mBaiduMap = mMapView.getMap();
        mBaiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL);

        // 地图点击事件 — 选圆心
        mBaiduMap.setOnMapClickListener(new BaiduMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                if (mIsSelectingCenter) {
                    setCenter(latLng);
                    mIsSelectingCenter = false;
                    mBtnSetCenter.setText(R.string.running_loop_select_center);
                    mBtnSetCenter.setBackgroundTintList(
                            ContextCompat.getColorStateList(RunningLoopActivity.this,
                                    R.color.colorPrimary));
                    Toast.makeText(RunningLoopActivity.this,
                            R.string.running_loop_center_set, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMapPoiClick(com.baidu.mapapi.map.MapPoi mapPoi) {}
        });

        // 默认地图位置
        LatLng defaultCenter = new LatLng(36.667662, 117.027707);
        MapStatusUpdate update = MapStatusUpdateFactory.newLatLngZoom(defaultCenter, 16f);
        mBaiduMap.setMapStatus(update);
    }

    private void checkPermissions() {
        String[] perms = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
        };
        List<String> needRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest.add(p);
            }
        }
        if (!needRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.running_loop_permission_denied,
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  圆心选择
    // ─────────────────────────────────────────────────────────────

    /** 切换"地图点击选圆心"模式 */
    private void toggleSelectCenterMode() {
        mIsSelectingCenter = !mIsSelectingCenter;
        if (mIsSelectingCenter) {
            mBtnSetCenter.setText(R.string.running_loop_cancel_select);
            mBtnSetCenter.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.colorAccent));
            Toast.makeText(this, R.string.running_loop_tap_map, Toast.LENGTH_SHORT).show();
        } else {
            mBtnSetCenter.setText(R.string.running_loop_select_center);
            mBtnSetCenter.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.colorPrimary));
        }
    }

    /** 通过输入框手动确认圆心 */
    public void onConfirmManualInput(View view) {
        String latStr = mEtLat.getText().toString().trim();
        String lngStr = mEtLng.getText().toString().trim();
        if (TextUtils.isEmpty(latStr) || TextUtils.isEmpty(lngStr)) {
            Toast.makeText(this, R.string.running_loop_input_lat_lng, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            double lat = Double.parseDouble(latStr);
            double lng = Double.parseDouble(lngStr);
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                Toast.makeText(this, R.string.running_loop_invalid_coords, Toast.LENGTH_SHORT).show();
                return;
            }
            setCenter(new LatLng(lat, lng));
            Toast.makeText(this, R.string.running_loop_center_set, Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.running_loop_invalid_number, Toast.LENGTH_SHORT).show();
        }
    }

    /** 设置圆心，更新地图和输入框 */
    private void setCenter(LatLng latLng) {
        mCenterPoint = latLng;
        mEtLat.setText(String.format(Locale.US, "%.6f", latLng.latitude));
        mEtLng.setText(String.format(Locale.US, "%.6f", latLng.longitude));

        // 地图移到圆心
        MapStatusUpdate update = MapStatusUpdateFactory.newLatLngZoom(latLng, 16f);
        mBaiduMap.setMapStatus(update);

        // 绘制预览（如果已有半径）
        refreshCirclePreview();
    }

    // ─────────────────────────────────────────────────────────────
    //  地图圆圈预览
    // ─────────────────────────────────────────────────────────────

    private void refreshCirclePreview() {
        if (mCenterPoint == null) return;
        String radiusStr = mEtRadius.getText().toString().trim();
        if (TextUtils.isEmpty(radiusStr)) return;
        try {
            int radius = Integer.parseInt(radiusStr);
            drawCirclePreview(mCenterPoint, radius);
        } catch (NumberFormatException ignored) {}
    }

    private void drawCirclePreview(LatLng center, int radius) {
        mBaiduMap.clear();
        mTrailPoints.clear();

        // 绘制圆圈（半透明填充）
        CircleOptions circleOpt = new CircleOptions()
                .center(center)
                .radius(radius)
                .fillColor(0x22009688)    // 半透明青色
                .stroke(new com.baidu.mapapi.map.Stroke(4, 0xFF009688));
        mBaiduMap.addOverlay(circleOpt);

        // 圆心标记
        OverlayOptions markerOpt = new MarkerOptions()
                .position(center)
                .icon(BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_CYAN))
                .title(getString(R.string.running_loop_center_marker));
        mBaiduMap.addOverlay(markerOpt);
    }

    // ─────────────────────────────────────────────────────────────
    //  参数读取与验证
    // ─────────────────────────────────────────────────────────────

    private boolean validateParams() {
        if (mCenterPoint == null) {
            Toast.makeText(this, R.string.running_loop_no_center, Toast.LENGTH_SHORT).show();
            return false;
        }
        String radiusStr = mEtRadius.getText().toString().trim();
        if (TextUtils.isEmpty(radiusStr)) {
            Toast.makeText(this, R.string.running_loop_no_radius, Toast.LENGTH_SHORT).show();
            return false;
        }
        int radius;
        try {
            radius = Integer.parseInt(radiusStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.running_loop_invalid_number, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (radius < 50 || radius > 2000) {
            Toast.makeText(this, R.string.running_loop_radius_range, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    //  跑步控制
    // ─────────────────────────────────────────────────────────────

    private void startRunning() {
        if (!validateParams()) return;

        int radius  = Integer.parseInt(mEtRadius.getText().toString().trim());
        String lapsStr = mEtLaps.getText().toString().trim();
        int laps = TextUtils.isEmpty(lapsStr) ? 0 : Integer.parseInt(lapsStr); // 0=无限
        boolean clockwise = mRgDirection.getCheckedRadioButtonId() == R.id.rb_clockwise;

        // 预绘圆圈
        drawCirclePreview(mCenterPoint, radius);

        // 启动服务
        Intent intent = new Intent(this, RunningLoopService.class);
        intent.putExtra(RunningLoopService.EXTRA_CENTER_LAT,  mCenterPoint.latitude);
        intent.putExtra(RunningLoopService.EXTRA_CENTER_LNG,  mCenterPoint.longitude);
        intent.putExtra(RunningLoopService.EXTRA_RADIUS,      radius);
        intent.putExtra(RunningLoopService.EXTRA_SPEED_KMH,   mCurrentSpeed);
        intent.putExtra(RunningLoopService.EXTRA_TOTAL_LAPS,  laps);
        intent.putExtra(RunningLoopService.EXTRA_CLOCKWISE,   clockwise);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        bindLoopService();
        mTvStatus.setText(R.string.running_loop_status_running);
    }

    private void pauseRunning() {
        if (mIsBound && mService != null) {
            if (mService.getRunState() == RunningLoopService.RunState.RUNNING) {
                mService.pause();
            } else if (mService.getRunState() == RunningLoopService.RunState.PAUSED) {
                mService.resume();
            }
        }
    }

    private void stopRunning() {
        if (mIsBound && mService != null) {
            mService.stop();
        }
        stopAndUnbindService();
    }

    private void stopAndUnbindService() {
        if (mIsBound) {
            try {
                unbindService(mServiceConnection);
            } catch (Exception ignored) {}
            mIsBound = false;
            mService = null;
        }
        Intent intent = new Intent(this, RunningLoopService.class);
        stopService(intent);
        updateButtonState(RunningLoopService.RunState.STOPPED);
        mTvStatus.setText(R.string.running_loop_status_stopped);
    }

    private void bindLoopService() {
        if (!mIsBound) {
            Intent intent = new Intent(this, RunningLoopService.class);
            bindService(intent, mServiceConnection, BIND_AUTO_CREATE);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  实时 UI 更新（来自 Service 回调）
    // ─────────────────────────────────────────────────────────────

    /**
     * 更新地图当前位置标记与跑步轨迹
     */
    private void updateMapAndProgress(double lat, double lng, int completedLaps, double totalDistance) {
        LatLng curPos = new LatLng(lat, lng);

        // 加入轨迹列表（限制长度）
        mTrailPoints.add(curPos);
        if (mTrailPoints.size() > MAX_TRAIL_POINTS) {
            mTrailPoints.remove(0);
        }

        // 重绘：先清图 → 圆圈 → 轨迹 → 当前位置
        mBaiduMap.clear();

        // 圆圈
        String radiusStr = mEtRadius.getText().toString().trim();
        if (!TextUtils.isEmpty(radiusStr) && mCenterPoint != null) {
            try {
                int radius = Integer.parseInt(radiusStr);
                CircleOptions circleOpt = new CircleOptions()
                        .center(mCenterPoint)
                        .radius(radius)
                        .fillColor(0x22009688)
                        .stroke(new com.baidu.mapapi.map.Stroke(4, 0xFF009688));
                mBaiduMap.addOverlay(circleOpt);
            } catch (NumberFormatException ignored) {}
        }

        // 轨迹线（超过2个点才能画）
        if (mTrailPoints.size() > 1) {
            PolylineOptions polyOpt = new PolylineOptions()
                    .points(new ArrayList<>(mTrailPoints))
                    .width(6)
                    .color(0xCC4CAF50); // 绿色轨迹
            mBaiduMap.addOverlay(polyOpt);
        }

        // 当前位置小圆点
        OverlayOptions markerOpt = new MarkerOptions()
                .position(curPos)
                .icon(BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_GREEN))
                .flat(true);
        mBaiduMap.addOverlay(markerOpt);

        // 进度文字
        String progressText = getString(R.string.running_loop_progress_fmt,
                completedLaps, totalDistance / 1000.0);
        mTvProgress.setText(progressText);
    }

    private void onRunningFinished(int totalLaps, double totalDistance) {
        stopAndUnbindService();
        String msg = getString(R.string.running_loop_finished_fmt,
                totalLaps, totalDistance / 1000.0);
        new AlertDialog.Builder(this)
                .setTitle(R.string.running_loop_finished_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 根据 RunState 更新按钮可用性 */
    private void updateButtonState(RunningLoopService.RunState state) {
        switch (state) {
            case STOPPED:
                mBtnStart.setEnabled(true);
                mBtnPause.setEnabled(false);
                mBtnStop.setEnabled(false);
                mBtnPause.setText(R.string.running_loop_pause);
                mTvStatus.setText(R.string.running_loop_status_stopped);
                break;
            case RUNNING:
                mBtnStart.setEnabled(false);
                mBtnPause.setEnabled(true);
                mBtnStop.setEnabled(true);
                mBtnPause.setText(R.string.running_loop_pause);
                mTvStatus.setText(R.string.running_loop_status_running);
                break;
            case PAUSED:
                mBtnStart.setEnabled(false);
                mBtnPause.setEnabled(true);
                mBtnStop.setEnabled(true);
                mBtnPause.setText(R.string.running_loop_resume);
                mTvStatus.setText(R.string.running_loop_status_paused);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────────────────────

    private void updateSpeedLabel(float speedKmh) {
        // 换算为配速 min/km
        float paceMinPerKm = 60.0f / speedKmh;
        int paceMin = (int) paceMinPerKm;
        int paceSec = (int) ((paceMinPerKm - paceMin) * 60);
        mTvSpeedLabel.setText(getString(R.string.running_loop_speed_fmt,
                speedKmh, paceMin, paceSec));
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // 半径输入变化时实时预览
    public void onRadiusChanged(android.text.Editable s) {
        if (mCenterPoint != null && !TextUtils.isEmpty(s)) {
            try {
                int r = Integer.parseInt(s.toString());
                if (r >= 50 && r <= 2000) {
                    drawCirclePreview(mCenterPoint, r);
                }
            } catch (NumberFormatException ignored) {}
        }
    }
}

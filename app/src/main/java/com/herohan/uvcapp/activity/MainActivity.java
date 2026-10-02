package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.VideoCapture;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.opengl.renderer.MirrorMode;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.utils.UriHelper;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.databinding.ActivityMainBinding;
import com.herohan.uvcapp.fragment.CameraControlsDialogFragment;
import com.herohan.uvcapp.fragment.DeviceListDialogFragment;
import com.herohan.uvcapp.fragment.VideoFormatDialogFragment;
import com.herohan.uvcapp.fragment.SettingsDialogFragment;
import com.herohan.uvcapp.utils.SaveHelper;

import android.os.SystemClock;
import android.animation.ObjectAnimator;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;

    private ActivityMainBinding mBinding;

    private static final int QUARTER_SECOND = 250;
    private static final int HALF_SECOND = 500;
    private static final int ONE_SECOND = 1000;

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    /**
     * Camera preview width
     */
    private int mPreviewWidth = DEFAULT_WIDTH;
    /**
     * Camera preview height
     */
    private int mPreviewHeight = DEFAULT_HEIGHT;

    private int mPreviewRotation = 0;

    private ICameraHelper mCameraHelper;
    private ClipBufferManager mClipBufferManager;

    private static final int[] CLIP_DURATIONS_SECONDS = {30, 60, 90, 120};
    private static final int MIN_BITRATE_MBPS = 1;
    private static final int MAX_BITRATE_MBPS = 250;
    private int mVideoBitrateMbps = 6;
    private int mSelectedClipDurationSeconds = 30;
    private ObjectAnimator mConnectionPulse;

    private UsbDevice mUsbDevice;
    private final ICameraHelper.StateCallback mStateCallback = new MyCameraHelperCallback();

    private long mRecordStartTime = 0;
    private Timer mRecordTimer = null;
    private DecimalFormat mDecimalFormat;

    private boolean mIsRecording = false;
    private boolean mIsCameraConnected = false;
    private boolean mIsSavingClip = false;

    private final Handler mUiHandler = new Handler();
    private final Runnable mClipStatusUpdater = new Runnable() {
        @Override public void run() {
            updateClipStatus();
            if (mIsCameraConnected) mUiHandler.postDelayed(this, HALF_SECOND);
        }
    };

    private CameraControlsDialogFragment mControlsDialog;
    private DeviceListDialogFragment mDeviceListDialog;
    private VideoFormatDialogFragment mFormatDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        checkCameraHelper();

        setListeners();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            if (!mIsCameraConnected) {
                mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                selectDevice(mUsbDevice);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        initPreviewView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsRecording) {
            toggleVideoRecord(false);
        }
    }

    @Override
    protected void onDestroy() {
        mUiHandler.removeCallbacks(mClipStatusUpdater);
        if (mClipBufferManager != null) {
            mClipBufferManager.stop();
        }
        super.onDestroy();
        clearCameraHelper();
    }

    private void setListeners() {
        mBinding.btnManualRecord.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request((permissions, all) -> toggleVideoRecord(!mIsRecording));
        });

        mBinding.chipDuration30.setOnClickListener(v -> setClipDuration(30));
        mBinding.chipDuration60.setOnClickListener(v -> setClipDuration(60));
        mBinding.chipDuration90.setOnClickListener(v -> setClipDuration(90));
        mBinding.chipDuration120.setOnClickListener(v -> setClipDuration(120));
        updateDurationChips();

        mBinding.btnQuality.setOnClickListener(v -> showVideoFormatDialog());
        mBinding.btnSettings.setOnClickListener(v -> showSettingsDialog());

        mBinding.btnGallery.setOnClickListener(v -> openGallery());

        mBinding.btnClipNow.setOnClickListener(v -> {
            if (mClipBufferManager == null) {
                Toast.makeText(this, "Buffer not ready yet", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!hasEnoughStorage()) {
                Toast.makeText(this, "Not enough storage for a clip", Toast.LENGTH_LONG).show();
                return;
            }
            mIsSavingClip = true;
            mBinding.btnClipNow.setEnabled(false);
            mBinding.btnClipNow.setText("SAVING");
            Toast.makeText(this, "Saving clip...", Toast.LENGTH_SHORT).show();
            mClipBufferManager.clipNow(new ClipBufferManager.ClipCallback() {
                @Override public void onClipSaved(java.io.File outputFile) {
                    mIsSavingClip = false;
                    mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                    mBinding.btnClipNow.setText("CLIP\nNOW");
                    Toast.makeText(MainActivity.this, "Clip saved: " + outputFile.getName(), Toast.LENGTH_SHORT).show();
                }
                @Override public void onClipFailed(String reason) {
                    mIsSavingClip = false;
                    mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                    mBinding.btnClipNow.setText("CLIP\nNOW");
                    Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setClipDuration(int seconds) {
        mSelectedClipDurationSeconds = seconds;
        if (mClipBufferManager != null) mClipBufferManager.setClipDurationSeconds(seconds);
        updateDurationChips();
        updateClipStatus();
    }

    private void updateDurationChips() {
        mBinding.chipDuration30.setSelected(mSelectedClipDurationSeconds == 30);
        mBinding.chipDuration60.setSelected(mSelectedClipDurationSeconds == 60);
        mBinding.chipDuration90.setSelected(mSelectedClipDurationSeconds == 90);
        mBinding.chipDuration120.setSelected(mSelectedClipDurationSeconds == 120);
    }

    private void showSettingsDialog() {
        SettingsDialogFragment dialog = new SettingsDialogFragment(
                mVideoBitrateMbps, mSelectedClipDurationSeconds, mClipBufferManager);
        dialog.setOnSettingsChangedListener((bitrateMbps, durationSeconds) -> {
            mVideoBitrateMbps = Math.max(MIN_BITRATE_MBPS, Math.min(MAX_BITRATE_MBPS, bitrateMbps));
            if (mClipBufferManager != null) {
                mClipBufferManager.setVideoBitrateBps(mVideoBitrateMbps * 1024 * 1024);
                mClipBufferManager.setClipDurationSeconds(durationSeconds);
            }
            mSelectedClipDurationSeconds = durationSeconds;
            mBinding.tvClipBitrate.setText(mVideoBitrateMbps + " Mbps");
            updateDurationChips();
            updateClipStatus();
        });
        dialog.show(getSupportFragmentManager(), "settings");
    }

    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType("video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_PICK);
            fallback.setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            startActivity(fallback);
        }
    }

    private void showCameraControlsDialog() {
        if (mControlsDialog == null) {
            mControlsDialog = new CameraControlsDialogFragment(mCameraHelper);
        }
        // When DialogFragment is not showing
        if (!mControlsDialog.isAdded()) {
            mControlsDialog.show(getSupportFragmentManager(), "camera_controls");
        }
    }

    private void showDeviceListDialog() {
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            return;
        }

        mDeviceListDialog = new DeviceListDialogFragment(mCameraHelper, mIsCameraConnected ? mUsbDevice : null);
        mDeviceListDialog.setOnDeviceItemSelectListener(usbDevice -> {
            if (mIsCameraConnected) {
                mCameraHelper.closeCamera();
            }
            mUsbDevice = usbDevice;
            selectDevice(mUsbDevice);
        });

        mDeviceListDialog.show(getSupportFragmentManager(), "device_list");
    }

    private void showVideoFormatDialog() {
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            return;
        }

        mFormatDialog = new VideoFormatDialogFragment(mCameraHelper.getSupportedFormatList(), mCameraHelper.getPreviewSize());
        mFormatDialog.setOnVideoFormatSelectListener(size -> {
            if (mIsCameraConnected && !mCameraHelper.isRecording()) {
                mCameraHelper.stopPreview();
                mCameraHelper.setPreviewSize(size);
                mCameraHelper.startPreview();
                resizePreviewView(size);
                // save selected preview size
                setSavedPreviewSize(size);
            }
        });

        mFormatDialog.show(getSupportFragmentManager(), "video_format");
    }

    private void closeAllDialogFragment() {
        if (mControlsDialog != null && mControlsDialog.isAdded()) {
            mControlsDialog.dismiss();
        }
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            mDeviceListDialog.dismiss();
        }
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            mFormatDialog.dismiss();
        }
    }

    private void safelyEject() {
        if (mCameraHelper != null) {
            mCameraHelper.closeCamera();
        }
    }

    private void rotateBy(int angle) {
        mPreviewRotation += angle;
        mPreviewRotation %= 360;
        if (mPreviewRotation < 0) {
            mPreviewRotation += 360;
        }

        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setRotation(mPreviewRotation));
        }
    }

    private void flipHorizontally() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_HORIZONTAL));
        }
    }

    private void flipVertically() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_VERTICAL));
        }
    }

    private void checkCameraHelper() {
        if (!mIsCameraConnected) {
            clearCameraHelper();
        }
        initCameraHelper();
    }

    private void initCameraHelper() {
        if (mCameraHelper == null) {
            mCameraHelper = new CameraHelper();
            mCameraHelper.setStateCallback(mStateCallback);

            setCustomImageCaptureConfig();
            setCustomVideoCaptureConfig();
        }
    }

    private void clearCameraHelper() {
        if (DEBUG) Log.v(TAG, "clearCameraHelper:");
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
    }

    private void initPreviewView() {
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
        mBinding.viewMainPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (mCameraHelper != null) {
                    mCameraHelper.addSurface(surface, false);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (mCameraHelper != null) {
                    mCameraHelper.removeSurface(surface);
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

            }
        });
    }


    public void attachNewDevice(UsbDevice device) {
        if (mUsbDevice == null) {
            mUsbDevice = device;

            selectDevice(device);
        }
    }

    /**
     * In Android9+, connected to the UVC CAMERA, CAMERA permission is required
     *
     * @param device
     */
    protected void selectDevice(UsbDevice device) {
        if (DEBUG) Log.v(TAG, "selectDevice:device=" + device.getDeviceName());

        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request((permissions, all) -> {
                    mIsCameraConnected = false;
                    updateUIControls();

                    if (mCameraHelper != null) {
                        // 通过UsbDevice对象，尝试获取设备权限
                        mCameraHelper.selectDevice(device);
                    }
                });
    }

    private class MyCameraHelperCallback implements ICameraHelper.StateCallback {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:device=" + device.getDeviceName());

            attachNewDevice(device);
        }

        /**
         * After obtaining USB device permissions, connect the USB camera
         */
        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (DEBUG) Log.v(TAG, "onDeviceOpen:device=" + device.getDeviceName());

            mCameraHelper.openCamera(getSavedPreviewSize());

            mCameraHelper.setButtonCallback(new IButtonCallback() {
                @Override
                public void onButton(int button, int state) {
                    Toast.makeText(MainActivity.this, "onButton(button=" + button + "; " +
                            "state=" + state + ")", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraOpen:device=" + device.getDeviceName());
            mCameraHelper.startPreview();

            // After connecting to the camera, you can get preview size of the camera
            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                resizePreviewView(size);
            }

            if (mBinding.viewMainPreview.getSurfaceTexture() != null) {
                mCameraHelper.addSurface(mBinding.viewMainPreview.getSurfaceTexture(), false);
            }

            mIsCameraConnected = true;
            updateUIControls();

            if (mClipBufferManager == null) {
                mClipBufferManager = new ClipBufferManager(MainActivity.this, mCameraHelper);
            }
            mClipBufferManager.setClipDurationSeconds(mSelectedClipDurationSeconds);
            mClipBufferManager.setVideoBitrateBps(mVideoBitrateMbps * 1024 * 1024);
            applyVideoCaptureConfig();
            mClipBufferManager.start();
            mUiHandler.removeCallbacks(mClipStatusUpdater);
            mUiHandler.post(mClipStatusUpdater);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraClose:device=" + device.getDeviceName());

            if (mClipBufferManager != null) {
                mClipBufferManager.stop();
            }

            if (mIsRecording) {
                toggleVideoRecord(false);
            }

            if (mCameraHelper != null && mBinding.viewMainPreview.getSurfaceTexture() != null) {
                mCameraHelper.removeSurface(mBinding.viewMainPreview.getSurfaceTexture());
            }

            mIsCameraConnected = false;
            mUiHandler.removeCallbacks(mClipStatusUpdater);
            updateUIControls();

            closeAllDialogFragment();
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDeviceClose:device=" + device.getDeviceName());
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }
    }

    private void resizePreviewView(Size size) {
        // Update the preview size
        mPreviewWidth = size.width;
        mPreviewHeight = size.height;
        // Set the aspect ratio of TextureView to match the aspect ratio of the camera
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
    }

    private void updateUIControls() {
        runOnUiThread(() -> {
            if (mIsCameraConnected) {
                mBinding.viewMainPreview.setVisibility(View.VISIBLE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.GONE);
                mBinding.topControls.setVisibility(View.VISIBLE);
                mBinding.bottomControls.setVisibility(View.VISIBLE);
                mBinding.bufferProgress.setVisibility(View.VISIBLE);
                mBinding.tvClipBitrate.setVisibility(View.VISIBLE);
                mBinding.btnClipNow.setVisibility(View.VISIBLE);
                mBinding.btnManualRecord.setVisibility(View.VISIBLE);
                mBinding.btnGallery.setVisibility(View.VISIBLE);
                mBinding.tvConnectionStatus.setText("Capture Clipper connected");
                mBinding.connectionDot.setBackgroundTintList(ColorStateList.valueOf(0xFF00E5FF));
                startConnectionPulse();
                mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                mBinding.tvClipBitrate.setText(mVideoBitrateMbps + " Mbps");
            } else {
                mBinding.viewMainPreview.setVisibility(View.GONE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.VISIBLE);
                mBinding.topControls.setVisibility(View.VISIBLE);
                mBinding.bottomControls.setVisibility(View.GONE);
                mBinding.bufferProgress.setVisibility(View.GONE);
                mBinding.tvClipBitrate.setVisibility(View.GONE);
                mBinding.btnClipNow.setVisibility(View.GONE);
                mBinding.btnManualRecord.setVisibility(View.GONE);
                mBinding.btnGallery.setVisibility(View.GONE);
                mBinding.tvConnectionStatus.setText("Capture Clipper disconnected");
                mBinding.connectionDot.setBackgroundTintList(ColorStateList.valueOf(0x66B8B2C9));
                stopConnectionPulse();
                mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            }
        });
    }

    private void startConnectionPulse() {
        if (mConnectionPulse != null) return;
        mConnectionPulse = ObjectAnimator.ofFloat(mBinding.connectionDot, View.ALPHA, 1f, 0.35f, 1f);
        mConnectionPulse.setDuration(1100);
        mConnectionPulse.setRepeatCount(ObjectAnimator.INFINITE);
        mConnectionPulse.start();
    }

    private void stopConnectionPulse() {
        if (mConnectionPulse != null) {
            mConnectionPulse.cancel();
            mConnectionPulse = null;
            mBinding.connectionDot.setAlpha(1f);
        }
    }

    private void updateClipStatus() {
        if (mBinding == null || !mIsCameraConnected || mClipBufferManager == null) return;
        int buffered = mClipBufferManager.getBufferedSeconds();
        int target = mClipBufferManager.getClipDurationSeconds();
        mBinding.bufferProgress.setProgress(buffered, target);
        if (!hasEnoughStorage()) {
            mBinding.tvClipBitrate.setText("Low storage");
            mBinding.btnClipNow.setEnabled(false);
        } else {
            mBinding.tvClipBitrate.setText(mVideoBitrateMbps + " Mbps");
            if (!mIsSavingClip && !mBinding.btnClipNow.isEnabled()) mBinding.btnClipNow.setEnabled(true);
        }
    }

    private boolean hasEnoughStorage() {
        try {
            StatFs statFs = new StatFs(getFilesDir().getAbsolutePath());
            return statFs.getAvailableBytes() >= 512L * 1024L * 1024L;
        } catch (Exception e) {
            Log.w(TAG, "Unable to check available storage", e);
            return true;
        }
    }

    private Size getSavedPreviewSize() {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        String sizeStr = getPreferences(MODE_PRIVATE).getString(key, null);
        if (TextUtils.isEmpty(sizeStr)) {
            return null;
        }
        Gson gson = new Gson();
        return gson.fromJson(sizeStr, Size.class);
    }

    private void setSavedPreviewSize(Size size) {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        Gson gson = new Gson();
        String json = gson.toJson(size);
        getPreferences(MODE_PRIVATE)
                .edit()
                .putString(key, json)
                .apply();
    }

    private void setCustomImageCaptureConfig() {
//        mCameraHelper.setImageCaptureConfig(
//                mCameraHelper.getImageCaptureConfig().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY));
        mCameraHelper.setImageCaptureConfig(
                mCameraHelper.getImageCaptureConfig().setJpegCompressionQuality(90));
    }

    public void takePicture() {
        if (mIsRecording) {
            return;
        }

        try {
            File file = new File(SaveHelper.getSavePhotoPath());
            ImageCapture.OutputFileOptions options =
                    new ImageCapture.OutputFileOptions.Builder(file).build();
            mCameraHelper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    Toast.makeText(MainActivity.this,
                            "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int imageCaptureError, @NonNull String message, @Nullable Throwable cause) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
    }

    public void toggleVideoRecord(boolean isRecording) {
        try {
            if (isRecording) {
                if (mIsCameraConnected && mCameraHelper != null && !mCameraHelper.isRecording()) {
                    startRecord();
                }
            } else {
                if (mIsCameraConnected && mCameraHelper != null && mCameraHelper.isRecording()) {
                    stopRecord();
                }

                stopRecordTimer();
            }
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
            stopRecordTimer();
        }

        mIsRecording = isRecording;

        updateUIControls();
    }

    private void setCustomVideoCaptureConfig() {
        applyVideoCaptureConfig();
    }

    private void applyVideoCaptureConfig() {
        if (mCameraHelper == null) {
            return;
        }
        mCameraHelper.setVideoCaptureConfig(
                mCameraHelper.getVideoCaptureConfig()
                        .setAudioCaptureEnable(false)
                        .setBitRate(mVideoBitrateMbps * 1024 * 1024)
                        .setVideoFrameRate(25)
                        .setIFrameInterval(1));
    }

    private void startRecord() {
        File file = new File(SaveHelper.getSaveVideoPath());
        VideoCapture.OutputFileOptions options =
                new VideoCapture.OutputFileOptions.Builder(file).build();
        mCameraHelper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
                startRecordTimer();
            }

            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults outputFileResults) {
                toggleVideoRecord(false);

                Toast.makeText(
                        MainActivity.this,
                        "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                toggleVideoRecord(false);

                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopRecord() {
        mCameraHelper.stopRecording();
    }

    private void startRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.VISIBLE));

        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));

        // Start Record Timer
        mRecordStartTime = SystemClock.elapsedRealtime();
        mRecordTimer = new Timer();
        //The timer is refreshed every quarter second
        mRecordTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long recordTime = (SystemClock.elapsedRealtime() - mRecordStartTime) / 1000;
                if (recordTime > 0) {
                    setVideoRecordTimeText(formatTime(recordTime));
                }
            }
        }, QUARTER_SECOND, QUARTER_SECOND);
    }

    private void stopRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.GONE));

        // Stop Record Timer
        mRecordStartTime = 0;
        if (mRecordTimer != null) {
            mRecordTimer.cancel();
            mRecordTimer = null;
        }
        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));
    }

    private void setVideoRecordTimeText(String timeText) {
        runOnUiThread(() -> {
            mBinding.tvVideoRecordTime.setText(timeText);
        });
    }

    /**
     * 将秒转化为 HH:mm:ss 的格式
     *
     * @param time 秒
     * @return
     */
    private String formatTime(long time) {
        if (mDecimalFormat == null) {
            mDecimalFormat = new DecimalFormat("00");
        }
        String hh = mDecimalFormat.format(time / 3600);
        String mm = mDecimalFormat.format(time % 3600 / 60);
        String ss = mDecimalFormat.format(time % 60);
        return hh + ":" + mm + ":" + ss;
    }
}

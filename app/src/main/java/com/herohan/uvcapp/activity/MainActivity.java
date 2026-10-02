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
import com.herohan.uvcapp.utils.SaveHelper;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.Gravity;
import android.view.Window;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;

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

        setSupportActionBar(mBinding.toolbar);

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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_control) {
            showCameraControlsDialog();
        } else if (id == R.id.action_device) {
            showDeviceListDialog();
        } else if (id == R.id.action_safely_eject) {
            safelyEject();
        } else if (id == R.id.action_settings) {
        } else if (id == R.id.action_video_format) {
            showVideoFormatDialog();
        } else if (id == R.id.action_rotate_90_CW) {
            rotateBy(90);
        } else if (id == R.id.action_rotate_90_CCW) {
            rotateBy(-90);
        } else if (id == R.id.action_flip_horizontally) {
            flipHorizontally();
        } else if (id == R.id.action_flip_vertically) {
            flipVertically();
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mIsCameraConnected) {
            menu.findItem(R.id.action_control).setVisible(true);
            menu.findItem(R.id.action_safely_eject).setVisible(true);
            menu.findItem(R.id.action_video_format).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(true);
            menu.findItem(R.id.action_flip_horizontally).setVisible(true);
            menu.findItem(R.id.action_flip_vertically).setVisible(true);
        } else {
            menu.findItem(R.id.action_control).setVisible(false);
            menu.findItem(R.id.action_safely_eject).setVisible(false);
            menu.findItem(R.id.action_video_format).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(false);
            menu.findItem(R.id.action_flip_horizontally).setVisible(false);
            menu.findItem(R.id.action_flip_vertically).setVisible(false);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void setListeners() {
        mBinding.fabPicture.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .request((permissions, all) -> {
                        takePicture();
                    });
        });

        mBinding.fabVideo.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request((permissions, all) -> {
                        toggleVideoRecord(!mIsRecording);
                    });
        });

        mBinding.seekClipDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyDurationSelection(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        mBinding.seekClipDuration.setProgress(0);

        View.OnClickListener durationListener = v -> {
            int index = 0;
            if (v == mBinding.btnDuration60) index = 1;
            else if (v == mBinding.btnDuration90) index = 2;
            else if (v == mBinding.btnDuration120) index = 3;
            mBinding.seekClipDuration.setProgress(index);
            applyDurationSelection(index);
        };
        mBinding.btnDuration30.setOnClickListener(durationListener);
        mBinding.btnDuration60.setOnClickListener(durationListener);
        mBinding.btnDuration90.setOnClickListener(durationListener);
        mBinding.btnDuration120.setOnClickListener(durationListener);

        mBinding.btnAperture.setOnClickListener(v -> showCameraControlsDialog());
        mBinding.btnSettings.setOnClickListener(v -> showSettingsMenu());

        mBinding.seekClipBitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mVideoBitrateMbps = Math.max(MIN_BITRATE_MBPS,
                        Math.min(MAX_BITRATE_MBPS, progress + MIN_BITRATE_MBPS));
                mBinding.tvClipBitrate.setText(mVideoBitrateMbps + " Mbps");
                if (mClipBufferManager != null) {
                    mClipBufferManager.setVideoBitrateBps(mVideoBitrateMbps * 1024 * 1024);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // The rolling buffer applies the new bitrate when the next segment starts.
            }
        });
        // 6 Mbps is the default balance of quality and file size.
        mBinding.seekClipBitrate.setProgress(mVideoBitrateMbps - MIN_BITRATE_MBPS);

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
            Toast.makeText(this, "Saving clip...", Toast.LENGTH_SHORT).show();
            mClipBufferManager.clipNow(new ClipBufferManager.ClipCallback() {
                @Override
                public void onClipSaved(java.io.File outputFile) {
                    mIsSavingClip = false;
                    mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                    Toast.makeText(MainActivity.this, "Clip saved: " + outputFile.getName(), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onClipFailed(String reason) {
                    mIsSavingClip = false;
                    mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                    Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void applyDurationSelection(int index) {
        int safe = Math.max(0, Math.min(index, CLIP_DURATIONS_SECONDS.length - 1));
        int seconds = CLIP_DURATIONS_SECONDS[safe];
        mBinding.tvClipDuration.setText(seconds + "s clip");
        mBinding.btnDuration30.setSelected(safe == 0);
        mBinding.btnDuration60.setSelected(safe == 1);
        mBinding.btnDuration90.setSelected(safe == 2);
        mBinding.btnDuration120.setSelected(safe == 3);
        if (mClipBufferManager != null) {
            mClipBufferManager.setClipDurationSeconds(seconds);
        }
        if (mBinding.tvBufferNumber != null) {
            mBinding.tvBufferNumber.setText(String.valueOf(seconds));
        }
    }

    private void showSettingsMenu() {
        final String[] items = {"Quality & Format", "Bitrate", "Resolution", "Gallery"};
        showNeonMenu("SETTINGS", "CAPTURE CLIPPER", items, which -> {
            if (which == 0) {
                showVideoFormatDialog();
            } else if (which == 1) {
                showBitrateMenu();
            } else if (which == 2) {
                showVideoFormatDialog();
            } else {
                openGallery();
            }
        });
    }

    private void showBitrateMenu() {
        final String[] values = {"1 Mbps", "6 Mbps", "12 Mbps", "25 Mbps", "50 Mbps", "100 Mbps", "150 Mbps", "250 Mbps"};
        showNeonChoiceMenu("BITRATE", "ROLLING BUFFER ENCODER", values, bitrateChoiceIndex(), which -> {
            int[] mbps = {1, 6, 12, 25, 50, 100, 150, 250};
            mVideoBitrateMbps = mbps[which];
            mBinding.seekClipBitrate.setProgress(mVideoBitrateMbps - MIN_BITRATE_MBPS);
            if (mClipBufferManager != null) {
                mClipBufferManager.setVideoBitrateBps(mVideoBitrateMbps * 1024 * 1024);
            }
            applyVideoCaptureConfig();
        });
    }

    private void showNeonMenu(String title, String subtitle, String[] items,
                              android.content.DialogInterface.OnClickListener listener) {
        LinearLayout root = createNeonDialogRoot(title, subtitle);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView row = createNeonRow(items[i], false);
            row.setOnClickListener(v -> {
                listener.onClick(null, index);
                ((AlertDialog) v.getTag()).dismiss();
            });
            root.addView(row);
        }
        showNeonDialog(root);
    }

    private void showNeonChoiceMenu(String title, String subtitle, String[] items, int selected,
                                    android.content.DialogInterface.OnClickListener listener) {
        LinearLayout root = createNeonDialogRoot(title, subtitle);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView row = createNeonRow(items[i], i == selected);
            row.setOnClickListener(v -> {
                listener.onClick(dialogHolder[0], index);
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            });
            root.addView(row);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(d -> styleNeonDialog(dialog));
        dialog.show();
        styleNeonDialog(dialog);
        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).setTag(dialog);
        }
    }

    private LinearLayout createNeonDialogRoot(String title, String subtitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(18));
        root.setBackground(neonPanelBackground());

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setLetterSpacing(0.08f);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(Color.rgb(0, 229, 255));
        sub.setTextSize(10);
        sub.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(4);
        subParams.bottomMargin = dp(12);
        root.addView(sub, subParams);
        return root;
    }

    private TextView createNeonRow(String text, boolean selected) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(Color.WHITE);
        row.setTextSize(15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinHeight(dp(52));
        row.setBackground(neonRowBackground(selected));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(7);
        row.setLayoutParams(params);
        return row;
    }

    private GradientDrawable neonPanelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(8, 10, 20));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.rgb(92, 59, 181));
        return bg;
    }

    private GradientDrawable neonRowBackground(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? Color.rgb(35, 24, 68) : Color.rgb(16, 18, 30));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(selected ? 2 : 1), selected ? Color.rgb(0, 229, 255) : Color.rgb(54, 42, 91));
        return bg;
    }

    private void showNeonDialog(LinearLayout root) {
        final AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).setTag(dialog);
        }
        dialog.setOnShowListener(d -> styleNeonDialog(dialog));
        dialog.show();
        styleNeonDialog(dialog);
    }

    private void styleNeonDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams lp = window.getAttributes();
        lp.dimAmount = 0.72f;
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
        window.setAttributes(lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType("video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No video gallery is installed.", Toast.LENGTH_SHORT).show();
        }
    }

    private int bitrateChoiceIndex() {
        int[] mbps = {1, 6, 12, 25, 50, 100, 150, 250};
        int best = 0;
        for (int i = 0; i < mbps.length; i++) {
            if (mbps[i] == mVideoBitrateMbps) return i;
        }
        return best;
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
            mClipBufferManager.setClipDurationSeconds(
                    CLIP_DURATIONS_SECONDS[mBinding.seekClipDuration.getProgress()]);
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

                mBinding.fabPicture.setVisibility(View.GONE);
                mBinding.fabVideo.setVisibility(View.VISIBLE);
                mBinding.durationSelector.setVisibility(View.VISIBLE);
                mBinding.btnAperture.setVisibility(View.VISIBLE);
                mBinding.btnSettings.setVisibility(View.VISIBLE);
                mBinding.tvConnection.setVisibility(View.VISIBLE);
                mBinding.tvBufferNumber.setVisibility(View.VISIBLE);
                mBinding.tvClipDuration.setVisibility(View.GONE);
                mBinding.seekClipDuration.setVisibility(View.GONE);
                mBinding.tvClipBitrate.setVisibility(View.GONE);
                mBinding.seekClipBitrate.setVisibility(View.GONE);
                mBinding.tvClipStatus.setVisibility(View.GONE);
                mBinding.btnClipNow.setVisibility(View.VISIBLE);
                mBinding.btnClipNow.setEnabled(hasEnoughStorage());
                applyDurationSelection(mBinding.seekClipDuration.getProgress());

                // Update record button
                int colorId = R.color.WHITE;
                if (mIsRecording) {
                    colorId = R.color.RED;
                }
                ColorStateList colorStateList = ColorStateList.valueOf(getResources().getColor(colorId));
                mBinding.fabVideo.setSupportImageTintList(colorStateList);

            } else {
                mBinding.viewMainPreview.setVisibility(View.GONE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.VISIBLE);

                mBinding.fabPicture.setVisibility(View.GONE);
                mBinding.fabVideo.setVisibility(View.GONE);
                mBinding.durationSelector.setVisibility(View.GONE);
                mBinding.btnAperture.setVisibility(View.GONE);
                mBinding.btnSettings.setVisibility(View.GONE);
                mBinding.tvConnection.setVisibility(View.GONE);
                mBinding.tvBufferNumber.setVisibility(View.GONE);
                mBinding.tvClipDuration.setVisibility(View.GONE);
                mBinding.seekClipDuration.setVisibility(View.GONE);
                mBinding.tvClipBitrate.setVisibility(View.GONE);
                mBinding.seekClipBitrate.setVisibility(View.GONE);
                mBinding.tvClipStatus.setVisibility(View.GONE);
                mBinding.btnClipNow.setVisibility(View.GONE);
                mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            }
            invalidateOptionsMenu();
        });
    }

    private void updateClipStatus() {
        if (mBinding == null || !mIsCameraConnected || mClipBufferManager == null) return;

        int buffered = mClipBufferManager.getBufferedSeconds();
        int target = mClipBufferManager.getClipDurationSeconds();
        mBinding.tvBufferNumber.setText(String.valueOf(target));

        if (!hasEnoughStorage()) {
            mBinding.tvClipStatus.setText("Low storage — free space to save clips");
            mBinding.btnClipNow.setEnabled(false);
        } else if (!mIsSavingClip && !mBinding.btnClipNow.isEnabled()) {
            mBinding.btnClipNow.setEnabled(true);
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
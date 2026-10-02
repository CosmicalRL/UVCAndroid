package com.herohan.uvcapp.fragment;

import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.herohan.uvcapp.R;
import com.herohan.uvcapp.activity.ClipBufferManager;
import com.herohan.uvcapp.databinding.DialogSettingsBinding;
import com.herohan.uvcapp.utils.SaveHelper;

import java.io.File;

public class SettingsDialogFragment extends DialogFragment {
    private final int[] durations = {30,60,90,120};
    private final int bitrate;
    private final int duration;
    private final ClipBufferManager bufferManager;
    private DialogSettingsBinding binding;
    private OnSettingsChangedListener listener;

    public SettingsDialogFragment(int bitrate, int duration, ClipBufferManager bufferManager) {
        this.bitrate = bitrate;
        this.duration = duration;
        this.bufferManager = bufferManager;
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogSettingsBinding.inflate(getLayoutInflater());
        binding.seekSettingsBitrate.setProgress(Math.max(0, Math.min(249, bitrate - 1)));
        binding.tvSettingsBitrate.setText(bitrate + " Mbps");
        binding.seekSettingsBitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                binding.tvSettingsBitrate.setText((p + 1) + " Mbps");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        selectDuration(duration);
        View.OnClickListener durationListener = v -> {
            int selected = v == binding.settingsDuration30 ? 30 : v == binding.settingsDuration60 ? 60 : v == binding.settingsDuration90 ? 90 : 120;
            selectDuration(selected);
        };
        binding.settingsDuration30.setOnClickListener(durationListener);
        binding.settingsDuration60.setOnClickListener(durationListener);
        binding.settingsDuration90.setOnClickListener(durationListener);
        binding.settingsDuration120.setOnClickListener(durationListener);

        File cache = new File(requireContext().getCacheDir(), "clip_buffer");
        binding.tvStorageUsed.setText("Storage used: " + formatBytes(folderSize(cache)));
        binding.btnClearBuffer.setOnClickListener(v -> {
            int deleted = clearFolder(cache);
            binding.tvStorageUsed.setText("Storage used: " + formatBytes(folderSize(cache)));
            Toast.makeText(requireContext(), "Cleared " + deleted + " buffer segment" + (deleted == 1 ? "" : "s"), Toast.LENGTH_SHORT).show();
        });
        SaveHelper.checkBaseStoragePath();
        binding.tvSaveLocation.setText("Save location: " + SaveHelper.BaseStoragePath);
        try {
            PackageInfo info = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0);
            binding.tvVersion.setText("Version: " + info.versionName);
        } catch (Exception e) {
            binding.tvVersion.setText("Version: —");
        }

        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle("Settings")
                .setView(binding.getRoot())
                .setPositiveButton("Done", (d,w) -> {
                    if (listener != null) listener.onSettingsChanged(
                            binding.seekSettingsBitrate.getProgress() + 1, selectedDuration());
                })
                .setNegativeButton("Cancel", null)
                .create();
        return dialog;
    }

    private void selectDuration(int seconds) {
        binding.settingsDuration30.setSelected(seconds == 30);
        binding.settingsDuration60.setSelected(seconds == 60);
        binding.settingsDuration90.setSelected(seconds == 90);
        binding.settingsDuration120.setSelected(seconds == 120);
    }

    private int selectedDuration() {
        if (binding.settingsDuration120.isSelected()) return 120;
        if (binding.settingsDuration90.isSelected()) return 90;
        if (binding.settingsDuration60.isSelected()) return 60;
        return 30;
    }

    private int clearFolder(File dir) {
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles();
        int count = 0;
        if (files != null) for (File file : files) {
            if (file.isFile() && file.delete()) count++;
        }
        return count;
    }

    private long folderSize(File dir) {
        if (!dir.exists()) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) total += file.length();
        return total;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024d / 1024d);
        return String.format(java.util.Locale.US, "%.1f GB", bytes / 1024d / 1024d / 1024d);
    }

    public void setOnSettingsChangedListener(OnSettingsChangedListener listener) { this.listener = listener; }
    public interface OnSettingsChangedListener { void onSettingsChanged(int bitrateMbps, int durationSeconds); }
}
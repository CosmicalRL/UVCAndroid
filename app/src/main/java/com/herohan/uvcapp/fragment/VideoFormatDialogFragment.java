package com.herohan.uvcapp.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.serenegiant.usb.Format;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.databinding.FragmentVideoFormatBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class VideoFormatDialogFragment extends DialogFragment {
    private static final String RESOLUTION_SEPARATOR = "x";
    private List<Format> mFormatList;
    private Size mSize;
    private LinkedHashMap<Integer, String> mTypeAndNameMap = new LinkedHashMap<>();
    private LinkedHashMap<Integer, LinkedHashMap<String, List<Integer>>> mTypeAndResolutionMap = new LinkedHashMap<>();
    private FragmentVideoFormatBinding mBinding;
    private OnVideoFormatSelectListener mOnVideoFormatSelectListener;

    public VideoFormatDialogFragment(List<Format> formatList, Size size) {
        mFormatList = formatList;
        mSize = size.clone();
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mBinding = FragmentVideoFormatBinding.inflate(getLayoutInflater());
        buildDeviceOptions();
        updateFormatButtons();
        updateResolutionOptions();
        updateFrameRateOptions();
        mBinding.rowResolution.setOnClickListener(v -> toggle(mBinding.resolutionOptions));
        mBinding.rowFrameRate.setOnClickListener(v -> toggle(mBinding.frameRateOptions));
        mBinding.btnFormatMjpeg.setOnClickListener(v -> selectFormat(UVCCamera.UVC_VS_FRAME_MJPEG));
        mBinding.btnFormatYuyv.setOnClickListener(v -> selectFormat(UVCCamera.UVC_VS_FRAME_UNCOMPRESSED));

        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setView(mBinding.getRoot())
                .setPositiveButton("Apply", (d,w) -> {
                    if (mOnVideoFormatSelectListener != null) mOnVideoFormatSelectListener.onFormatSelect(mSize);
                })
                .setNegativeButton("Cancel", null)
                .create();
        return dialog;
    }

    private void buildDeviceOptions() {
        for (Format format : mFormatList) {
            int type = -1;
            if (format.type == UVCCamera.UVC_VS_FORMAT_UNCOMPRESSED) type = UVCCamera.UVC_VS_FRAME_UNCOMPRESSED;
            else if (format.type == UVCCamera.UVC_VS_FORMAT_MJPEG) type = UVCCamera.UVC_VS_FRAME_MJPEG;
            if (type < 0) continue;
            mTypeAndNameMap.put(type, type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : "YUYV");
            LinkedHashMap<String, List<Integer>> map = mTypeAndResolutionMap.get(type);
            if (map == null) { map = new LinkedHashMap<>(); mTypeAndResolutionMap.put(type, map); }
            for (Format.Descriptor descriptor : format.frameDescriptors) {
                List<Integer> fps = new ArrayList<>();
                for (Format.Interval interval : descriptor.intervals) fps.add(interval.fps);
                map.put(descriptor.width + RESOLUTION_SEPARATOR + descriptor.height, fps);
            }
        }
        if (!mTypeAndResolutionMap.containsKey(mSize.type) && !mTypeAndResolutionMap.isEmpty()) {
            mSize.type = mTypeAndResolutionMap.keySet().iterator().next();
        }
    }

    private void selectFormat(int type) {
        if (!mTypeAndResolutionMap.containsKey(type)) return;
        mSize.type = type;
        updateFormatButtons();
        updateResolutionOptions();
        updateFrameRateOptions();
    }

    private void updateFormatButtons() {
        boolean mjpeg = mSize.type == UVCCamera.UVC_VS_FRAME_MJPEG;
        mBinding.btnFormatMjpeg.setEnabled(mTypeAndResolutionMap.containsKey(UVCCamera.UVC_VS_FRAME_MJPEG));
        mBinding.btnFormatYuyv.setEnabled(mTypeAndResolutionMap.containsKey(UVCCamera.UVC_VS_FRAME_UNCOMPRESSED));
        mBinding.btnFormatMjpeg.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mjpeg ? 0xFF7F5AF0 : 0xFF1A1033));
        mBinding.btnFormatYuyv.setBackgroundTintList(android.content.res.ColorStateList.valueOf(!mjpeg ? 0xFF7F5AF0 : 0xFF1A1033));
    }

    private void updateResolutionOptions() {
        LinkedHashMap<String, List<Integer>> map = mTypeAndResolutionMap.get(mSize.type);
        if (map == null || map.isEmpty()) return;
        String current = mSize.width + RESOLUTION_SEPARATOR + mSize.height;
        if (!map.containsKey(current)) {
            current = map.keySet().iterator().next();
            String[] p = current.split(RESOLUTION_SEPARATOR);
            mSize.width = Integer.parseInt(p[0]); mSize.height = Integer.parseInt(p[1]);
        }
        mBinding.tvResolutionValue.setText(current);
        mBinding.resolutionOptions.removeAllViews();
        for (String resolution : map.keySet()) {
            addOption(mBinding.resolutionOptions, resolution, () -> {
                String[] p = resolution.split(RESOLUTION_SEPARATOR);
                mSize.width = Integer.parseInt(p[0]); mSize.height = Integer.parseInt(p[1]);
                mBinding.tvResolutionValue.setText(resolution);
                mBinding.resolutionOptions.setVisibility(View.GONE);
                updateFrameRateOptions();
            });
        }
    }

    private void updateFrameRateOptions() {
        LinkedHashMap<String, List<Integer>> map = mTypeAndResolutionMap.get(mSize.type);
        if (map == null) return;
        List<Integer> fps = map.get(mSize.width + RESOLUTION_SEPARATOR + mSize.height);
        if (fps == null || fps.isEmpty()) return;
        if (!fps.contains(mSize.fps)) mSize.fps = fps.get(0);
        mBinding.tvFrameRateValue.setText(mSize.fps + " fps");
        mBinding.frameRateOptions.removeAllViews();
        for (Integer rate : fps) {
            addOption(mBinding.frameRateOptions, rate + " fps", () -> {
                mSize.fps = rate;
                mBinding.tvFrameRateValue.setText(rate + " fps");
                mBinding.frameRateOptions.setVisibility(View.GONE);
            });
        }
    }

    private void addOption(android.widget.LinearLayout parent, String text, Runnable action) {
        TextView option = new TextView(requireContext());
        option.setText(text);
        option.setTextColor(Color.WHITE);
        option.setTextSize(14);
        option.setGravity(android.view.Gravity.CENTER_VERTICAL);
        option.setPadding(16, 0, 16, 0);
        option.setMinHeight(48);
        option.setBackgroundColor(0xFF1A1033);
        option.setOnClickListener(v -> action.run());
        parent.addView(option);
    }

    private void toggle(View view) {
        view.setVisibility(view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    public void setOnVideoFormatSelectListener(OnVideoFormatSelectListener listener) { mOnVideoFormatSelectListener = listener; }
    public interface OnVideoFormatSelectListener { void onFormatSelect(Size size); }
}
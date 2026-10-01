/*
 * UVCAndroid rolling 30-second clip buffer.
 *
 * The camera is continuously recorded into short MP4 segments. Clip Now
 * stops the current segment, then muxes the newest segments into one MP4.
 */
package com.herohan.uvcapp.activity;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.herohan.uvcapp.VideoCapture;
import com.herohan.uvcapp.utils.SaveHelper;
import com.herohan.uvcapp.ICameraHelper;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Maintains a rolling video buffer made from 10-second MP4 segments.
 *
 * This intentionally uses the existing VideoCapture/MediaMuxer pipeline instead
 * of adding another encoder to the UVC camera. Android's MediaMuxer requires
 * encoded samples to be written in chronological order, so segments are muxed
 * in their original order with timestamps rebased to zero.
 */
public final class ClipBufferManager {
    private static final String TAG = "ClipBufferManager";
    private static final long SEGMENT_MS = 10_000L;
    private static final int MAX_SEGMENTS = 4;
    private static final int CLIP_SEGMENTS = 3;

    public interface ClipCallback {
        void onClipSaved(File outputFile);
        void onClipFailed(String reason);
    }

    private final ICameraHelper cameraHelper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService muxExecutor =
            Executors.newSingleThreadExecutor();

    private final Deque<File> segments = new ArrayDeque<>();

    private volatile boolean running;
    private volatile boolean stoppingForClip;
    private ScheduledFuture<?> rotateFuture;
    private File currentSegment;

    public ClipBufferManager(android.content.Context context, ICameraHelper cameraHelper) {
        this.cameraHelper = cameraHelper;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        stoppingForClip = false;
        startSegment();
    }

    public synchronized void stop() {
        running = false;
        stoppingForClip = false;

        if (rotateFuture != null) {
            rotateFuture.cancel(false);
            rotateFuture = null;
        }

        if (cameraHelper != null && cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
        }

        clearSegments();
    }

    public synchronized void clipNow(@NonNull ClipCallback callback) {
        if (!running) {
            mainHandler.post(() -> callback.onClipFailed("Clip buffer is not running"));
            return;
        }

        if (stoppingForClip) {
            mainHandler.post(() -> callback.onClipFailed("Clip is already being saved"));
            return;
        }

        stoppingForClip = true;
        if (rotateFuture != null) {
            rotateFuture.cancel(false);
            rotateFuture = null;
        }

        // Finish the current segment first so the newest footage is included.
        if (cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
        } else {
            buildClip(callback);
        }
    }

    private synchronized void startSegment() {
        if (!running || stoppingForClip || cameraHelper.isRecording()) {
            return;
        }

        File output = new File(SaveHelper.getSaveVideoPath());
        currentSegment = output;

        VideoCapture.OutputFileOptions options =
                new VideoCapture.OutputFileOptions.Builder(output).build();

        cameraHelper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
                synchronized (ClipBufferManager.this) {
                    if (!running) {
                        cameraHelper.stopRecording();
                        return;
                    }

                    if (stoppingForClip) {
                        cameraHelper.stopRecording();
                        return;
                    }

                    rotateFuture = scheduler.schedule(() -> {
                        if (running && !stoppingForClip && cameraHelper.isRecording()) {
                            cameraHelper.stopRecording();
                        }
                    }, SEGMENT_MS, TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void onVideoSaved(
                    @NonNull VideoCapture.OutputFileResults outputFileResults) {
                synchronized (ClipBufferManager.this) {
                    File saved = currentSegment;
                    currentSegment = null;

                    if (saved != null && saved.exists() && saved.length() > 0) {
                        segments.addLast(saved);
                        while (segments.size() > MAX_SEGMENTS) {
                            File old = segments.removeFirst();
                            safeDelete(old);
                        }
                    }

                    if (stoppingForClip) {
                        buildClip(null);
                    } else if (running) {
                        startSegment();
                    }
                }
            }

            @Override
            public void onError(int error, @NonNull String message, Throwable cause) {
                synchronized (ClipBufferManager.this) {
                    currentSegment = null;
                    Log.e(TAG, "Rolling segment failed: " + message, cause);

                    if (stoppingForClip) {
                        stoppingForClip = false;
                        if (running) {
                            startSegment();
                        }
                    } else if (running) {
                        // Give the camera a short recovery window before retrying.
                        scheduler.schedule(() -> {
                            synchronized (ClipBufferManager.this) {
                                if (running && !stoppingForClip && !cameraHelper.isRecording()) {
                                    startSegment();
                                }
                            }
                        }, 500, TimeUnit.MILLISECONDS);
                    }
                }
            }
        });
    }

    private void buildClip(ClipCallback requestedCallback) {
        final List<File> input;
        synchronized (this) {
            input = new ArrayList<>(segments);
            stoppingForClip = false;

            // The current segment was just added by onVideoSaved.
            int from = Math.max(0, input.size() - CLIP_SEGMENTS);
            if (input.size() > CLIP_SEGMENTS) {
                input.subList(0, from).clear();
            }
        }

        if (input.isEmpty()) {
            if (requestedCallback != null) {
                mainHandler.post(() ->
                        requestedCallback.onClipFailed("No video has been buffered yet"));
            }
            synchronized (this) {
                if (running) {
                    startSegment();
                }
            }
            return;
        }

        final File output = new File(SaveHelper.getSaveVideoPath());

        muxExecutor.execute(() -> {
            try {
                muxSegments(input, output);

                // Remove source segments only after the clip is safely written.
                synchronized (ClipBufferManager.this) {
                    for (File segment : input) {
                        segments.remove(segment);
                        safeDelete(segment);
                    }
                }

                if (requestedCallback != null) {
                    mainHandler.post(() -> requestedCallback.onClipSaved(output));
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to create clip", e);
                safeDelete(output);
                if (requestedCallback != null) {
                    String message = e.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "Unable to create clip";
                    }
                    final String error = message;
                    mainHandler.post(() -> requestedCallback.onClipFailed(error));
                }
            } finally {
                synchronized (ClipBufferManager.this) {
                    if (running && !cameraHelper.isRecording()) {
                        startSegment();
                    }
                }
            }
        });
    }

    private static void muxSegments(List<File> inputFiles, File output) throws IOException {
        if (inputFiles.isEmpty()) {
            throw new IOException("No segments available");
        }

        MediaMuxer muxer = null;
        boolean started = false;
        long timestampOffsetUs = 0L;

        try {
            MediaFormat videoFormat = null;

            // All segments are generated by the same VideoCapture configuration.
            for (File file : inputFiles) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(file.getAbsolutePath());
                    int track = findVideoTrack(extractor);
                    if (track >= 0) {
                        videoFormat = extractor.getTrackFormat(track);
                        break;
                    }
                } finally {
                    extractor.release();
                }
            }

            if (videoFormat == null) {
                throw new IOException("No video track found in buffered segments");
            }

            muxer = new MediaMuxer(
                    output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            for (File file : inputFiles) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(file.getAbsolutePath());
                    int track = findVideoTrack(extractor);
                    if (track < 0) {
                        continue;
                    }

                    extractor.selectTrack(track);
                    long firstPts = -1L;

                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            break;
                        }

                        long pts = extractor.getSampleTime();
                        if (pts < 0) {
                            break;
                        }

                        if (firstPts < 0) {
                            firstPts = pts;
                        }

                        long relativePts = Math.max(0L, pts - firstPts);
                        info.set(
                                0,
                                size,
                                timestampOffsetUs + relativePts,
                                extractor.getSampleFlags());

                        buffer.position(0);
                        buffer.limit(size);
                        muxer.writeSampleData(videoTrack, buffer, info);

                        extractor.advance();
                    }

                    if (firstPts >= 0) {
                        long duration = Math.max(0L, extractor.getSampleTime() - firstPts);
                        if (duration > 0) {
                            timestampOffsetUs += duration;
                        } else {
                            // Fallback for the last sample when the extractor is exhausted.
                            timestampOffsetUs += 10_000_000L;
                        }
                    }
                } finally {
                    extractor.release();
                }
            }
        } finally {
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }
                muxer.release();
            }
        }
    }

    private static int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private synchronized void clearSegments() {
        for (File segment : segments) {
            safeDelete(segment);
        }
        segments.clear();

        if (currentSegment != null) {
            safeDelete(currentSegment);
            currentSegment = null;
        }
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }
}

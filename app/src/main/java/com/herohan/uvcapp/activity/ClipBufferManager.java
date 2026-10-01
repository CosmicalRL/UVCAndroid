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
import android.media.MediaScannerConnection;

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
    private static final int MAX_SEGMENTS = 12;
    private static final int MIN_CLIP_SECONDS = 30;
    private static final int MAX_CLIP_SECONDS = 120;
    private static final int DEFAULT_BITRATE_BPS = 6 * 1024 * 1024;
    private static final int MIN_BITRATE_BPS = 1 * 1024 * 1024;
    private static final int MAX_BITRATE_BPS = 250 * 1024 * 1024;

    private volatile int clipDurationSeconds = MIN_CLIP_SECONDS;
    private volatile int videoBitrateBps = DEFAULT_BITRATE_BPS;

    public interface ClipCallback {
        void onClipSaved(File outputFile);
        void onClipFailed(String reason);
    }

    private final ICameraHelper cameraHelper;
    private final android.content.Context context;
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
    private ClipCallback pendingClipCallback;

    public ClipBufferManager(android.content.Context context, ICameraHelper cameraHelper) {
        this.context = context.getApplicationContext();
        this.cameraHelper = cameraHelper;
    }

    public synchronized void setClipDurationSeconds(int seconds) {
        int clamped = Math.max(MIN_CLIP_SECONDS, Math.min(MAX_CLIP_SECONDS, seconds));
        // Keep the duration aligned to the 10-second rolling segment size.
        clipDurationSeconds = ((clamped + 5) / 10) * 10;
    }

    public int getClipDurationSeconds() {
        return clipDurationSeconds;
    }

    public synchronized void setVideoBitrateBps(int bitrateBps) {
        videoBitrateBps = Math.max(MIN_BITRATE_BPS, Math.min(MAX_BITRATE_BPS, bitrateBps));
    }

    public int getVideoBitrateBps() {
        return videoBitrateBps;
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
        pendingClipCallback = callback;
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

        // ClipBufferManager never needs microphone audio because the final clip mux is
        // video-only. Disabling it also makes segment stop/save operations much lighter.
        cameraHelper.setVideoCaptureConfig(
                cameraHelper.getVideoCaptureConfig()
                        .setBitRate(videoBitrateBps)
                        .setAudioCaptureEnable(false));

        File output = createTemporarySegmentFile();
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
                        buildClip(pendingClipCallback);
                        pendingClipCallback = null;
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
            List<File> available = new ArrayList<>(segments);

            // The current segment was just added by onVideoSaved.
            int clipSegments = Math.max(1, clipDurationSeconds / 10);
            int from = Math.max(0, available.size() - clipSegments);
            input = new ArrayList<>(available.subList(from, available.size()));

            // Detach the selected source files from the rolling deque before restarting
            // recording. This prevents the rolling cleanup from deleting a file while it
            // is being muxed in the background.
            for (File segment : input) {
                segments.remove(segment);
            }
            stoppingForClip = false;

            // Resume the rolling buffer immediately. The MP4 join happens off-thread.
            if (running) {
                startSegment();
            }
        }

        if (input.isEmpty()) {
            if (requestedCallback != null) {
                mainHandler.post(() ->
                        requestedCallback.onClipFailed("No video has been buffered yet"));
            }
            return;
        }

        final File output = createUniqueClipFile(input);

        muxExecutor.execute(() -> {
            try {
                muxSegments(input, output);

                for (File segment : input) {
                    safeDelete(segment);
                }

                if (requestedCallback != null) {
                    // Force Gallery/Photos to notice the newly-created file instead of
                    // waiting for a later background media scan.
                    MediaScannerConnection.scanFile(
                            context,
                            new String[]{output.getAbsolutePath()},
                            new String[]{"video/mp4"},
                            (path, uri) -> mainHandler.post(() -> requestedCallback.onClipSaved(output)));
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to create clip", e);
                safeDelete(output);
                for (File segment : input) {
                    safeDelete(segment);
                }
                if (requestedCallback != null) {
                    String message = e.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "Unable to create clip";
                    }
                    final String error = message;
                    mainHandler.post(() -> requestedCallback.onClipFailed(error));
                }
            }
        });
    }

    private File createTemporarySegmentFile() {
        File bufferDir = new File(context.getCacheDir(), "clip_buffer");
        if (!bufferDir.exists() && !bufferDir.mkdirs() && !bufferDir.exists()) {
            Log.w(TAG, "Could not create clip buffer directory: " + bufferDir);
        }

        return new File(bufferDir, "segment_" + System.currentTimeMillis() + "_" +
                Integer.toHexString(System.identityHashCode(this)) + ".mp4");
    }

    private static File createUniqueClipFile(List<File> inputFiles) {
        File candidate = new File(SaveHelper.getSaveVideoPath());

        // SaveHelper uses second-level timestamps. Make the final Clip Now
        // output unique if a file with that timestamp already exists.
        if (candidate.exists()) {
            String path = candidate.getAbsolutePath();
            int dot = path.lastIndexOf('.');
            String base = dot > 0 ? path.substring(0, dot) : path;
            String extension = dot > 0 ? path.substring(dot) : ".mp4";
            candidate = new File(base + "_clip_" + System.currentTimeMillis() + extension);
        }

        return candidate;
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

            long lastOutputPts = -1L;
            int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 25;
            long frameDurationUs = Math.max(1L, 1_000_000L / Math.max(1, frameRate));

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
                    long lastPts = -1L;
                    boolean segmentStarted = false;

                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            break;
                        }

                        long pts = extractor.getSampleTime();
                        if (pts < 0) {
                            break;
                        }

                        int flags = extractor.getSampleFlags();

                        // Every joined segment must begin on a keyframe. Starting on a
                        // predicted frame can make the final MP4 appear frozen until the
                        // decoder reaches the next sync frame.
                        if (!segmentStarted
                                && (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                            extractor.advance();
                            continue;
                        }

                        if (firstPts < 0) {
                            firstPts = pts;
                            segmentStarted = true;
                        }

                        long relativePts = Math.max(0L, pts - firstPts);
                        long outputPts = timestampOffsetUs + relativePts;
                        if (lastOutputPts >= 0 && outputPts <= lastOutputPts) {
                            outputPts = lastOutputPts + 1L;
                        }

                        info.set(0, size, outputPts, flags);
                        buffer.position(0);
                        buffer.limit(size);
                        muxer.writeSampleData(videoTrack, buffer, info);
                        lastOutputPts = outputPts;
                        lastPts = pts;

                        extractor.advance();
                    }

                    if (segmentStarted && lastPts >= firstPts) {
                        // Advance by one frame beyond the final sample so the next
                        // segment starts strictly after this one.
                        timestampOffsetUs += (lastPts - firstPts) + frameDurationUs;
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

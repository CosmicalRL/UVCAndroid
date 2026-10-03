/*
 * UVCAndroid rolling 30-second clip buffer.
 *
 * The camera is continuously recorded into 1-second MP4 segments. Clip Now
 * stops the current segment, then muxes the newest segments into one MP4.
 */
package com.herohan.uvcapp.activity;

import android.os.Handler;
import android.os.SystemClock;
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
 * Maintains a rolling video buffer made from 1-second MP4 segments.
 *
 * This intentionally uses the existing VideoCapture/MediaMuxer pipeline instead
 * of adding another encoder to the UVC camera. Android's MediaMuxer requires
 * encoded samples to be written in chronological order, so segments are muxed
 * in their original order with timestamps rebased to zero.
 */
public final class ClipBufferManager {
    private static final String TAG = "ClipBufferManager";
    private static final long SEGMENT_MS = 1_000L;
    private static final int MAX_SEGMENTS = 65;
    private static final int MIN_CLIP_SECONDS = 5;
    private static final int MAX_CLIP_SECONDS = 60;
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
    private long currentSegmentStartElapsed;
    private ClipCallback pendingClipCallback;

    // Manual recording temporarily takes over the encoder so the normal Record
    // button and rolling Clip Now buffer cannot fight over the same camera encoder.
    public interface ManualRecordCallback {
        void onStart();
        void onSaved(File outputFile);
        void onError(String message);
    }

    private boolean manualRecordingRequested;
    private boolean manualRecordingActive;
    private File pendingManualOutputFile;
    private ManualRecordCallback pendingManualStartCallback;
    private File manualOutputFile;
    private ManualRecordCallback manualRecordCallback;

    public ClipBufferManager(android.content.Context context, ICameraHelper cameraHelper) {
        this.context = context.getApplicationContext();
        this.cameraHelper = cameraHelper;
    }

    public synchronized void setClipDurationSeconds(int seconds) {
        int clamped = Math.max(MIN_CLIP_SECONDS, Math.min(MAX_CLIP_SECONDS, seconds));
        // 1-second segments allow arbitrary whole-second clip lengths.
        clipDurationSeconds = clamped;
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

    public synchronized int getBufferedSeconds() {
        long bufferedMs = segments.size() * SEGMENT_MS;
        if (cameraHelper.isRecording() && currentSegmentStartElapsed > 0L) {
            bufferedMs += Math.max(0L, SystemClock.elapsedRealtime() - currentSegmentStartElapsed);
        }
        return (int) Math.min(MAX_CLIP_SECONDS, bufferedMs / 1000L);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        stoppingForClip = false;
        cleanupStaleTemporarySegments();
        startSegment();
    }

    public synchronized void stop() {
        running = false;
        stoppingForClip = false;
        manualRecordingRequested = false;
        manualRecordingActive = false;
        pendingManualOutputFile = null;
        pendingManualStartCallback = null;
        manualOutputFile = null;
        manualRecordCallback = null;

        if (rotateFuture != null) {
            rotateFuture.cancel(false);
            rotateFuture = null;
        }

        if (cameraHelper != null && cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
        }

        clearSegments();
    }

    public synchronized void startManualRecording(@NonNull File outputFile, @NonNull ManualRecordCallback callback) {
        if (!running) {
            mainHandler.post(() -> callback.onError("Recorder is not running"));
            return;
        }
        if (manualRecordingRequested || manualRecordingActive) {
            mainHandler.post(() -> callback.onError("A recording is already in progress"));
            return;
        }
        if (stoppingForClip) {
            mainHandler.post(() -> callback.onError("A clip is currently being saved"));
            return;
        }

        manualRecordingRequested = true;
        pendingManualOutputFile = outputFile;
        pendingManualStartCallback = callback;
        if (rotateFuture != null) {
            rotateFuture.cancel(false);
            rotateFuture = null;
        }

        // Finish the current rolling segment cleanly, then hand the encoder to
        // the manual recording. The segment is deliberately not added to the
        // rolling deque because it is immediately followed by the manual take.
        if (cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
        } else {
            startManualCapture(outputFile, callback);
            pendingManualOutputFile = null;
            pendingManualStartCallback = null;
        }
    }

    public synchronized void stopManualRecording() {
        if (manualRecordingRequested && !manualRecordingActive) {
            manualRecordingRequested = false;
            File pending = pendingManualOutputFile;
            pendingManualOutputFile = null;
            ManualRecordCallback callback = pendingManualStartCallback;
            pendingManualStartCallback = null;
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Recording stopped before it started"));
            }
            if (running && !cameraHelper.isRecording()) {
                startSegment();
            }
            return;
        }

        if (manualRecordingActive && cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
        }
    }

    private synchronized void startManualCapture(File outputFile, ManualRecordCallback callback) {
        if (!running) {
            manualRecordingRequested = false;
            mainHandler.post(() -> callback.onError("Recorder is not running"));
            return;
        }

        manualRecordingRequested = false;
        manualRecordingActive = true;
        manualOutputFile = outputFile;
        manualRecordCallback = callback;

        cameraHelper.setVideoCaptureConfig(
                cameraHelper.getVideoCaptureConfig()
                        .setBitRate(videoBitrateBps)
                        .setAudioCaptureEnable(false));

        VideoCapture.OutputFileOptions options =
                new VideoCapture.OutputFileOptions.Builder(outputFile).build();
        cameraHelper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
                ManualRecordCallback cb;
                synchronized (ClipBufferManager.this) {
                    cb = manualRecordCallback;
                }
                if (cb != null) {
                    mainHandler.post(cb::onStart);
                }
            }

            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults outputFileResults) {
                ManualRecordCallback cb;
                File saved;
                synchronized (ClipBufferManager.this) {
                    saved = manualOutputFile;
                    manualOutputFile = null;
                    cb = manualRecordCallback;
                    manualRecordCallback = null;
                    manualRecordingActive = false;
                }

                if (saved != null && saved.exists() && saved.length() > 0) {
                    if (cb != null) {
                        mainHandler.post(() -> cb.onSaved(saved));
                    }
                } else if (cb != null) {
                    mainHandler.post(() -> cb.onError("Recording did not produce a video file"));
                }

                synchronized (ClipBufferManager.this) {
                    if (running && !stoppingForClip && !cameraHelper.isRecording()) {
                        startSegment();
                    }
                }
            }

            @Override
            public void onError(int error, @NonNull String message, Throwable cause) {
                ManualRecordCallback cb;
                synchronized (ClipBufferManager.this) {
                    cb = manualRecordCallback;
                    manualRecordCallback = null;
                    manualOutputFile = null;
                    manualRecordingActive = false;
                }
                Log.e(TAG, "Manual recording failed: " + message, cause);
                if (cb != null) {
                    mainHandler.post(() -> cb.onError(message));
                }
                synchronized (ClipBufferManager.this) {
                    if (running && !stoppingForClip && !cameraHelper.isRecording()) {
                        startSegment();
                    }
                }
            }
        });
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
        if (manualRecordingRequested || manualRecordingActive) {
            mainHandler.post(() -> callback.onClipFailed("Stop the current recording before clipping"));
            return;
        }

        int bufferedSeconds = getBufferedSeconds();
        if (bufferedSeconds <= 0) {
            mainHandler.post(() -> callback.onClipFailed("No video has been buffered yet"));
            return;
        }

        // Clip whatever is currently available. If the user presses Clip Now before
        // the selected duration is full, the clip contains the most recent available
        // footage instead of refusing to save or falling back to an arbitrary segment.
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
                    currentSegmentStartElapsed = SystemClock.elapsedRealtime();

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
                    currentSegmentStartElapsed = 0L;

                    if (manualRecordingRequested && pendingManualOutputFile != null) {
                        // This segment only exists to hand the encoder from the rolling
                        // buffer to the manual recording. It is not part of the buffer
                        // and must not be left behind on disk.
                        safeDelete(saved);
                        File manualFile = pendingManualOutputFile;
                        pendingManualOutputFile = null;
                        ManualRecordCallback callback = pendingManualStartCallback;
                        pendingManualStartCallback = null;
                        if (callback != null) {
                            startManualCapture(manualFile, callback);
                        }
                        return;
                    }

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
                    currentSegmentStartElapsed = 0L;
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
            // With 1-second segments, every requested whole-second duration maps
            // directly to the number of segments to stitch.
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

    private void cleanupStaleTemporarySegments() {
        File bufferDir = new File(context.getCacheDir(), "clip_buffer");
        File[] files = bufferDir.listFiles();
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6);
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) safeDelete(file);
        }
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

        File parent = candidate.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            Log.w(TAG, "Could not create clip output directory: " + parent);
        }

        // SaveHelper uses second-level timestamps. Always add a millisecond
        // suffix so rapid Clip Now presses cannot select the same output path
        // before the first mux operation has created its file.
        String path = candidate.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        String base = dot > 0 ? path.substring(0, dot) : path;
        String extension = dot > 0 ? path.substring(dot) : ".mp4";
        candidate = new File(base + "_clip_" + System.currentTimeMillis() + extension);

        while (candidate.exists()) {
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
                if (file == null || !file.exists() || file.length() <= 0) {
                    continue;
                }
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

            // Tell MediaMuxer the duration of the final sample explicitly. Android's
            // MediaMuxer documentation specifies an empty END_OF_STREAM sample for
            // this purpose. This avoids copying the first 10-second segment's
            // KEY_DURATION metadata into the joined track while keeping the output
            // MP4 valid and giving players the full stitched duration.
            if (lastOutputPts >= 0L) {
                MediaCodec.BufferInfo endInfo = new MediaCodec.BufferInfo();
                endInfo.set(0, 0, lastOutputPts + frameDurationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                ByteBuffer emptyBuffer = ByteBuffer.allocateDirect(0);
                muxer.writeSampleData(videoTrack, emptyBuffer, endInfo);
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

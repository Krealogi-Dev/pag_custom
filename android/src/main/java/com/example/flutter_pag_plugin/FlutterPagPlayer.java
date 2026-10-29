package com.example.flutter_pag_plugin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.LinearInterpolator;

import org.libpag.PAGFile;
import org.libpag.PAGPlayer;
import org.libpag.PAGSurface;

import java.util.HashMap;

import io.flutter.plugin.common.MethodChannel;


public class FlutterPagPlayer extends PAGPlayer {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ValueAnimator animator = ValueAnimator.ofFloat(0.0F, 1.0F);
    private volatile boolean isRelease;
    private long currentPlayTime = 0L;
    private double progress = 0;
    private double initProgress = 0;
    private SurfaceTexture surfaceTexture;

    private MethodChannel channel;
    private long textureId;


    public FlutterPagPlayer() {
        super();
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animatorUpdateListener);
        animator.addListener(animatorListenerAdapter);
    }

    public boolean isRelease() {
        return isRelease;
    }

    public void init(PAGFile file, int repeatCount, double initProgress, MethodChannel channel, long textureId) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                setComposition(file);
            }
        } else {
            setComposition(file);
        }

        this.channel = channel;
        this.textureId = textureId;
        progress = initProgress;
        this.initProgress = initProgress;
        // init() runs on a worker thread, but ValueAnimator is only safe on the main
        // thread (start()/cancel() come from there). Posting keeps FIFO order with the
        // plugin's later handler.post(start/result.success).
        final int count = repeatCount;
        mainHandler.post(() -> {
            if (!isRelease) {
                initAnimator(count);
            }
        });
    }

    private boolean valid() {
        return getSurface() != null && surfaceTexture != null;
    }

    private void initAnimator(int repeatCount) {
        animator.setDuration(duration() / 1000L);
        if (repeatCount < 0) {
            repeatCount = 0;
        }
        animator.setRepeatCount(repeatCount - 1);
        setProgressValue(initProgress);
    }

    public void setProgressValue(double value) {
        if (isRelease) {
            return;
        }
        this.progress = Math.max(0.0D, Math.min(value, 1.0D));
        this.currentPlayTime = (long) (progress * (double) this.animator.getDuration());
        this.animator.setCurrentPlayTime(currentPlayTime);
        safeSetProgress(progress);
        flush();
    }

    // setProgress() touches the native composition, so it must share the lock with
    // flush()/clear()/release() which run on the worker pool.
    private void safeSetProgress(double value) {
        synchronized (this) {
            if (isRelease) {
                return;
            }
            setProgress(value);
        }
    }

    public void start() {
        animator.start();
    }

    public void stop() {
        pause();
        setProgressValue(initProgress);
    }

    @Override
    public void setSurface(PAGSurface pagSurface) {
        super.setSurface(pagSurface);
    }

    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.surfaceTexture = surfaceTexture;
    }

    public void updateBufferSize(int width, int height) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                surfaceTexture.setDefaultBufferSize(width, height);
                getSurface().updateSize();
                getSurface().clearAll();
            }
        } else {
            surfaceTexture.setDefaultBufferSize(width, height);
            getSurface().updateSize();
            getSurface().clearAll();
        }
    }

    public void clear() {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                setComposition(null);
                if (valid()) {
                    getSurface().freeCache();
                    getSurface().clearAll();
                }
            }
        } else {
            setComposition(null);
            if (valid()) {
                getSurface().freeCache();
                getSurface().clearAll();
            }
        }
    }

    public void cancel() {
        animator.cancel();
    }

    public void pause() {
        animator.pause();
    }

    @Override
    public void release() {
        release(null);
    }

    /**
     * @param onReleased runs on the worker thread after the native teardown finished
     *                   (inside no lock), so callers can release dependent resources
     *                   (e.g. the TextureRegistry entry) without racing a flush().
     */
    public void release(final Runnable onReleased) {
        // isRelease is set first (before any teardown) so any flush() call made after
        // release() has been requested is rejected as early as possible.
        isRelease = true;
        animator.cancel();
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        WorkThreadExecutor.getInstance().postInCertainThread(() -> {
            // super.release() and getSurface()/surfaceTexture teardown now share the same
            // lock used by flush(), so a flush() task queued on the (cached, unordered)
            // WorkThreadExecutor thread pool can never run concurrently with this teardown.
            synchronized (this) {
                FlutterPagPlayer.super.release();
                if (getSurface() != null) getSurface().release();
                if (surfaceTexture != null) surfaceTexture.release();
                surfaceTexture = null;
            }
            if (onReleased != null) {
                onReleased.run();
            }
        });
    }

    @Override
    public boolean flush() {
        if (isRelease) {
            return false;
        }
        WorkThreadExecutor.getInstance().postInCertainThread(() -> {
            synchronized (this) {
                // Re-check isRelease *inside* the lock: closes the race where release()
                // runs (and flips isRelease) between the check above and this task
                // actually executing on the thread pool.
                if (isRelease) {
                    return;
                }
                FlutterPagPlayer.super.flush();
            }
        });
        return true;
    }

    // 更新PAG渲染
    private final ValueAnimator.AnimatorUpdateListener animatorUpdateListener = new ValueAnimator.AnimatorUpdateListener() {

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            progress = (double) (Float) animation.getAnimatedValue();
            currentPlayTime = (long) (progress * (double) animator.getDuration());
            safeSetProgress(progress);
            flush();
        }
    };

    // 动画状态监听
    private final AnimatorListenerAdapter animatorListenerAdapter = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animator) {
            super.onAnimationStart(animator);
            notifyEvent(FlutterPagPlugin._eventStart);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            // Align with iOS platform, avoid triggering this method when stopping
            int repeatCount = ((ValueAnimator) animation).getRepeatCount();
            if (repeatCount >= 0 && (animation.getDuration() > 0) &&
                    (currentPlayTime / animation.getDuration() > repeatCount)) {
                notifyEvent(FlutterPagPlugin._eventEnd);
            }
        }

        @Override
        public void onAnimationCancel(Animator animator) {
            super.onAnimationCancel(animator);
            notifyEvent(FlutterPagPlugin._eventCancel);
        }

        @Override
        public void onAnimationRepeat(Animator animator) {
            super.onAnimationRepeat(animator);
            notifyEvent(FlutterPagPlugin._eventRepeat);
        }
    };

    void notifyEvent(String event) {
        final HashMap<String, Object> arguments = new HashMap<>();
        arguments.put(FlutterPagPlugin._argumentTextureId, textureId);
        arguments.put(FlutterPagPlugin._argumentEvent, event);
        channel.invokeMethod(FlutterPagPlugin._playCallback, arguments);
    }
}

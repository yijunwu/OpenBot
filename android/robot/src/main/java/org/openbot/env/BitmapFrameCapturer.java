package org.openbot.env;

import static androidx.core.util.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.view.Surface;

import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

/**
 * A [VideoCapturer] that can be manually driven by passing in [Bitmap].
 * <p>
 * Once [startCapture] is called, call [pushBitmap] to render images as video frames.
 */
public class BitmapFrameCapturer implements VideoCapturer {
    private SurfaceTextureHelper surfaceTextureHelper = null;
    public CapturerObserver capturerObserver = null;
    private boolean disposed = false;
    public boolean active = false;

    private int rotation = 0;
    private int width = 0;
    private int height = 0;

    private Object stateLock = new Object();

    private Surface surface = null;

    @Override
    public void initialize(
            SurfaceTextureHelper surfaceTextureHelper,
            Context context,
            CapturerObserver observer
    ) {
        synchronized (stateLock) {
            this.surfaceTextureHelper = surfaceTextureHelper;
            this.capturerObserver = observer;
            surface = new Surface(surfaceTextureHelper.getSurfaceTexture());
        }
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("Capturer is disposed.");
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void startCapture(int width, int height, int framerate) {
        synchronized (stateLock) {
            checkNotDisposed();
            checkNotNull(surfaceTextureHelper, "BitmapFrameCapturer must be initialized before calling startCapture.");
            if (capturerObserver != null) capturerObserver.onCapturerStarted(true);
            surfaceTextureHelper.startListening(frame -> {
                if (capturerObserver != null) {
                    capturerObserver.onFrameCaptured(frame);
                }
            });
            active = true;
        }
    }

    @Override
    public void stopCapture() {
        synchronized (stateLock) {
            surfaceTextureHelper.stopListening();
            capturerObserver.onCapturerStopped();
            active = false;
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        // Do nothing.
        // These attributes are driven by the bitmaps fed in.
    }

    @Override
    public void dispose() {
        synchronized (stateLock) {
            if (disposed) {
                return;
            }

            stopCapture();
            surface.release();
            disposed = true;
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    @SuppressLint("RestrictedApi")
    public void pushBitmap(Bitmap bitmap, int rotationDegrees) {
        synchronized (stateLock) {
            if (disposed) {
                return;
            }

            checkNotNull(surfaceTextureHelper);
            checkNotNull(surface);
            if (this.rotation != rotationDegrees) {
                surfaceTextureHelper.setFrameRotation(rotationDegrees);
                this.rotation = rotationDegrees;
            }

            if (this.width != bitmap.getWidth() || this.height != bitmap.getHeight()) {
                surfaceTextureHelper.setTextureSize(bitmap.getWidth(), bitmap.getHeight());
                this.width = bitmap.getWidth();
                this.height = bitmap.getHeight();
            }

            surfaceTextureHelper.getHandler().post(() -> {
                Canvas canvas;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    canvas = surface.lockHardwareCanvas();
                } else {
                    canvas = surface.lockCanvas(null);
                }

                if (canvas != null) {
                    canvas.drawBitmap(bitmap, new Matrix(), new Paint());
                    surface.unlockCanvasAndPost(canvas);
                }
            });
        }
    }
}


class RedirectedVideoCapturer implements VideoCapturer {
    public CapturerObserver capturerObserver;
    public boolean active = false;
    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        this.capturerObserver = capturerObserver;
    }

    @Override
    public void startCapture(int i, int i1, int i2) {
        active = true;
    }

    @Override
    public void stopCapture() throws InterruptedException {
        active = false;
    }

    @Override
    public void changeCaptureFormat(int i, int i1, int i2) {

    }

    @Override
    public void dispose() {

    }

    @Override
    public boolean isScreencast() {
        return false;
    }
}
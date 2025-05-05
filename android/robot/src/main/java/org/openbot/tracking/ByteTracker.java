package org.openbot.tracking; // Adjust package name if needed

import android.util.Log;

import java.util.ArrayList;

public class ByteTracker implements AutoCloseable {

    private static final String TAG = "ByteTrackerJava";

    // Pointer to the native C++ ByteTracker object
    private long nativePtr = 0;

    // Load the native library
    static {
        try {
            System.loadLibrary("app"); // Use the same library name as in CMakeLists.txt
            Log.i(TAG, "Successfully loaded native library 'app'");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library 'app'", e);
            // Handle library loading failure (e.g., throw an exception or set a flag)
        }
    }

    /**
     * Initializes the native ByteTracker object.
     * Must be called before calling update.
     *
     * @param frameRate Target frame rate.
     * @param trackBuffer Buffer size (in frames).
     * @return true if initialization was successful, false otherwise.
     */
    public boolean init(int frameRate, int trackBuffer) {
        // Release previous instance if it exists
        release();
        try {
            this.nativePtr = nativeInit(frameRate, trackBuffer);
            if (this.nativePtr == 0) {
                Log.e(TAG, "Native initialization returned null pointer.");
                return false;
            }
            Log.i(TAG, "Native ByteTracker initialized successfully. Pointer: " + Long.toHexString(this.nativePtr));
            return true;
        } catch (Throwable t) { // Catch potential errors during JNI call
            Log.e(TAG, "Exception during nativeInit", t);
            this.nativePtr = 0; // Ensure pointer is zero on failure
            return false;
        }
    }

    /**
     * Updates the tracker with new detections.
     *
     * @param objects Detected objects from the current frame.
     * @return A list of tracked objects (STracks). Returns an empty list if not initialized or on error.
     */
    public ArrayList<JavaSTrack> update(ArrayList<JavaObject> objects) {
        if (this.nativePtr == 0) {
            Log.w(TAG, "Update called but ByteTracker is not initialized.");
            return new ArrayList<>(); // Return empty list
        }
        try {
            // Pass the native pointer and the list of objects to the native function
            return nativeUpdate(this.nativePtr, objects);
        } catch (Throwable t) { // Catch potential errors during JNI call
            Log.e(TAG, "Exception during nativeUpdate", t);
            return new ArrayList<>(); // Return empty list on error
        }
    }

    /**
     * Releases the native ByteTracker object's resources.
     * It's important to call this when the tracker is no longer needed to avoid memory leaks.
     */
    public void release() {
        if (this.nativePtr != 0) {
            try {
                nativeRelease(this.nativePtr);
                Log.i(TAG, "Native ByteTracker released successfully. Pointer: " + Long.toHexString(this.nativePtr));
            } catch (Throwable t) { // Catch potential errors during JNI call
                Log.e(TAG, "Exception during nativeRelease", t);
            } finally {
                this.nativePtr = 0; // Always set pointer to 0 after attempting release
            }
        } else {
            Log.d(TAG,"Release called but native pointer is already zero.");
        }
    }

    /**
     * Implements AutoCloseable for use in try-with-resources statements.
     */
    @Override
    public void close() {
        release();
    }

    /**
     * Overrides finalize to attempt cleanup, but relying on explicit release() or close() is preferred.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (nativePtr != 0) {
                Log.w(TAG, "ByteTracker finalized without explicit release! Releasing now. Pointer: " + Long.toHexString(nativePtr));
                release();
            }
        } finally {
            super.finalize();
        }
    }

    // --- Native Method Declarations ---

    /**
     * Calls the native C++ code to initialize the tracker.
     * @param frameRate Frame rate.
     * @param trackBuffer Track buffer size.
     * @return A long representing the pointer to the native ByteTracker object, or 0 on failure.
     */
    private native long nativeInit(int frameRate, int trackBuffer);

    /**
     * Calls the native C++ code to update the tracker.
     * @param nativePtr Pointer to the native ByteTracker object.
     * @param objects List of detected JavaObject.
     * @return List of tracked JavaSTrack.
     */
    private native ArrayList<JavaSTrack> nativeUpdate(long nativePtr, ArrayList<JavaObject> objects);

    /**
     * Calls the native C++ code to release the tracker resources.
     * @param nativePtr Pointer to the native ByteTracker object to be released.
     */
    private native void nativeRelease(long nativePtr);
}
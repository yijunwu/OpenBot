#include <jni.h>
#include <vector>
#include <string>
#include <android/log.h>

// Include ByteTracker core headers
#include "BYTETracker.h"
#include "STrack.h"
#include "yolox.h" // Contains the definition for 'Object' struct

#define LOG_TAG_BT "ByteTrackJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_BT, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_BT, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_BT, __VA_ARGS__)

// Helper function to convert Java ArrayList<JavaObject> to C++ std::vector<Object>
// Returns true on success, false on failure
bool javaListToCppVector(JNIEnv *env, jobject javaObjectsList, std::vector<Object>& cppObjects) {
    jclass listClass = env->FindClass("java/util/ArrayList");
    if (!listClass) {
        LOGE("Failed to find class java/util/ArrayList");
        return false;
    }
    jmethodID listGetMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jmethodID listSizeMethod = env->GetMethodID(listClass, "size", "()I");
    if (!listGetMethod || !listSizeMethod) {
        LOGE("Failed to find ArrayList methods");
        return false;
    }

    // Make sure to use the correct package name here
    jclass objClass = env->FindClass("org/openbot/tracking/JavaObject");
    if (!objClass) {
        LOGE("Failed to find class org/openbot/tracking/JavaObject");
        return false;
    }
    jfieldID rectField = env->GetFieldID(objClass, "rect", "Landroid/graphics/RectF;");
    jfieldID labelField = env->GetFieldID(objClass, "label", "I");
    jfieldID probField = env->GetFieldID(objClass, "prob", "F");
    if (!rectField || !labelField || !probField) {
        LOGE("Failed to find JavaObject fields");
        return false;
    }

    jclass rectFClass = env->FindClass("android/graphics/RectF");
    if (!rectFClass) {
        LOGE("Failed to find class android/graphics/RectF");
        return false;
    }
    jfieldID leftField = env->GetFieldID(rectFClass, "left", "F");
    jfieldID topField = env->GetFieldID(rectFClass, "top", "F");
    jfieldID rightField = env->GetFieldID(rectFClass, "right", "F");
    jfieldID bottomField = env->GetFieldID(rectFClass, "bottom", "F");
    if (!leftField || !topField || !rightField || !bottomField) {
        LOGE("Failed to find RectF fields");
        return false;
    }

    int listSize = env->CallIntMethod(javaObjectsList, listSizeMethod);
    LOGD("Converting %d JavaObjects to C++ Objects", listSize);
    cppObjects.clear(); // Clear previous content
    cppObjects.reserve(listSize);

    for (int i = 0; i < listSize; ++i) {
        jobject javaObj = env->CallObjectMethod(javaObjectsList, listGetMethod, i);
        if (!javaObj) {
            LOGW("Null object found in ArrayList at index %d", i);
            env->DeleteLocalRef(javaObj); // Still need to delete the null ref
            continue;
        }

        jobject rectFObj = env->GetObjectField(javaObj, rectField);
        if (!rectFObj) {
            LOGW("Null RectF found in JavaObject at index %d", i);
            env->DeleteLocalRef(rectFObj);
            env->DeleteLocalRef(javaObj);
            continue;
        }
        jint label = env->GetIntField(javaObj, labelField);
        jfloat prob = env->GetFloatField(javaObj, probField);

        jfloat left = env->GetFloatField(rectFObj, leftField);
        jfloat top = env->GetFloatField(rectFObj, topField);
        jfloat right = env->GetFloatField(rectFObj, rightField);
        jfloat bottom = env->GetFloatField(rectFObj, bottomField);

        Object cppObj;
        cppObj.rect.x = left;
        cppObj.rect.y = top;
        cppObj.rect.width = right - left;
        cppObj.rect.height = bottom - top;
        cppObj.label = label;
        cppObj.prob = prob;

        cppObjects.push_back(cppObj);

        // Release local references
        env->DeleteLocalRef(rectFObj);
        env->DeleteLocalRef(javaObj);
    }

    // Clean up class references (optional, can be cached)
    // env->DeleteLocalRef(listClass);
    // env->DeleteLocalRef(objClass);
    // env->DeleteLocalRef(rectFClass);

    return true;
}

// Helper function to convert C++ std::vector<STrack> to Java ArrayList<JavaSTrack>
jobject cppVectorToJavaList(JNIEnv *env, const std::vector<STrack>& cppTracks) {
    jclass listClass = env->FindClass("java/util/ArrayList");
    if (!listClass) {
        LOGE("Failed to find class java/util/ArrayList");
        return nullptr; // Return null on error
    }
    jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "(I)V");
    jmethodID listAddMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    if (!listConstructor || !listAddMethod) {
        LOGE("Failed to find ArrayList constructor or add method");
        return nullptr;
    }

    // Make sure to use the correct package name here
    jclass javaSTrackClass = env->FindClass("org/openbot/tracking/JavaSTrack");
    if (!javaSTrackClass) {
        LOGE("Failed to find class org/openbot/tracking/JavaSTrack");
        return nullptr;
    }
    // Constructor: (int trackId, int state, float score, RectF rect)
    jmethodID javaSTrackConstructor = env->GetMethodID(javaSTrackClass, "<init>", "(IIFLandroid/graphics/RectF;)V");
    if (!javaSTrackConstructor) {
        LOGE("Failed to find JavaSTrack constructor");
        return nullptr;
    }

    jclass rectFClass = env->FindClass("android/graphics/RectF");
    if (!rectFClass) {
        LOGE("Failed to find class android/graphics/RectF");
        return nullptr;
    }
    // Constructor: public RectF(float left, float top, float right, float bottom)
    jmethodID rectFConstructor = env->GetMethodID(rectFClass, "<init>", "(FFFF)V");
    if (!rectFConstructor) {
        LOGE("Failed to find RectF constructor");
        return nullptr;
    }

    // Create Java ArrayList
    jobject javaTracksList = env->NewObject(listClass, listConstructor, static_cast<jint>(cppTracks.size()));
    if (!javaTracksList) {
        LOGE("Failed to create Java ArrayList");
        return nullptr;
    }

    LOGD("Converting %zu C++ STracks to JavaSTracks", cppTracks.size());
    for (const auto& cppTrack : cppTracks) {
        // STrack::tlbr is [left, top, right, bottom]
        if (cppTrack.tlbr.size() != 4) {
            LOGE("Invalid tlbr size for track ID %d", cppTrack.track_id);
            continue; // Skip this invalid track
        }
        jfloat tlbr_left = cppTrack.tlbr[0];
        jfloat tlbr_top = cppTrack.tlbr[1];
        jfloat tlbr_right = cppTrack.tlbr[2];
        jfloat tlbr_bottom = cppTrack.tlbr[3];

        // Create Java RectF object
        jobject javaRectF = env->NewObject(rectFClass, rectFConstructor, tlbr_left, tlbr_top, tlbr_right, tlbr_bottom);
        if (!javaRectF) {
            LOGE("Failed to create Java RectF object for track ID %d", cppTrack.track_id);
            continue;
        }

        // Create JavaSTrack object
        jobject javaSTrack = env->NewObject(javaSTrackClass, javaSTrackConstructor,
                                            static_cast<jint>(cppTrack.track_id),
                                            static_cast<jint>(cppTrack.state),
                                            static_cast<jfloat>(cppTrack.score),
                                            javaRectF);
        if (!javaSTrack) {
            LOGE("Failed to create JavaSTrack object for track ID %d", cppTrack.track_id);
            env->DeleteLocalRef(javaRectF); // Clean up created RectF
            continue;
        }

        // Add to ArrayList
        env->CallBooleanMethod(javaTracksList, listAddMethod, javaSTrack);

        // Release local references for objects created in the loop
        env->DeleteLocalRef(javaSTrack);
        env->DeleteLocalRef(javaRectF);
    }

    // Clean up class references (optional, can be cached)
    // env->DeleteLocalRef(listClass);
    // env->DeleteLocalRef(javaSTrackClass);
    // env->DeleteLocalRef(rectFClass);

    return javaTracksList;
}


extern "C" {

// --- Implementation for ByteTracker.java native methods ---
JNIEXPORT jlong JNICALL
Java_org_openbot_tracking_ByteTracker_nativeInit(JNIEnv *env, jobject thiz, jint frameRate, jint trackBuffer) {
    LOGD("nativeInit called with frameRate=%d, trackBuffer=%d", frameRate, trackBuffer);

    // Use nothrow version of new
    BYTETracker* tracker = new (std::nothrow) BYTETracker(static_cast<int>(frameRate), static_cast<int>(trackBuffer));

    if (!tracker) { // Check if allocation failed
        LOGE("Failed to allocate memory for ByteTracker!");
        return 0; // Return 0 to indicate failure
    }

    LOGD("ByteTracker C++ object created at %p", tracker);
    return reinterpret_cast<jlong>(tracker);
}

JNIEXPORT jobject JNICALL
Java_org_openbot_tracking_ByteTracker_nativeUpdate(JNIEnv *env, jobject thiz, jlong nativePtr, jobject javaObjectsList) {
    BYTETracker* tracker = reinterpret_cast<BYTETracker*>(nativePtr);
    if (!tracker) {
        LOGE("nativeUpdate called with null nativePtr!");
        // Return an empty ArrayList
        jclass listClass = env->FindClass("java/util/ArrayList");
        jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "()V");
        return env->NewObject(listClass, listConstructor);
    }

    LOGD("nativeUpdate called for tracker at %p", tracker);

    std::vector<Object> cppObjects;
    if (!javaListToCppVector(env, javaObjectsList, cppObjects)) {
        LOGE("Failed to convert Java Objects to C++ vector in nativeUpdate");
        jclass listClass = env->FindClass("java/util/ArrayList");
        jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "()V");
        return env->NewObject(listClass, listConstructor);
    }

    // --- Remove try-catch around tracker->update() ---
    // Assume tracker->update() won't throw or handle potential crashes
    std::vector<STrack> cppTracks = tracker->update(cppObjects);
    LOGD("tracker->update() finished, %zu tracks returned.", cppTracks.size());
    // --- End removal ---


    jobject javaTracksList = cppVectorToJavaList(env, cppTracks);
    if (!javaTracksList) {
        LOGE("Failed to convert C++ Tracks to Java List in nativeUpdate");
        jclass listClass = env->FindClass("java/util/ArrayList");
        jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "()V");
        return env->NewObject(listClass, listConstructor);
    }

    LOGD("nativeUpdate returning Java ArrayList.");
    return javaTracksList;
}

JNIEXPORT void JNICALL
Java_org_openbot_tracking_ByteTracker_nativeRelease(JNIEnv *env, jobject thiz, jlong nativePtr) {
    BYTETracker* tracker = reinterpret_cast<BYTETracker*>(nativePtr);
    if (tracker) {
        LOGD("nativeRelease called for tracker at %p. Deleting...", tracker);
        // --- Remove try-catch around delete ---
        // Assume delete won't throw (usually safe unless destructor throws, which is bad practice)
        delete tracker;
        LOGD("ByteTracker C++ object at %p deleted.", (void*)nativePtr);
        // --- End removal ---
    } else {
        LOGW("nativeRelease called with null nativePtr.");
    }
}

} // extern "C"
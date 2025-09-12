package org.openbot.common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.google.common.util.concurrent.ListenableFuture;

import org.openbot.R;
import org.openbot.env.BitmapFrameCapturer;
import org.openbot.env.ImageUtils;
import org.openbot.env.SharedPreferencesManager;
import org.openbot.utils.Constants;
import org.openbot.utils.Enums;
import org.openbot.utils.PermissionUtils;
import org.openbot.utils.YuvToRgbConverter;
import org.webrtc.VideoCapturer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class CameraXFragment extends Fragment {

  private ExecutorService cameraExecutor;
  // for built-in cameras
  private PreviewView previewView;
  // for USB cameras
  private SurfaceView usbCameraPreview;
  private Preview preview;
  protected int lensFacing;
  private ProcessCameraProvider cameraProvider;
  private Size analyserResolution = Enums.Preview.HD_4_3.getValue();
  private YuvToRgbConverter converter;
  private Bitmap bitmapBuffer;
  private int rotationDegrees;
  private Camera camera;
  private CameraControl cameraControl;
  private CameraInfo cameraInfo;

  VideoCapturer videoCapturer;

  SharedPreferencesManager preferencesManager;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    // 1. 總承包商讀取自己的“主建築藍圖” // TODO wuyijun, clean up comments
    return inflater.inflate(R.layout.fragment_camera_x, container, false);
  }

  protected View inflateFragment(int resId, LayoutInflater inflater, ViewGroup container) {
    return addCamera(inflater.inflate(resId, container, false), inflater, container);
  }

  protected View inflateFragment(
      ViewBinding viewBinding, LayoutInflater inflater, ViewGroup container) {
    return addCamera(viewBinding.getRoot(), inflater, container);
  }

  private View addCamera(View view, LayoutInflater inflater, ViewGroup container) {
    View cameraView = inflater.inflate(R.layout.fragment_camera, container, false);
    ViewGroup rootView = (ViewGroup) cameraView.getRootView();
    // set lensFacing from user preferences (last used setting)
    lensFacing =
        preferencesManager.getCameraSwitch()
            ? CameraSelector.LENS_FACING_FRONT
            : CameraSelector.LENS_FACING_BACK;
    previewView = cameraView.findViewById(R.id.viewFinder);

    rootView.addView(view);

    if (!PermissionUtils.hasCameraPermission(requireActivity())) {
      requestPermissionLauncherCamera.launch(Constants.PERMISSION_CAMERA);
    } else if (PermissionUtils.shouldShowRational(requireActivity(), Constants.PERMISSION_CAMERA)) {
      PermissionUtils.showCameraPermissionsPreviewToast(requireActivity());
    } else {
      setupCamera();
    }
    return cameraView;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    cameraExecutor = Executors.newSingleThreadExecutor();
  }

  @SuppressLint("RestrictedApi")
  private void setupCamera() {
    ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext());

    cameraProviderFuture.addListener(
            () -> {
              try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
              } catch (ExecutionException | InterruptedException e) {
                Timber.e("Camera setup failed: %s", e.toString());
              }
            },
            ContextCompat.getMainExecutor(requireContext()));
  }

  @SuppressLint({"UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
  private void bindCameraUseCases() {
    converter = new YuvToRgbConverter(requireContext());
    bitmapBuffer = null;
    preview = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
    final boolean rotated = ImageUtils.getScreenOrientation(requireActivity()) % 180 == 90;
    final PreviewView.ScaleType scaleType =
        rotated ? PreviewView.ScaleType.FIT_CENTER : PreviewView.ScaleType.FIT_START;
    previewView.setScaleType(scaleType);
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
      String[] cameraIds = null;
      try {
        cameraIds = manager.getCameraIdList();
        for (String id : cameraIds) {
          CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
          Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
          // 检查是否为前置超广角（需厂商特定标签）
          if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            Range<Float> zoomRatios = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            Timber.d("CameraID", id + " Zoom Range: " + zoomRatios);

            // 检查是否是逻辑多摄像头
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean isLogicalMultiCamera = false;
            if (capabilities != null) {
              for (int capability : capabilities) {
                if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                  isLogicalMultiCamera = true;
                  break;
                }
              }
            }
            Timber.d("CameraInfo", "Is Logical Multi-Camera: " + isLogicalMultiCamera);

            // 如果是逻辑多摄像头，获取物理摄像头ID (API 28+)
            if (isLogicalMultiCamera && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
              Set<String> physicalCameraIds = characteristics.getPhysicalCameraIds();
              Timber.d("CameraInfo", "Physical Camera IDs: " + physicalCameraIds);
              // 在这里你可以看到是否有多个物理ID，并尝试找出哪个是广角
              // 你可能需要进一步查询每个物理ID的特性来区分它们，例如焦距
              for(String physicalId : physicalCameraIds) {
                CameraCharacteristics physicalChars = manager.getCameraCharacteristics(physicalId);
                float[] focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                // ... 分析焦距等信息
                Timber.d("CameraInfo", "Physical ID: " + physicalId + " Focal Lengths: " + Arrays.toString(focalLengths));
              }
            }

            // 检查焦距
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            Timber.d("CameraInfo", "Available Focal Lengths: " + Arrays.toString(focalLengths));

            // 检查缩放范围 (虽然您已经知道 CameraInfo 报告的是什么，但这里是从 CameraCharacteristics 获取)
            Range<Float> zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            Timber.d("CameraInfo", "Zoom Ratio Range from Characteristics: " + zoomRatioRange);

            break; // 假设只有一个前置摄像头
          }
        }
      } catch (CameraAccessException e) {
        Timber.d("CameraAccessException", e);
        //throw new RuntimeException(e);
      } catch (NoSuchFieldError e) {
        Timber.d("NoSuchFieldError", e);
      }

    CameraSelector cameraSelector =
        new CameraSelector.Builder().requireLensFacing(lensFacing)
           .addCameraFilter(cameraInfos -> {
               List<CameraInfo> filtered = new ArrayList<>();
               if (cameraInfos.size() >= 1) {
                 filtered.add(cameraInfos.get(0));
               }
               return filtered;
           })
           .build();
    ImageAnalysis imageAnalysis;

    if (analyserResolution == null)
      imageAnalysis =
          new ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
    else
      imageAnalysis = new ImageAnalysis.Builder().setTargetResolution(analyserResolution).build();
    // insert your code here.
    imageAnalysis.setAnalyzer(
        cameraExecutor,
        image -> {
          if (bitmapBuffer == null)
            bitmapBuffer =
                Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);

          rotationDegrees = image.getImageInfo().getRotationDegrees();
          converter.yuvToRgb(image.getImage(), bitmapBuffer);
          image.close();

          if (videoCapturer instanceof BitmapFrameCapturer) {
            BitmapFrameCapturer bitmapFrameCapturer = (BitmapFrameCapturer)videoCapturer;

            if (bitmapFrameCapturer.capturerObserver != null && bitmapFrameCapturer.active) {
              bitmapFrameCapturer.pushBitmap(bitmapBuffer, 270);
            }
          }
          processFrame(bitmapBuffer, image);
        });
    try {
      if (cameraProvider != null) {
        cameraProvider.unbindAll();
        //List<CameraInfo> cameraInfos = cameraProvider.getAvailableCameraInfos();
        Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        // Get CameraControl and CameraInfo
        if (camera != null) {
          cameraControl = camera.getCameraControl();
          cameraInfo = camera.getCameraInfo();

          float minZoom = cameraInfo.getZoomState().getValue().getMinZoomRatio();
          float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
          // Set initial zoom state
          cameraControl.setZoomRatio(0.6F);
          float currentZoom = cameraInfo.getZoomState().getValue().getZoomRatio();
          currentZoom += 1;
        }
      }
    } catch (Exception e) {
      Timber.e("Use case binding failed: %s", e.toString());
    }
  }

  public int getRotationDegrees() {
    return rotationDegrees;
  }

  private final ActivityResultLauncher<String> requestPermissionLauncherCamera =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          isGranted -> {
            if (isGranted) {
              setupCamera();
            } else if (PermissionUtils.shouldShowRational(
                requireActivity(), Constants.PERMISSION_CAMERA)) {
              PermissionUtils.showCameraPermissionsPreviewToast(requireActivity());
            } else {

            }
          });

  @Override
  public void onDestroy() {
    super.onDestroy();
    cameraExecutor.shutdown();
  }

  @SuppressLint("RestrictedApi")
  public Size getPreviewSize() {
    return preview.getAttachedSurfaceResolution();
  }

  public Size getMaxAnalyseImageSize() {
    return new Size(bitmapBuffer.getWidth(), bitmapBuffer.getHeight());
  }

  public void toggleCamera() {
    lensFacing =
        CameraSelector.LENS_FACING_FRONT == lensFacing
            ? CameraSelector.LENS_FACING_BACK
            : CameraSelector.LENS_FACING_FRONT;
    preferencesManager.setCameraSwitch(!preferencesManager.getCameraSwitch());
    bindCameraUseCases();
  }

  public void setAnalyserResolution(Size resolutionSize) {
    if (resolutionSize == null) analyserResolution = null;
    else {
      if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
        this.analyserResolution = new Size(resolutionSize.getHeight(), resolutionSize.getWidth());
      else this.analyserResolution = resolutionSize;
    }
    bindCameraUseCases();
  }

  protected void processFrame(Bitmap image, ImageProxy imageProxy) {
    throw new UnsupportedOperationException("This method should NOT be called."); // TODO wuyijun, 待确认
  }

}

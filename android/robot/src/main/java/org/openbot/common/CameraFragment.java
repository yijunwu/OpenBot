package org.openbot.common;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import org.openbot.R;
import org.openbot.env.PhoneController;

public abstract class CameraFragment extends ControlsFragment {

  private FragmentManager fragmentManager;
  private CameraXFragment cameraXFragment;
  private UsbCameraFragment usbCameraFragment;

  protected int lensFacing;

  public CameraFragment() {
    // Required empty public constructor
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    // 1. 總承包商讀取自己的“主建築藍圖”
    return inflater.inflate(R.layout.fragment_camera, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    phoneController = PhoneController.getInstance(requireContext(), videoCapturer);

    fragmentManager = this.getChildFragmentManager();

    if (savedInstanceState == null) {
      cameraXFragment = new CameraXFragment();
      usbCameraFragment = new UsbCameraFragment();

      fragmentManager.beginTransaction()
              .add(R.id.camera_preview_fragment_container, usbCameraFragment, "USB_CAM_FRAGMENT")
              .hide(usbCameraFragment) // 預設隱藏
              .add(R.id.camera_preview_fragment_container, cameraXFragment, "CAMX_FRAGMENT")
              // .hide(cameraXFragment) // 預設顯示內置相機，所以不用 hide
              .commit();
    } else {
      // Activity 重建時，通過 Tag 找回 Fragment 實例
      cameraXFragment = (CameraXFragment) fragmentManager.findFragmentByTag("CAMX_FRAGMENT");
      usbCameraFragment = (UsbCameraFragment) fragmentManager.findFragmentByTag("USB_CAM_FRAGMENT");
    }

    // 假設你有個按鈕用來切換
    Button switchButton = view.findViewById(View.NO_ID/*TODO wuyijun, R.id.switch_camera_button*/);
    if (switchButton != null) {
      switchButton.setOnClickListener(v -> {
        if (cameraXFragment.isVisible()) {
          switchToUsbCamera();
        } else {
          switchToBuiltInCamera();
        }
      });
    }
  }

  private void switchToUsbCamera() {
    fragmentManager.beginTransaction()
            .hide(cameraXFragment)
            .show(usbCameraFragment)
            .commit();
  }

  private void switchToBuiltInCamera() {
    fragmentManager.beginTransaction()
            .hide(usbCameraFragment)
            .show(cameraXFragment)
            .commit();
  }


  public Size getMaxAnalyseImageSize() {
    if (cameraXFragment.isVisible()) {
      return cameraXFragment.getMaxAnalyseImageSize();
    } else {
      return null; // TODO wuyijun, 待实现
    }
  }

  public void toggleCamera() {
    if (cameraXFragment.isVisible()) {
      cameraXFragment.toggleCamera();
      lensFacing = cameraXFragment.lensFacing;
    } else {
      usbCameraFragment.toggleCamera(); // TODO wuyijun, 待实现
      lensFacing = CameraSelector.LENS_FACING_FRONT; // TODO wuyijun, 待优化
    }
  }

  public void setAnalyserResolution(Size resolutionSize) {
    if (cameraXFragment.isVisible()) {
      cameraXFragment.setAnalyserResolution(resolutionSize);
    } else {
      usbCameraFragment.setAnalyserResolution(resolutionSize); // TODO wuyijun, 待实现
    }
  }

  protected View inflateFragment(int resId, LayoutInflater inflater, ViewGroup container) {
    if (cameraXFragment.isVisible()) {
      return cameraXFragment.inflateFragment(resId, inflater, container);
    } else {
      return usbCameraFragment.inflateFragment(resId, inflater, container); // TODO wuyijun, 待实现
    }
  }

  protected View inflateFragment(
          ViewBinding viewBinding, LayoutInflater inflater, ViewGroup container) {
    if (cameraXFragment.isVisible()) {
      return cameraXFragment.inflateFragment(viewBinding, inflater, container);
    } else {
      return usbCameraFragment.inflateFragment(viewBinding, inflater, container); // TODO wuyijun, 待实现
    }
  }

  @Override
  protected boolean useBitmapVideoCapturer() {
    return true;
  }

  protected abstract void processFrame(Bitmap image, ImageProxy imageProxy);
}

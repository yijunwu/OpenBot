package org.openbot.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import org.openbot.R;

public abstract class CameraFragment extends ControlsFragment {

  private FragmentManager fragmentManager;
  private CameraXFragment cameraXFragment;
  private UsbCameraFragment usbCameraFragment;

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
    Button switchButton = view.findViewById(-1); //R.id.switch_camera_button
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
}

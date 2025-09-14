package org.openbot.common;

import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import org.openbot.R;

public class UsbCameraFragment extends com.jiangdg.ausbc.base.CameraFragment {

  private AspectRatioTextureView mTextureView;
  private ViewGroup container;
  private View rootView;

  public UsbCameraFragment() {
    // Required empty public constructor
  }
//
//  @Override
//  public View onCreateView(LayoutInflater inflater, ViewGroup container,
//                           Bundle savedInstanceState) {
//    // 1. 專業施工隊讀取自己的“廚房藍圖”
//    View view = inflater.inflate(R.layout.fragment_usb_camera, container, false);
//    mTextureView = view.findViewById(R.id.usb_camera_texture_view);
//    return view;
//  }

  @Override
  protected View getRootView(LayoutInflater inflater, ViewGroup container) {
    // 2. 加載 (Inflate) 佈局，得到根視圖
    rootView = inflater.inflate(R.layout.fragment_usb_camera, container, false);

    // 3. 在根視圖上使用 findViewById 查找子視圖
    //    注意：這個操作通常在 onViewCreated 中進行，這裡為了對比範例寫在 getRootView
    mTextureView = rootView.findViewById(R.id.usb_camera_texture_view);
    this.container = rootView.findViewById(R.id.container);

    return rootView;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 2. 專心處理自己藍圖裡的元件
    Button childButton = view.findViewById(View.NO_ID/* TODO wuyijun, R.id.child_button*/);
    if (childButton != null) {
      childButton.setOnClickListener(v -> {
        Toast.makeText(getContext(), "子 Fragment 的按鈕被點擊了！", Toast.LENGTH_SHORT).show();
      });
    }
  }

  // 2. 實現 libausbc 的抽象方法，告訴它在哪裡渲染預覽畫面
  @Override
  public IAspectRatio getCameraView() {
    return mTextureView;
  }

  // if you want offscreen render
  // please return null, the same as getCameraView()
  @Override public ViewGroup getCameraViewContainer() {
    return this.container;
  }

  @Override
  public void onCameraState(@NonNull MultiCameraClient.ICamera iCamera, @NonNull State state, @Nullable String s) {

  }

  public void toggleCamera() {
    throw new UnsupportedOperationException("Not implemented"); // TODO wuyijun, 待实现
  }

  public void setAnalyserResolution(Size resolutionSize) {
    throw new UnsupportedOperationException("Not implemented"); // TODO wuyijun, 待实现
  }

  public View inflateFragment(int resId, LayoutInflater inflater, ViewGroup container) {
    throw new UnsupportedOperationException("Not implemented"); // TODO wuyijun, 待实现
  }

  public View inflateFragment(ViewBinding viewBinding, LayoutInflater inflater, ViewGroup container) {
    throw new UnsupportedOperationException("Not implemented"); // TODO wuyijun, 待实现
  }
}

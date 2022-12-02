package uniface.android;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.navigation.fragment.NavHostFragment;

import uniface.UniFaceFeature;
import uniface.android.camera.UniCamera2GrabberConfiguration;
import uniface.android.databinding.FragmentSecondBinding;
import uniface.android.license.License;
import uniface.android.util.AndroidUtil;
import uniface.tf2lite.util.TFLieFaceApi;
import uniimage.UniRGBImage;
import uniimage.UniSize;

public class SecondFragment extends ShowCamera2FaceDemoFragment {
    private FragmentSecondBinding binding;
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentSecondBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonSecond.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavHostFragment.findNavController(SecondFragment.this)
                        .navigate(R.id.action_SecondFragment_to_FirstFragment);
            }
        });
        // 后面更新显示人脸小图要用到
        this.mainThreadHandler = new Handler(this.getActivity().getMainLooper());
        // 人脸位置绘制视图设置透明和置顶
        this.binding.drawFacesView.setZOrderOnTop(true);
        this.binding.drawFacesView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        // 开始抓帧并预览
        super.startGrabber(0, 1280, 960, this.binding.previewView, this.binding.drawFacesView);

        this.binding.firstFace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((ImageView)v).setImageResource(R.drawable.std);
                SecondFragment.this.firstFace = null;
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private Handler mainThreadHandler;
    private UniFaceFeature firstFace;
    private long lastShowFaceTime;
    @Override
    protected void onGrabbedFaces(UniRGBImage grabbedImage, UniFaceFeature[] grabbedImageFeatures) {
        if (this.firstFace == null) {
            this.firstFace = grabbedImageFeatures[0];
            final ImageView iv = this.binding.firstFace;
            // 显示找到的第一个人脸小图
            this.mainThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = AndroidUtil.toBitmap(grabbedImageFeatures[0].getFaceImage());
                    iv.setImageBitmap(bitmap);
                }
            });
        }
        if (System.currentTimeMillis() - this.lastShowFaceTime > 500) {
            this.lastShowFaceTime = System.currentTimeMillis();
            final UniFaceFeature[] foundFace = new UniFaceFeature[1];
            final UniFaceFeature[] currentFace = new UniFaceFeature[1];
            for (UniFaceFeature feature : grabbedImageFeatures) {
                if (feature != this.firstFace) {
                    Float similarity = TFLieFaceApi.compare(feature, this.firstFace);
                    // 与第一个人脸比对，相似度大于0.7的判定为同一个人
                    if (similarity > 0.7f) {
                        foundFace[0] = feature;
                    } else {
                        currentFace[0] = feature;
                    }
                }
            }
            if (foundFace[0] != null || currentFace[0] != null) {
                final ImageView ivf = this.binding.foundFace;
                final ImageView ivc = this.binding.currentFace;
                this.mainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (foundFace[0] != null) {
                            // 显示与第一张人脸同人的人脸小图
                            Bitmap bitmap = AndroidUtil.toBitmap(foundFace[0].getFaceImage());
                            ivf.setImageBitmap(bitmap);
                        }
                        if (currentFace[0] != null) {
                            // 显示与第一张人脸不是同一个人的人脸小图
                            Bitmap bitmap = AndroidUtil.toBitmap(currentFace[0].getFaceImage());
                            ivc.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }
    }

    @Override
    protected void onGrabStarted(UniCamera2GrabberConfiguration grabberConfiguration) {
        // 抓帧器启动以后根据帧图尺寸和屏幕宽度，等比调整预览视图和人脸位置绘制视图的尺寸
        UniSize ps = grabberConfiguration.getPortraitPreviewImageSize();
        int w = this.binding.previewView.getResources().getDisplayMetrics().widthPixels;
        int h = w * ps.height / ps.width;

        this.binding.previewView.getHolder().setFixedSize(w, h);
        ViewGroup.LayoutParams lp = this.binding.previewView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        this.binding.previewView.setLayoutParams(lp);

        this.binding.drawFacesView.getHolder().setFixedSize(w, h);
        lp = this.binding.drawFacesView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        this.binding.drawFacesView.setLayoutParams(lp);

        // 是否允许引擎从几何图层中解析人脸特征，设置为true的话，效率高，效果依赖具体手机的摄像头
        super.useGeometryGraph = false;

        this.firstFace = null;
    }
}
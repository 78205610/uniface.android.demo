package uniface.android;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.navigation.fragment.NavHostFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import uniface.UniFaceFeature;
import uniface.android.camera.UniCamera2GrabberConfiguration;
import uniface.android.databinding.FragmentFirstBinding;
import uniface.android.license.License;
import uniface.android.util.AndroidUtil;
import uniimage.UniRGBImage;
import uniimage.UniSize;
import uniimage.util.UniImageUtil;

public class FirstFragment extends ShowCamera2FaceDemoFragment {
    public void saveImage(UniRGBImage image, String fileName) {
        Bitmap bitmap = AndroidUtil.toBitmap(image);
        File file = new File(this.getActivity().getExternalFilesDir("").getAbsolutePath() + "/" + fileName);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {}
            }
        }
    }
    private FragmentFirstBinding binding;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonFirst.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment);
            }
        });
        // 后面更新显示人脸小图要用到
        this.mainThreadHandler = new Handler(this.getActivity().getMainLooper());
        // 人脸位置绘制视图设置透明和置顶
        this.binding.drawFacesView.setZOrderOnTop(true);
        this.binding.drawFacesView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        // 开始抓帧并预览
        super.startGrabber(1, 640, 480, this.binding.previewView, this.binding.drawFacesView);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
    private Handler mainThreadHandler;
    private long lastShowFaceTime;
    @Override
    protected void onGrabbedFaces(UniRGBImage grabbedImage, UniFaceFeature[] grabbedImageFeatures) {
        if (System.currentTimeMillis() - this.lastShowFaceTime > 500) {
//            this.saveImage(grabbedImage, "grabbed.jpg");
            this.lastShowFaceTime = System.currentTimeMillis();
            final ImageView fiv = this.binding.featureImage;
            final ImageView fcv = this.binding.featureCutImage;
            final ImageView ccv = this.binding.cameraCutImage;
            this.mainThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = AndroidUtil.toBitmap(grabbedImageFeatures[0].getFaceImage());
                    fiv.setImageBitmap(bitmap);
                    UniRGBImage img = UniImageUtil.cutImage(grabbedImage, grabbedImageFeatures[0].getRectangle());
                    bitmap = AndroidUtil.toBitmap(img);
                    fcv.setImageBitmap(bitmap);
                    UniFaceFeature[] features = FirstFragment.super.parseFaceFeaturesFromGeometryGraph(grabbedImage.getInsideGeometryGraph());
                    if (features != null && features.length > 0) {
                        img = UniImageUtil.cutImage(grabbedImage, features[0].getRectangle());
                        bitmap = AndroidUtil.toBitmap(img);
                        ccv.setImageBitmap(bitmap);
                    }
                }
            });
        }
    }

    @Override
    protected void onGrabStarted(UniCamera2GrabberConfiguration grabberConfiguration) {
        // 抓帧器启动以后根据帧图尺寸和屏幕宽度，等比调整预览视图和人脸位置绘制视图的尺寸
        UniSize ps = grabberConfiguration.getPortraitPreviewImageSize();
        int w = this.binding.previewView.getResources().getDisplayMetrics().widthPixels;
        int h = w * ps.height / ps.width;

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
    }
}
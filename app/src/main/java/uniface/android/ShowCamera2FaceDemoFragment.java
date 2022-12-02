package uniface.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.Iterator;

import thread.WhileThread;
import uniface.UniFaceFeature;
import uniface.android.camera.UniCamera2Grabber;
import uniface.android.camera.UniCamera2GrabberConfiguration;
import uniface.android.license.License;
import uniface.tf2lite.util.TFLieFaceApi;
import uniimage.UniGeometryGraph;
import uniimage.UniPoint;
import uniimage.UniPolygon;
import uniimage.UniRGBImage;
import uniimage.UniRect;
import uniimage.UniSize;
import uniimage.UniYUVImage;
import uniimage.util.UniImageUtil;

/**
 * UniCamera2Grabber摄像头抓帧和Tensorflow lite人脸识别比对应用例程
 * 1. 启动UniCamera2Grabber摄像头抓帧；
 * 2. 通过TFLieFaceApi对帧图进行人脸识别；
 * 3. 在预览视图上绘制人脸位置；
 */
public abstract class ShowCamera2FaceDemoFragment extends Fragment {
    protected final static String LogTag = "uniface.debug";
    protected abstract void onGrabbedFaces(UniRGBImage grabbedImage, UniFaceFeature[] grabbedImageFeatures);
    protected abstract void onGrabStarted(UniCamera2GrabberConfiguration grabberConfiguration);
    protected UniCamera2GrabberConfiguration grabberConfiguration;
    protected Paint rectPaintY; // 用于绘制UniFaceEngine分析所得人脸矩形
    protected Paint textPaintY; // 用于绘制UniFaceEngine分析所得人脸的可信度、清晰度
    protected Paint rectPaintG; // 用于绘制直接解析摄像头人脸信息所得人脸矩形
    protected Paint textPaintG; // 用于绘制直接解析摄像头人脸信息所得人脸的可信度、清晰度
    protected Paint rectPaintB; // 用于绘制调正以后的人脸矩形
    protected Paint logPaint; // 用于绘制帧率、角度等实时日志信息
    protected UniCamera2Grabber grabber; // 基于Camera2实现的抓帧器
    /**
     * 抓帧器工作状态变化监听器<br>
     * 这里主要用来联动启/停人脸信息绘制后台线程
     */
    protected final UniCamera2Grabber.GrabberStateListener grabberStateListener = new UniCamera2Grabber.GrabberStateListener() {
        @Override
        public void onStarted(String cameraId) {
            ShowCamera2FaceDemoFragment.this.facesDrawThread.start();
            ShowCamera2FaceDemoFragment.this.onGrabStarted(ShowCamera2FaceDemoFragment.this.grabberConfiguration);
        }
        @Override
        public void onStopped(String cameraId) {
            ShowCamera2FaceDemoFragment.this.facesDrawThread.stop();
        }
        /**
         * 在Camera2 AIP CameraCaptureSession.StateCallback.onConfigured()被调用时调用<br>
         * 通过这个方法，可以在相机启用前对其相关参数进行配置，如：对焦、感光等
         * @param cameraId 摄像头ID
         * @param captureRequestBuilder 配置对象，直接修改其属性进行配置
         * @return 如果返回true，表示不要再对摄像头进行其他配置了。
         */
        public boolean onConfigCameraCaptureRequest(String cameraId, CaptureRequest.Builder captureRequestBuilder) {
            // 设置对焦模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            // 设置拍摄图像时相机设备是否使用光学防抖（OIS）。
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            // 感光灵敏度
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 3200);
            // 曝光补偿
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            return true;
        }
    };
    /**
     * 抓帧器抓帧回调<br>
     * 从摄像头获取的每一帧图像都会通过这个回调返回
     */
    protected final UniCamera2Grabber.GrabFrameCallback grabFrameCallback = new UniCamera2Grabber.GrabFrameCallback() {
        private final long[] fpsTimes = new long[10];
        private int fpsIndex;
        @Override
        public void onGrabbed(String cameraId, UniYUVImage frameImage, long timestamp) {
            // 为了不阻塞摄像头的抓帧，这里仅仅将抓取的帧图放入单帧缓存中
            synchronized (ShowCamera2FaceDemoFragment.this.grabbedImageBuffer) {
                ShowCamera2FaceDemoFragment.this.grabbedImageBuffer[0] = frameImage;
                ShowCamera2FaceDemoFragment.this.grabbedImageTimestamp = timestamp;
                ShowCamera2FaceDemoFragment.this.grabbedImageTime = System.currentTimeMillis();
                ShowCamera2FaceDemoFragment.this.grabbedImageBuffer.notifyAll();
            }
            this.fpsTimes[this.fpsIndex++] = System.currentTimeMillis();
            if (this.fpsIndex >= this.fpsTimes.length) {
                this.fpsIndex = 0;
                ShowCamera2FaceDemoFragment.this.fps[0] = this.fpsTimes.length * 1000F / (float)(this.fpsTimes[this.fpsTimes.length - 1] - this.fpsTimes[0]);
            }
        }
    };
    /**
     * 摄像头抓帧线程与人脸信息绘图线程之间的单帧缓存<br>
     * 没有被人脸信息绘图线程及时取走的帧图会被摄像头抓帧线程用新抓取的帧图覆盖
     */
    protected final UniYUVImage[] grabbedImageBuffer = new UniYUVImage[1];
    protected long grabbedImageTimestamp;
    protected long grabbedImageTime;
    /**
     * 人脸信息绘图后台线程
     */
    protected final WhileThread facesDrawThread = new WhileThread() {
        @Override
        protected void stopping() {
            // 激活可能处于wait()状态的run()及时终止运行
            synchronized (ShowCamera2FaceDemoFragment.this.grabbedImageBuffer) {
                ShowCamera2FaceDemoFragment.this.grabbedImageBuffer.notifyAll();
            }
            ShowCamera2FaceDemoFragment.this.grabbedImageBuffer[0] = null;
        }
        @Override
        protected void pausing() {
        }
        @Override
        public void run() {
            UniYUVImage image = null;
            while (super.running()) {
                if (image != null) {
                    try {
                        ShowCamera2FaceDemoFragment.this.onGrabbedImage(image);
                    } catch (Exception e) {
                        Log.e(LogTag, e.getMessage() + " - " + e.getClass().getSimpleName());
                    }
                }
                // 从帧图缓存中取图，如果缓存中无图则阻塞等待
                synchronized (ShowCamera2FaceDemoFragment.this.grabbedImageBuffer) {
                    if (ShowCamera2FaceDemoFragment.this.grabbedImageBuffer[0] == null) {
                        try {
                            ShowCamera2FaceDemoFragment.this.grabbedImageBuffer.wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    image = ShowCamera2FaceDemoFragment.this.grabbedImageBuffer[0];
                    ShowCamera2FaceDemoFragment.this.grabbedImageBuffer[0] = null;
                }
            }
        }
    };
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        this.stopGrabber();
    }
    protected ShowCamera2FaceDemoFragment() {
        this.grabber = new UniCamera2Grabber()
                .setGraberStateListener(this.grabberStateListener)
                .setGrabFrameCallback(this.grabFrameCallback);

        // 准备好绘制人脸信息的Paint
        this.rectPaintY = new Paint();
        this.rectPaintY.setColor(Color.YELLOW);
        this.rectPaintY.setStyle(Paint.Style.STROKE);
        this.rectPaintY.setStrokeWidth(5);

        this.textPaintY = new Paint();
        this.textPaintY.setColor(Color.YELLOW);
        this.textPaintY.setStyle(Paint.Style.FILL);
        this.textPaintY.setTextSize(64f);
        this.textPaintY.setStrokeWidth(5);

        this.rectPaintG = new Paint();
        this.rectPaintG.setColor(Color.GREEN);
        this.rectPaintG.setStyle(Paint.Style.STROKE);
        this.rectPaintG.setStrokeWidth(5);

        this.textPaintG = new Paint();
        this.textPaintG.setColor(Color.GREEN);
        this.textPaintG.setStyle(Paint.Style.FILL);
        this.textPaintG.setTextSize(64f);
        this.textPaintG.setStrokeWidth(5);

        this.rectPaintB = new Paint();
        this.rectPaintB.setColor(Color.BLUE);
        this.rectPaintB.setStyle(Paint.Style.STROKE);
        this.rectPaintB.setStrokeWidth(5);

        this.logPaint = new Paint();
        this.logPaint.setColor(Color.BLACK);
        this.logPaint.setStyle(Paint.Style.FILL);
        this.logPaint.setTextSize(32f);
        this.logPaint.setStrokeWidth(5);
    }
    protected SurfaceView facesDrawView;
    protected UniSize facesDrawViewSize = new UniSize();

    /**
     * 启动抓帧器开始抓帧和预览
     * @param cameraId      摄像头编号，一般0是后置摄像头，1是前置摄像头
     * @param grabWidth         期望的帧图像素宽度
     * @param grabHeight        期望的帧图像素高度
     * @param previewView   预览视图对象，仅支持SurfaceView或SurfaceTexture
     * @param facesDrawView 人脸位置绘图视图
     * @return
     */
    protected ShowCamera2FaceDemoFragment startGrabber(int cameraId, int grabWidth, int grabHeight, TextureView previewView, @NonNull SurfaceView facesDrawView) {
        this.facesDrawView = facesDrawView;
        facesDrawView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // 人脸矩形绘制视图的最终尺寸在绘制过程中需要用来对绘制点位进行坐标换算
                ShowCamera2FaceDemoFragment.this.facesDrawViewSize.width = width;
                ShowCamera2FaceDemoFragment.this.facesDrawViewSize.height = height;
            }
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                ShowCamera2FaceDemoFragment.this.facesDrawView = null;
            }
        });
        try {
            this.grabberConfiguration =
                    UniCamera2GrabberConfiguration.createConfiguration(this.getActivity(), cameraId, new UniSize(grabWidth, grabHeight), previewView);
            ShowCamera2FaceDemoFragment.this.startGrabber(this.grabberConfiguration);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }
    /**
     * 启动抓帧器开始抓帧和预览
     * @param cameraId      摄像头编号，一般0是后置摄像头，1是前置摄像头
     * @param grabWidth         期望的帧图像素宽度
     * @param grabHeight        期望的帧图像素高度
     * @param previewView   预览视图对象，仅支持SurfaceView或SurfaceTexture
     * @param facesDrawView 人脸位置绘图视图
     * @return
     */
    protected ShowCamera2FaceDemoFragment startGrabber(int cameraId, int grabWidth, int grabHeight, SurfaceView previewView, @NonNull SurfaceView facesDrawView) {
        this.facesDrawView = facesDrawView;
        facesDrawView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // 人脸矩形绘制视图的最终尺寸在绘制过程中需要用来对绘制点位进行坐标换算
                ShowCamera2FaceDemoFragment.this.facesDrawViewSize.width = width;
                ShowCamera2FaceDemoFragment.this.facesDrawViewSize.height = height;
            }
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                ShowCamera2FaceDemoFragment.this.facesDrawView = null;
            }
        });
        try {
            this.grabberConfiguration =
                    UniCamera2GrabberConfiguration.createConfiguration(this.getActivity(), cameraId, new UniSize(grabWidth, grabHeight), previewView);
            ShowCamera2FaceDemoFragment.this.startGrabber(this.grabberConfiguration);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }
    protected final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            ShowCamera2FaceDemoFragment.this.light = event.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            ShowCamera2FaceDemoFragment.this.lightAccuracy = accuracy;
        }
    };
    private void startGrabber(UniCamera2GrabberConfiguration grabberConfiguration) {
        // 开个光照度监听，观察一下光照度与人脸识别效果的关系
        SensorManager sensorManager = (SensorManager) grabberConfiguration.getContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(this.sensorEventListener, sensor, SensorManager.SENSOR_DELAY_GAME);
        this.licenseValid = License.check(0, this.expiryDate);
        try {
            grabberConfiguration.enableFaceDetect().setSnatchMode(false);
            this.grabber.start(grabberConfiguration);
        } catch (Exception e) {
            Log.e(LogTag, e.getMessage() + " - " + e.getClass().getSimpleName());
        }
    }
    protected void stopGrabber() {
        SensorManager sensorManager = (SensorManager) grabberConfiguration.getContext().getSystemService(Context.SENSOR_SERVICE);
        sensorManager.unregisterListener(this.sensorEventListener);
        this.grabber.stop();
    }
    protected boolean adjustOrientation = true; // 是否让引擎在识别时修正图片成像方向
    protected boolean adjustDegree = true; // 是否让引擎在识别时进行图片角度矫正
    protected boolean useGeometryGraph = false; // 是否让引擎从几何图层中解析人脸特征
    private final long[] fpsTimes = new long[10];
    private int fpsIndex;
    /**
     * 在人脸信息绘图后台线程中被调用
     * @param yuvImage 来自摄像头的实时帧图
     */
    protected void onGrabbedImage(UniYUVImage yuvImage) {
        // 计算人脸识别、绘图等一系列处理的帧率
        this.fpsTimes[this.fpsIndex++] = System.currentTimeMillis();
        if (this.fpsIndex >= this.fpsTimes.length) {
            this.fpsIndex = 0;
            this.fps[1] = this.fpsTimes.length * 1000F / (float)(this.fpsTimes[this.fpsTimes.length - 1] - this.fpsTimes[0]);
        }
        if (!this.adjustOrientation && yuvImage.getOrientation() != null) {
            // 根据图片的方向属性将图片旋转为正图
            yuvImage = UniImageUtil.rotateImage(yuvImage, yuvImage.getOrientation(), (byte)0, new UniSize());
        }
        // 因为人脸识别引擎仅支持RGB格式的图片数据，因此需要转换图片数据的格式
        if (!this.adjustDegree) {
            yuvImage.setDegree(null);
        }
        UniGeometryGraph graph = null;
        if (!this.useGeometryGraph) {
            graph = yuvImage.getInsideGeometryGraph();
            // 将附加在图片上的几何图层置空，人脸识别引擎就会通过人脸识别算法从图片内容中检测人脸，检测结果不依赖摄像头检测的人脸信息
            yuvImage.setInsideGeometryGraph(null);
        }
        long time = System.currentTimeMillis();
        // 通过人脸引擎分析图片中的人脸特征信息（人脸在图中的位置信息、人脸图的清晰度、人脸特征值、仅包含人脸的小图）
        UniFaceFeature[] grabbedImageFeatures = TFLieFaceApi.analyse(yuvImage);
        this.lastAnalyseTime = System.currentTimeMillis() - time;
        if (graph != null) {
            yuvImage.setInsideGeometryGraph(graph);
        }
        // 从配置对象中获取竖屏状态的预览图片尺寸，通常是原始抓帧图尺寸旋转90以后的尺寸，也就是宽高互换
        UniSize previewImageSize = this.grabberConfiguration.getPortraitPreviewImageSize();
        // 调整帧图分析所获的人脸位置信息，获得预览所需的人脸位置信息，调整过程会对人脸位置信息进行旋转、缩放。
        UniFaceFeature[] previewImageFeatures = TFLieFaceApi.adjustFaceFeaturesAngleForPreview(grabbedImageFeatures, yuvImage, previewImageSize);
        UniRGBImage grabbedImage = UniImageUtil.toRGBImage(yuvImage);
        this.drawFaces(grabbedImage, previewImageSize, previewImageFeatures);
        if (grabbedImageFeatures != null && grabbedImageFeatures.length > 0) {
            this.onGrabbedFaces(grabbedImage, grabbedImageFeatures);
        }
    }
    protected final float[] fps = new float[2];
    protected long lastAnalyseTime; // 最近一次调用人脸识别引擎进行图片分析的耗时毫秒值
    protected String[] expiryDate = new String[1];
    protected boolean licenseValid;
    private float light;
    private int lightAccuracy;
    @SuppressLint("DefaultLocale")
    private void drawFaces(UniRGBImage grabbedImage, UniSize previewImageSize, UniFaceFeature[] previewImageFeatures) {
        Canvas canvas = null;
        SurfaceHolder surfaceHolder = null;
        try{
            SurfaceView fdv = this.facesDrawView;
            surfaceHolder = fdv == null ? null : fdv.getHolder();
            canvas = surfaceHolder == null ? null : surfaceHolder.lockCanvas(null);
            if (canvas == null) {
                return;
            }

            canvas.drawColor(Color.WHITE);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);

            this.drawFaces(canvas, this.rectPaintY, this.rectPaintB, this.textPaintY, previewImageSize, previewImageFeatures, true);

            UniFaceFeature[] graphFeatures = this.parseFaceFeaturesFromGeometryGraph(grabbedImage.getInsideGeometryGraph());
            if (graphFeatures != null) {
                UniFaceFeature[] graphPreviewFeatures = TFLieFaceApi.adjustFaceFeaturesAngleForPreview(graphFeatures, grabbedImage, previewImageSize);
                this.drawFaces(canvas, this.rectPaintG, this.rectPaintG, textPaintG, previewImageSize, graphPreviewFeatures, false);
            }
            Integer imageOrientation = grabbedImage.getOrientation();
            Integer imageDegree = grabbedImage.getDegree();
            int logY = 32;
            int logYH = 32;
            // fps[0]是抓帧帧率，fps[1]是人脸识别、绘图帧率
            canvas.drawText(String.format("FPS:%.2f|%.2f", this.fps[0], this.fps[1]), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("face analyse time:%d", this.lastAnalyseTime), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("image size:%dx%d", grabbedImage.getWidth(), grabbedImage.getHeight()), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("image orientation:%d, degree:%d", imageOrientation == null ? -1 : imageOrientation, imageDegree == null ? -1 : imageDegree), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("timestamp:%d, time:%d", this.grabbedImageTimestamp, this.grabbedImageTime), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("light:%.2f|%d", this.light, this.lightAccuracy), 32, logY, this.logPaint);
            logY += logYH;
            canvas.drawText(String.format("license(%s) %s", this.licenseValid, this.expiryDate[0]), 32, logY, this.logPaint);

        } catch(Exception e){
            e.printStackTrace();
        }finally{
            if(canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }
    @SuppressLint("DefaultLocale")
    private void drawFaces(Canvas canvas, Paint polygonPaint, Paint rectPaint, Paint textPaint, UniSize previewSize, UniFaceFeature[] previewFeatures, boolean topTip) {
        int sw = this.facesDrawViewSize.width;
        int sh = this.facesDrawViewSize.height;
        for (UniFaceFeature feature : previewFeatures) {
            UniPolygon polygon = feature.getPolygon();
            if (polygon != null && polygon.getPoints() != null) {
                UniPoint firstPoint = null;
                UniPoint prevPoint = null;
                int minX = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE;
                Iterator<UniPoint> it = polygon.getPoints().iterator();
                // 按人脸区域的多边形数据绘制
                while (it.hasNext()) {
                    UniPoint point = it.next().clone();
                    point.x = point.x * sw / previewSize.getWidth();
                    point.y = point.y * sh / previewSize.getHeight();
                    if (this.grabberConfiguration.isFront()) {
                        point.x = sw - point.x;
                    }
                    if (minX > point.x) {
                        minX = point.x;
                    }
                    if (minY > point.y) {
                        minY = point.y;
                    }
                    if (maxY < point.y) {
                        maxY = point.y;
                    }
                    if (firstPoint == null) {
                        firstPoint = point;
                    }
                    if (prevPoint != null) {
                        canvas.drawLine(prevPoint.x, prevPoint.y, point.x, point.y, polygonPaint);
                    }
                    prevPoint = point;
                }
                if (firstPoint != prevPoint) {
                    canvas.drawLine(prevPoint.x, prevPoint.y, firstPoint.x, firstPoint.y, polygonPaint);
                }
                // 按人脸区域的矩形数据绘制
                if (!polygon.isRect()) {
                    UniRect rect = feature.getRectangle();
                    rect.x = rect.x * sw / previewSize.getWidth();
                    rect.y = rect.y * sh / previewSize.getHeight();
                    rect.width = rect.width * sw / previewSize.getWidth();
                    rect.height = rect.height * sh / previewSize.getHeight();
                    if (this.grabberConfiguration.isFront()) {
                        rect.x = sw - rect.x - rect.width;
                    }
                    RectF rect2 = new RectF(rect.x, rect.y, rect.x + rect.width - 1, rect.y + rect.height - 1);
                    canvas.drawRect(rect2, rectPaint);
                }
                UniPoint mp = feature.getMouthPoint();
                UniPoint lep = feature.getLeftEyePoint();
                UniPoint rep = feature.getRightEyePoint();
                // 绘制人脸的嘴眼点位
                if (mp != null) {
                    mp.x = mp.x * sw / previewSize.getWidth();
                    mp.y = mp.y * sh / previewSize.getHeight();
                    lep.x = lep.x * sw / previewSize.getWidth();
                    lep.y = lep.y * sh / previewSize.getHeight();
                    rep.x = rep.x * sw / previewSize.getWidth();
                    rep.y = rep.y * sh / previewSize.getHeight();
                    if (this.grabberConfiguration.isFront()) {
                        mp.x += (sw / 2 - mp.x) * 2;
                        lep.x += (sw / 2 - lep.x) * 2;
                        rep.x += (sw / 2 - rep.x) * 2;
                    }
                    canvas.drawLine(mp.x, mp.y, lep.x, lep.y, polygonPaint);
                    canvas.drawLine(lep.x, lep.y, rep.x, rep.y, polygonPaint);
                    canvas.drawLine(mp.x, mp.y, rep.x, rep.y, polygonPaint);
                }
                canvas.drawText(String.format("置信:%d,清晰:%.2f", feature.getScore() == null ? -1 : feature.getScore(), feature.getClarity() == null ? -1f : feature.getClarity()), minX, (topTip ? minY : maxY) - 8, textPaint);
            }
        }
    }
    protected UniFaceFeature[] parseFaceFeaturesFromGeometryGraph(UniGeometryGraph rootGraph) {
        UniFaceFeature[] features = null;
        if (rootGraph != null) {
            UniGeometryGraph facesGraph = rootGraph.findInsideGeometryGraph(UniGeometryGraph.Face);
            if (facesGraph != null && facesGraph.getInsideGraphs() != null && facesGraph.getInsideGraphs().size() > 0) {
                features = new UniFaceFeature[facesGraph.getInsideGraphs().size()];
                Iterator<UniGeometryGraph> it = facesGraph.getInsideGraphs().iterator();
                int i = 0;
                while (it.hasNext()) {
                    UniGeometryGraph face = it.next();
                    UniFaceFeature feature = new UniFaceFeature();
                    feature.setFaceId(face.getId());
                    feature.setScore(face.getScore());
                    feature.setPolygon(new UniPolygon(face.getPoints()));
                    if (face.getInsideGraphs() != null && face.getInsideGraphs().size() > 0) {
                        Iterator<UniGeometryGraph> git = face.getInsideGraphs().iterator();
                        while (git.hasNext()) {
                            UniGeometryGraph graph = git.next();
                            if (UniGeometryGraph.FacePolygonMouthPoint.equals(graph.getName())) {
                                feature.setMouthPoint(graph.asPoint());
                            } else if (UniGeometryGraph.FacePolygonLeftEyePoint.equals(graph.getName())) {
                                feature.setLeftEyePoint(graph.asPoint());
                            } else if (UniGeometryGraph.FacePolygonRightEyePoint.equals(graph.getName())) {
                                feature.setRightEyePoint(graph.asPoint());
                            }
                        }
                    }
                    features[i] = feature;
                    i++;
                }
            }
        }
        return features;
    }
}

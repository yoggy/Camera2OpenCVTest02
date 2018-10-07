package net.sabamiso.android.camera2opencvtest02;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.widget.ImageView;
import android.widget.Toast;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "MainActivity";

    CameraCharacteristics cameraCharacteristics;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    CaptureRequest.Builder builder;

    ImageReader imageReader;
    ImageView imageView;
    Bitmap captureResultBitmap;

    RenderScript renderScript;
    ScriptC_yuv420888 script;

    FPSCounter fpsCounter = new FPSCounter();
    StopWatch stopWatch = new StopWatch();

    static {
        System.loadLibrary("opencv_java3");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = (ImageView)findViewById(R.id.imageView);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);

        imageReader = ImageReader.newInstance(1280, 720 , ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(imageReaderOnImageAvailableListener, new Handler(this.getMainLooper()));

        captureResultBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);


        renderScript = RenderScript.create(this);
        script = new ScriptC_yuv420888(renderScript);
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        //
        // ここを通過する際、アプリのpermissionの状態は、次の3種類
        //    1. permissionダイアログでユーザが拒否した場合
        //    2. permissionが未確認の状態
        //    3. permissionダイアログでユーザが許可した場合
        //
        if (isIgnorePermission() == true) {
            // 1. permissionダイアログでユーザが拒否した場合
            // とりあえずToastを表示して、カメラの権限を許可してもらうように促す...
            Toast.makeText(this, "アプリ情報から、このアプリのカメラのパーミッションを許可してください…", Toast.LENGTH_LONG).show();
        }
        else if (hasPermission() == false) {
            // 2. permissionが未確認の状態
            // 未確認なのでパーミッションダイアログを表示
            requestPermission();
        }
        else {
            // 3. permissionダイアログでユーザが許可した場合
            // カメラプレビューを開始する
            startCamera();

            // この例の場合は、SurfaceViewを使用しないためいきなりonResume()で開始してもOK
            // SurfaceViewを使用する場合は、SurfaceHolder.Callback.surfaceCreated()が呼び出された後で初期化する必要があるので要注意…
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        stopCamera();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    boolean hasPermission() {
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }

    boolean isIgnorePermission() {
        return ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA);
    }

    protected final int MY_PERMISSIONS_REQUEST_CAMERA = 1234;

    void requestPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                MY_PERMISSIONS_REQUEST_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult()");
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_CAMERA: {
                if (grantResults.length > 0  && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "GRANTED Camera permission");
                } else {
                    Log.d(TAG, "IGNORE Camera permission");
                }

                return;
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    String getBackfaceCameraId() {
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : manager.getCameraIdList()) {
                cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    @SuppressLint("MissingPermission")
    void startCamera() {
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = getBackfaceCameraId();
            manager.openCamera(cameraId, stateCallback, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void stopCamera() {
        if (cameraCaptureSession != null) {
            try {
                cameraCaptureSession.stopRepeating();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if(cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    int getOrientation() {
        // see also...https://github.com/googlesamples/android-Camera2Basic/blob/d1a4f53338b76c7aaa2579adbc16ef5a553a5462/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java#L857

        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        final SparseIntArray ORIENTATIONS = new SparseIntArray();
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);

        int displayRotation = this.getWindowManager().getDefaultDisplay().getRotation();
        int cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        int angle = (ORIENTATIONS.get(rotation) + cameraOrientation + 270) % 360;
        return angle;
    }

    final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, " CameraDevice.StateCallback.onOpend()");

            MainActivity.this.cameraDevice = cameraDevice;

            List<Surface> surfacesList = new ArrayList<Surface>();

            Surface surface_image_reader = imageReader.getSurface();
            surfacesList.add(surface_image_reader);

            try {
                builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(surface_image_reader);
                builder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());
                cameraDevice.createCaptureSession(surfacesList, captureSessionCallback, null);

                // addTarget(), createCaptureSession()に使用するsurfaceは複数指定可能
                // SurfaceView, TextureView, ImageReaderなどが使用可能
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, " CameraDevice.StateCallback.onDisconnected()");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            Log.d(TAG, " CameraDevice.StateCallback.onError()");
        }
    };

    final CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.d(TAG, "CameraCaptureSession.StateCallback.onConfigured()");

            MainActivity.this.cameraCaptureSession = session;

            try {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START); // オートフォーカスのトリガー

                session.setRepeatingRequest(builder.build(), captureCallback, null);

            } catch (CameraAccessException e) {
                Log.e(TAG, e.toString());
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.d(TAG, "CameraCaptureSession.StateCallback.onConfigureFailed()");
        }
    };

    final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request,
                                       TotalCaptureResult result) {
            //キャプチャ完了時に呼び出されるコールバック関数
            //Log.d(TAG, "CameraCaptureSession.StateCallback.onCaptureCompleted()");
        }
    };

    final ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader imageReader) {
            //ImageReaderの内容が更新されたら呼び出されるコールバック
            //Log.d(TAG, "ImageReader.OnImageAvailableListener.onImageAvailable()");

            stopWatch.start();

            Image img = imageReader.acquireLatestImage();
            if (img == null) {
                return;
            }

            int w = img.getWidth();
            int h = img.getHeight();

            // see also...https://stackoverflow.com/questions/36212904/yuv-420-888-interpretation-on-samsung-galaxy-s7-camera2
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            byte[] y = new byte[buffer.remaining()];
            buffer.get(y);

            buffer = planes[1].getBuffer();
            byte[] u = new byte[buffer.remaining()];
            buffer.get(u);

            buffer = planes[2].getBuffer();
            byte[] v = new byte[buffer.remaining()];
            buffer.get(v);

            int yRowStride    = planes[0].getRowStride();
            int uvRowStride   = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();  // same for u and v.

            Type.Builder typeUcharY = new Type.Builder(renderScript, Element.U8(renderScript));
            typeUcharY.setX(yRowStride).setY(h);
            Allocation yAlloc = Allocation.createTyped(renderScript, typeUcharY.create());
            yAlloc.copyFrom(y);
            script.set_ypsIn(yAlloc);

            Type.Builder typeUcharUV = new Type.Builder(renderScript, Element.U8(renderScript));
            typeUcharUV.setX(u.length);
            Allocation uAlloc = Allocation.createTyped(renderScript, typeUcharUV.create());
            uAlloc.copyFrom(u);
            script.set_uIn(uAlloc);

            Allocation vAlloc = Allocation.createTyped(renderScript, typeUcharUV.create());
            vAlloc.copyFrom(v);
            script.set_vIn(vAlloc);

            // handover parameters
            script.set_picWidth(w);
            script.set_uvRowStride (uvRowStride);
            script.set_uvPixelStride (uvPixelStride);

            Allocation outAlloc = Allocation.createFromBitmap(renderScript, captureResultBitmap,
                    Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

            Script.LaunchOptions lo = new Script.LaunchOptions();
            lo.setX(0, w);
            lo.setY(0, h);

            script.forEach_doConvert(outAlloc,lo);
            outAlloc.copyTo(captureResultBitmap);
            img.close();

            // test...
            Mat img_rgb = new Mat();
            Mat img_argb = new Mat();
            Mat img_gray = new Mat();
            Utils.bitmapToMat(captureResultBitmap, img_rgb);
            Imgproc.cvtColor(img_rgb, img_argb, Imgproc.COLOR_RGB2RGBA);
            Imgproc.cvtColor(img_rgb, img_gray, Imgproc.COLOR_RGB2GRAY);

            Utils.matToBitmap(img_gray,  captureResultBitmap);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    imageView.setImageBitmap(captureResultBitmap);
                    imageView.invalidate();
                }
            });
            stopWatch.stop();
            fpsCounter.check();
        }
    };
}

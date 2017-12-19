package com.carlm.autoshutter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.CountDownTimer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static java.lang.Math.abs;

public class ViewfinderActivity extends AppCompatActivity implements SensorEventListener {
    private SharedPreferences sharedPref;
    private String cameraID;
    private CameraDevice deviceCamera;
    private CameraManager cameraManager;
    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder previewRequest;
    private CaptureRequest.Builder captureRequest;
    private int setCameraDirection;
    private int shutterDelay;
    private int lapseDelay;
    private boolean timelapse;
    private boolean paused;
    private SensorManager sensorManager;
    private float[] referenceAcceleration;
    private float shakeTolerance;
    private TextureView previewTexture;
    private ImageReader imageReader;
    private List<Surface> outputList;
    private Size previewSize;
    private Size captureSize;
    private File dir;
    private File imageFile;
    private TextView feedback;
    private TextView feedback2;
    private CountDownTimer countdown;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/autoshutter");
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setCameraDirection = CameraCharacteristics.LENS_FACING_BACK;
        referenceAcceleration = new float[]{0,0,0};
        shakeTolerance = 1;
        paused = false;
        setContentView(R.layout.activity_viewfinder);
        feedback = findViewById(R.id.shutterText);
        feedback2 = findViewById(R.id.shutterText2);
        previewTexture = findViewById(R.id.viewfinderView);
    }
    @Override
    protected void onStart(){
        super.onStart();
        startBackgroundThread();
        checkPermissions();
    }
    @Override
    protected void onResume(){
        super.onResume();
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                1000000, 2000000, null);
        shutterDelay = Integer.parseInt(sharedPref.getString("delay",null));
        lapseDelay = Integer.parseInt(sharedPref.getString("frequency",null));
        timelapse = sharedPref.getBoolean("timelapse",false);
        feedback.setText(R.string.hold_still_txt);
        autoShutter(shutterDelay);
    }
    @Override
    protected void onPause(){
        super.onPause();
        sensorManager.unregisterListener(this);
        if (countdown != null)
            countdown.cancel();
    }
    @Override
    protected void onStop(){
        super.onStop();
        try { cameraSession.stopRepeating();
            cameraSession.close();
            deviceCamera.close();
        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {System.err.println("Session already closed");}
        stopBackgroundThread();
    }
    private void autoShutter(int delay){
        if (countdown != null)
            countdown.cancel();
        countdown = new CountDownTimer(delay, 100) {
            @SuppressLint("SetTextI18n")
            @Override
            public void onTick(long millisUntilFinished) {
                feedback2.setText(Long.toString((millisUntilFinished+1000-1)/1000));
            }
            @Override
            public void onFinish() {
                feedback.setText(R.string.capture_txt);
                capture();
            }
        }.start();
    }
    private void capture (){
        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS").format(new Date());
        imageFile = new File(dir, timeStamp + ".jpg");
        try { cameraSession.capture(captureRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException | IllegalStateException | NullPointerException e) {System.err.println("Capture session can\'t build request");}
        if (timelapse){
            feedback.setText(R.string.lapse_capture_txt);
            autoShutter(lapseDelay);}
        else
            feedback2.setText(R.string.final_capture_txt);
    }
    @Override
    public void onSensorChanged(SensorEvent event){
        for (int i=0;i<referenceAcceleration.length;i++){
            if(!paused && abs(event.values[i]-referenceAcceleration[i]) > shakeTolerance) {
                feedback.setText(R.string.hold_still_txt);
                autoShutter(shutterDelay);
            }
            referenceAcceleration[i] = event.values[i];
        }
    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void checkPermissions(){
        final String [] permLegend = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        final int ok = PackageManager.PERMISSION_GRANTED;
        if (ContextCompat.checkSelfPermission(this, permLegend[0]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[0]}, 0);
        else if (ContextCompat.checkSelfPermission(this, permLegend[1]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[1]}, 0);
        else
            initManager();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED)
            permissionsMissing();
        else
            checkPermissions();
    }
    private void permissionsMissing() {
        startActivity(new Intent(ViewfinderActivity.this, PermissionActivity.class));
    }
    private void initManager(){
        if (dir.exists() || dir.mkdir()) {
            if (cameraManager == null)
                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                String[] cameras = cameraManager.getCameraIdList();
                findCamera(cameras);
            } catch (CameraAccessException e) {
                System.err.println("Camera manager can\'t find cameras");
            }
        }
    }
    private void findCamera(String[] cameras) {
        CameraCharacteristics cameraCharacter;
        Integer thisCameraDirection;
        try{ for (String camID : cameras) {
                cameraCharacter = cameraManager.getCameraCharacteristics(camID);
                thisCameraDirection = cameraCharacter.get(CameraCharacteristics.LENS_FACING);
                if (thisCameraDirection != null && thisCameraDirection == setCameraDirection) {
                    cameraID = camID;
                    //noinspection ConstantConditions
                    setOutputSizes(cameraCharacter.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG));
                    break;
                }
            }
        } catch (CameraAccessException | NullPointerException e) {
            System.err.println("Can\'t read camera characteristics or find output sizes");}
    }
    private void setOutputSizes(Size[] jpgSizes){
        int previewWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int previewHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        int captureWidth = previewWidth;
        int captureHeight = previewHeight;
        boolean max = false;
        switch(sharedPref.getString("resolution","max")) {
            case ("hd"):
                captureWidth = 1280;
                captureHeight = 720;
                break;
            case("fhd"):
                captureWidth = 1920;
                captureHeight = 1080;
                break;
            case("max"):
                max = true;
                break;
            default:
                max = true;
                break;
        }
        List<Size> bigEnoughPreview = new ArrayList<>();
        List<Size> bigEnoughCapture = new ArrayList<>();
        for (Size option : jpgSizes) {
            int thisHeight = option.getHeight();
            int thisWidth = option.getWidth();
            if (thisHeight == thisWidth * previewHeight/previewWidth &&
                    thisWidth >= previewWidth && thisHeight >= previewHeight)
                bigEnoughPreview.add(option);
            if (thisHeight == thisWidth * captureHeight/captureWidth &&
                    thisWidth >= captureWidth && thisHeight >= captureHeight)
                bigEnoughCapture.add(option);
        }
        if (bigEnoughPreview.size() > 0) {
            Size size = Collections.min(bigEnoughPreview, new CompareSizesByArea());
            previewSize = new Size(size.getWidth(), size.getHeight());
        } else {
            previewSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        }
        if (bigEnoughCapture.size() > 0) {
            Size size = Collections.min(bigEnoughCapture, new CompareSizesByArea());
            if (max)
                size = Collections.max(bigEnoughCapture, new CompareSizesByArea());
            captureSize = new Size(size.getWidth(), size.getHeight());
        } else {
            captureSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        }
        prepareOutputSurfaces();
    }
    private void prepareOutputSurfaces() {
        if (previewTexture.isAvailable()){
            SurfaceTexture texture = previewTexture.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(readerHandler, null);
            Surface readerSurface = imageReader.getSurface();
            outputList = Arrays.asList(previewSurface, readerSurface);
            openCamera();
        } else
            previewTexture.setSurfaceTextureListener(viewHandler);
    }
    private void openCamera(){
        try { cameraManager.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException e){
            System.err.println("Camera manager can\'t open camera");
        }
    }
    private final CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            preparePreviewRequest();
            prepareCaptureRequest();
            startCameraSession();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera = null;
        }
        public void onDisconnected(@NonNull CameraDevice camera){this.onClosed(camera);}
        public void onError(@NonNull CameraDevice camera, int error){this.onClosed(camera);}
    };
    private void preparePreviewRequest(){
        try { previewRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        previewRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        previewRequest.addTarget(outputList.get(0));
    }
    private void prepareCaptureRequest(){
        try { captureRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequest.addTarget(outputList.get(1));
    }
    private void startCameraSession(){
        try { deviceCamera.createCaptureSession(outputList, cameraSessionHandler, backgroundHandler);
        } catch (CameraAccessException e) {System.err.println("Unable to start preview session");}
    }
    private final CameraCaptureSession.StateCallback cameraSessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            cameraSession = session;
            try { session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException e){System.err.println("Preview session can\'t build request");}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            initManager();}
        public void onClosed(@NonNull CameraCaptureSession session){cameraSession = null;}
    };
    private final TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            prepareOutputSurfaces();}
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {previewTexture = null;return true;}
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private final ImageReader.OnImageAvailableListener readerHandler = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try { Image image = imageReader.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                save(bytes);
                image.close();
            } catch (IOException e) {
                System.err.println("Image file could not be buffered/saved");
            }
        }
        private void save(byte[] bytes) throws IOException {
            OutputStream output = new FileOutputStream(imageFile);
            output.write(bytes);
            output.close();
        }
    };
    @SuppressWarnings("WeakerAccess")
    public void onClickPause(View view){
        paused = true;
        countdown.cancel();
        feedback.setText(R.string.pause_txt);
        feedback2.setText("");
        view.setVisibility(View.GONE);
        findViewById(R.id.playButton).setVisibility(View.VISIBLE);
    }
    public void onClickPlay(View view){
        paused = false;
        feedback.setText(R.string.hold_still_txt);
        autoShutter(shutterDelay);
        view.setVisibility(View.GONE);
        findViewById(R.id.pauseButton).setVisibility(View.VISIBLE);
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSwap(View view){
        if (setCameraDirection == CameraCharacteristics.LENS_FACING_BACK)
            setCameraDirection = CameraCharacteristics.LENS_FACING_FRONT;
        else
            setCameraDirection = CameraCharacteristics.LENS_FACING_BACK;
        onPause();
        deviceCamera.close();
        initManager();
        onResume();
        if (paused)
            onClickPause(findViewById(R.id.pauseButton));
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSettings(View view){
        startActivity (new Intent(ViewfinderActivity.this, SettingsActivity.class));
    }
    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try { backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {System.err.println("Couldn\'t join background thread");}
    }
}
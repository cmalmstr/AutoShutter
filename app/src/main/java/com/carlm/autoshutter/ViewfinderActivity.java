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
import android.util.SparseIntArray;
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
    private TextureView previewTexture;
    private ImageReader imageReader;
    private List<Surface> outputList;
    private File dir;
    private CountDownTimer countdown;
    private SensorManager sensorManager;
    private float[] referenceAcceleration;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {ORIENTATIONS.append(Surface.ROTATION_0, 90);
            ORIENTATIONS.append(Surface.ROTATION_90, 0);
            ORIENTATIONS.append(Surface.ROTATION_180, 270);
            ORIENTATIONS.append(Surface.ROTATION_270, 180);}
    private TextView feedback;
    private TextView feedback2;
    private TextView feedbackLapse;
    private int setCameraDirection;
    private float shakeTolerance;
    private int shutterDelay;
    private int lapseDelay;
    private int lapsePhotos;
    private boolean timelapse;
    private boolean paused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/autoshutter");
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        referenceAcceleration = new float[]{0,0,0};
        shakeTolerance = 1;
        setCameraDirection = CameraCharacteristics.LENS_FACING_BACK;
        setContentView(R.layout.activity_viewfinder);
        feedback = findViewById(R.id.shutterText);
        feedback2 = findViewById(R.id.shutterText2);
        feedbackLapse = findViewById(R.id.lapseText);
        previewTexture = findViewById(R.id.viewfinderView);
        paused = false;
    }
    @Override
    protected void onStart(){
        super.onStart();
        shutterDelay = Integer.parseInt(sharedPref.getString("delay",null));
        lapseDelay = Integer.parseInt(sharedPref.getString("frequency",null));
        timelapse = sharedPref.getBoolean("timelapse",false);
        startBackgroundThread();
        checkPermissions();
    }
    @Override
    protected void onResume(){
        super.onResume();
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                1000000, 2000000, null);
        lapsePhotos = 0;
        if(!paused) {
            feedback.setText(R.string.hold_still_txt);
            feedbackLapse.setText("");
            autoShutter(shutterDelay);
        }
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
        try { cameraSession.capture(captureRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException | IllegalStateException | NullPointerException e) {System.err.println("Capture session can\'t build request");}
        if (timelapse){
            feedback.setText(R.string.lapse_capture_txt);
            lapsePhotos++;
            feedbackLapse.setText(Integer.toString(lapsePhotos) + getString(R.string.lapse_count_txt) + Integer.toString(lapsePhotos/24) + getString(R.string.lapse_count_txt2));
            autoShutter(lapseDelay);}
        else
            feedback2.setText(R.string.final_capture_txt);
    }
    @Override
    public void onSensorChanged(SensorEvent event){
        for (int i=0;i<referenceAcceleration.length;i++){
            if(!paused && abs(event.values[i]-referenceAcceleration[i]) > shakeTolerance)
                onResume();
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
        if ((dir.exists() || dir.mkdir()) && previewTexture.isAvailable()) {
            if (cameraManager == null)
                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                String[] cameras = cameraManager.getCameraIdList();
                findCamera(cameras);
            } catch (CameraAccessException e) { System.err.println("Camera manager can\'t find cameras");}
        }
        else
            previewTexture.setSurfaceTextureListener(viewHandler);
    }
    @SuppressWarnings("ConstantConditions")
    private void findCamera(String[] cameras) {
        CameraCharacteristics cameraCharacter;
        try{ for (String camID : cameras) {
                cameraCharacter = cameraManager.getCameraCharacteristics(camID);
            if (cameraCharacter.get(CameraCharacteristics.LENS_FACING) == setCameraDirection) {
                    cameraID = camID;
                    setOutputSizes(cameraCharacter.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG));
                    break;
                }
            }
        } catch (CameraAccessException | NullPointerException e) {System.err.println("Can\'t read camera characteristics or find output sizes");}
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
        Size previewSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        Size captureSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        if (bigEnoughPreview.size() > 0) {
            Size size = Collections.min(bigEnoughPreview, new CompareSizesByArea());
            previewSize = new Size(size.getWidth(), size.getHeight());
        }
        if (bigEnoughCapture.size() > 0) {
            Size size;
           if (max)
                size = Collections.max(bigEnoughCapture, new CompareSizesByArea());
            else
                size = Collections.min(bigEnoughCapture, new CompareSizesByArea());
            captureSize = new Size(size.getWidth(), size.getHeight());
        }
        prepareOutputSurfaces(previewSize, captureSize);
    }
    private void prepareOutputSurfaces(Size previewSize, Size captureSize) {
        SurfaceTexture texture = previewTexture.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 1);
        imageReader.setOnImageAvailableListener(readerHandler, null);
        outputList = Arrays.asList(new Surface(texture), imageReader.getSurface());
        openCamera();
    }
    private void openCamera(){
        try { cameraManager.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException e){ System.err.println("Camera manager can\'t open camera");}
    }
    private final CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            buildPreview();
            prepareCaptureRequest();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera = null;
        }
        public void onDisconnected(@NonNull CameraDevice camera){this.onClosed(camera);}
        public void onError(@NonNull CameraDevice camera, int error){this.onClosed(camera);}
    };
    private void buildPreview(){
        try { previewRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        previewRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        previewRequest.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
        previewRequest.addTarget(outputList.get(0));
        try { deviceCamera.createCaptureSession(outputList, cameraSessionHandler, backgroundHandler);
        } catch (CameraAccessException e) {System.err.println("Unable to start preview session");}
    }
    private void prepareCaptureRequest(){
        try { captureRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureRequest.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
        captureRequest.addTarget(outputList.get(1));
    }
    private final CameraCaptureSession.StateCallback cameraSessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            cameraSession = session;
            try { session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException e){System.err.println("Preview session can\'t build request");}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {initManager();}
        public void onClosed(@NonNull CameraCaptureSession session){cameraSession = null;}
    };
    private final TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {initManager();}
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {previewTexture = null;return false;}
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private final ImageReader.OnImageAvailableListener readerHandler = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try { Image image = imageReader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                save(bytes);
                image.close();
            } catch (IOException e) { System.err.println("Image file could not be buffered/saved"); }
        }
        private void save(byte[] bytes) throws IOException {
            @SuppressLint("SimpleDateFormat")
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS").format(new Date());
            OutputStream output = new FileOutputStream(new File(dir, timeStamp + ".jpg"));
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
        feedbackLapse.setText("");
        view.setVisibility(View.GONE);
        findViewById(R.id.playButton).setVisibility(View.VISIBLE);
    }
    public void onClickPlay(View view){
        paused = false;
        view.setVisibility(View.GONE);
        findViewById(R.id.pauseButton).setVisibility(View.VISIBLE);
        onResume();
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSwap(View view){
        if (setCameraDirection == CameraCharacteristics.LENS_FACING_BACK)
            setCameraDirection = CameraCharacteristics.LENS_FACING_FRONT;
        else
            setCameraDirection = CameraCharacteristics.LENS_FACING_BACK;
        onPause();
        try { cameraSession.stopRepeating();
            cameraSession.close();
            deviceCamera.close();
        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {System.err.println("Session already closed");}

        initManager();
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
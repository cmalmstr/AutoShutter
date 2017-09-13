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
import android.hardware.camera2.*;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class ViewfinderActivity extends AppCompatActivity {
    private CameraManager cameraman;
    private CameraDevice deviceCamera;
    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder previewRequest;
    private CaptureRequest.Builder captureRequest;
    private String cameraID;
    private String[] cameras;
    private int setCameraDirection;
    private int shutterDelay;
    private int lapseDelay;
    private final int ok = PackageManager.PERMISSION_GRANTED;
    private final String [] permLegend = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private TextureView previewTexture;
    private Surface previewSurface;
    private Surface readerSurface;
    private List<Surface> outputList;
    private Size previewSize;
    private Size captureSize;
    private Size screenSize;
    private File imageFile;
    private TextView feedback;
    private CountDownTimer countdown;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private SharedPreferences sharedPref;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        screenSize = new Size(Resources.getSystem().getDisplayMetrics().widthPixels,
                Resources.getSystem().getDisplayMetrics().heightPixels);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        setCameraDirection = CameraCharacteristics.LENS_FACING_BACK;
        setContentView(R.layout.activity_viewfinder);
        feedback = findViewById(R.id.shutterText);
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
        shutterDelay = Integer.parseInt(sharedPref.getString("delay",null));
        lapseDelay = Integer.parseInt(sharedPref.getString("frequency",null));
        autoShutter(shutterDelay);
    }
    @Override
    protected void onPause(){
        if (cameraSession != null)
            try { cameraSession.stopRepeating();
                cameraSession.close();
            } catch (CameraAccessException | IllegalStateException e) {System.err.println("Session already closed");}
        if (countdown != null)
            countdown.cancel();
        super.onPause();
    }
    @Override
    protected void onStop(){
        if (deviceCamera != null)
            try { deviceCamera.close();
            } catch (IllegalStateException e){System.err.println("Camera already closed");}
        if (previewSurface != null)
            previewSurface.release();
        stopBackgroundThread();
        super.onStop();
    }
    private void autoShutter(int delay){
        if (countdown != null)
            countdown.cancel();
        countdown = new CountDownTimer(delay*1000, 100) {
            @SuppressLint("SetTextI18n")
            @Override
            public void onTick(long millisUntilFinished) {
                feedback.setText(Long.toString((millisUntilFinished+1000-1)/1000));
            }
            @Override
            public void onFinish() {
                feedback.setText(R.string.capture_txt);
                capture();
            }
        }.start();
    }
    private void capture (){
        try { deviceCamera.createCaptureSession(outputList, captureSessionHandler, null);
        } catch (CameraAccessException e) {System.err.println("Can\'t access camera to start session");}
    }
    private void reset(){
        startPreview();
    }
    private void checkPermissions(){
        if (ContextCompat.checkSelfPermission(this, permLegend[0]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[0]}, 0);
        else if (ContextCompat.checkSelfPermission(this, permLegend[1]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[1]}, 0);
        else
            initCameraManager();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (grantResults[0] != ok)
            permissionsMissing();
        else
            checkPermissions();
    }
    private void permissionsMissing() {
        startActivity(new Intent(ViewfinderActivity.this, PermissionActivity.class));
    }
    private void initCameraManager(){
        cameraman = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{ cameras = cameraman.getCameraIdList();
        } catch (CameraAccessException e){System.err.println("Camera manager can\'t find cameras");}
        findCamera();
    }
    private void findCamera() {
        CameraCharacteristics cameraCharacteristics;
        Integer thisCameraDirection;
        try{ for (String camID : cameras) {
                cameraCharacteristics = cameraman.getCameraCharacteristics(camID);
                thisCameraDirection = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (thisCameraDirection != null && thisCameraDirection == setCameraDirection) {
                    cameraID = camID;
                    setOutputSizes(cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG));
                    break;
                }
            }
        } catch (CameraAccessException | NullPointerException e) {
            System.err.println("Can\'t read camera characteristics or find output sizes");}
    }
    private void setOutputSizes(Size[] jpgSizes){
        previewSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        for (Size size : jpgSizes) {
            int thisWidth = size.getWidth();
            int thisHeight = size.getHeight();
            if (thisWidth > screenSize.getWidth() && thisWidth < previewSize.getWidth() &&
                    thisHeight > screenSize.getHeight() && thisHeight < previewSize.getHeight())
                previewSize = new Size(thisWidth, thisHeight);
            captureSize = previewSize;
        }
        if (previewTexture.isAvailable())
            prepareOutputSurfaces();
        else
            previewTexture.setSurfaceTextureListener(viewHandler);
    }
    private void prepareOutputSurfaces() {
        SurfaceTexture texture = previewTexture.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        ImageReader imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 1);
        imageReader.setOnImageAvailableListener(readerHandler, backgroundHandler);
        readerSurface = imageReader.getSurface();
        outputList = Arrays.asList(readerSurface, previewSurface);
        openCamera();
    }
    private void openCamera(){
        try { cameraman.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException e){
            System.err.println("Camera manager can\'t open camera");
        }
    }
    private final CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            preparePreviewRequest();
            prepareCaptureRequest();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera.close();
            deviceCamera = null;
        }
        public void onDisconnected(@NonNull CameraDevice camera){this.onClosed(camera);}
        public void onError(@NonNull CameraDevice camera, int error){this.onClosed(camera);}
    };
    private void preparePreviewRequest(){
        try { previewRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        previewRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        previewRequest.addTarget(previewSurface);
        startPreview();
    }
    private void prepareCaptureRequest(){
        try { captureRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {System.err.println("Unable to create capture request");}
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequest.addTarget(readerSurface);
    }
    private void startPreview(){
        try { deviceCamera.createCaptureSession(outputList, previewSessionHandler, backgroundHandler);
        } catch (CameraAccessException e) {System.err.println("Unable to start preview session");}
    }
    private final CameraCaptureSession.StateCallback previewSessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            cameraSession = session;
            try { session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException e){System.err.println("Preview session can\'t build request");}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {initCameraManager();}
        public void onClosed(@NonNull CameraCaptureSession session){cameraSession = null;}
    };
    private final CameraCaptureSession.StateCallback captureSessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            imageFile = new File(Environment.getExternalStorageDirectory()+"/pic.jpg");
            try { session.capture(captureRequest.build(), captureHandler, backgroundHandler);
            } catch (CameraAccessException e){System.err.println("Capture session can\'t build request");}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {initCameraManager();}
        public void onClosed(@NonNull CameraCaptureSession session){cameraSession = null;}
    };
    private final CameraCaptureSession.CaptureCallback captureHandler = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            reset();
        }
    };
    private final TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            prepareOutputSurfaces();}
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {previewTexture = null;return false;}
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private final ImageReader.OnImageAvailableListener readerHandler = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try { image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                save(bytes);
            } catch (IOException e) {
                System.err.println("Image file could not be buffered/saved");
            } finally {
                if (image != null)
                    image.close();
            }
        }
        private void save(byte[] bytes) throws IOException {
            OutputStream output = null;
            try {
                output = new FileOutputStream(imageFile);
                output.write(bytes);
            } finally {
                if (null != output)
                    output.close();
            }
        }
    };
    public void onClickPause(View view){
        countdown.cancel();
        feedback.setText(R.string.pause_txt);
        view.setVisibility(View.GONE);
        findViewById(R.id.playButton).setVisibility(View.VISIBLE);
    }
    public void onClickPlay(View view){
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
        findCamera();
        onResume();
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSettings(View view){
        startActivity (new Intent(ViewfinderActivity.this, SettingsActivity.class));
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
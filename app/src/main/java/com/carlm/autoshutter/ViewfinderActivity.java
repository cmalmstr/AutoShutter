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
import android.os.CountDownTimer;
import android.os.Bundle;
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

import java.util.Arrays;
import java.util.List;

public class ViewfinderActivity extends AppCompatActivity {
    private CameraManager cameraman;
    private CameraDevice deviceCamera;
    private CameraCaptureSession cameraSession;
    private String cameraID;
    private String[] cameras;
    private int cameraFacing;
    private final int ok = PackageManager.PERMISSION_GRANTED;
    private final String [] permLegend = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private TextureView previewTexture;
    private Surface previewSurface;
    private Size previewSize;
    private Size screenSize;
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
        setContentView(R.layout.activity_viewfinder);
        feedback = (TextView)findViewById(R.id.shutterText);
        previewTexture = (TextureView) findViewById(R.id.viewfinderView);
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
        autoShutter();
    }
    @Override
    protected void onPause(){
        if (cameraSession != null) {
            try { cameraSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {System.err.println("Session already closed");}
            cameraSession.close();
        }
        if (countdown != null)
            countdown.cancel();
        super.onPause();
    }
    @Override
    protected void onStop(){
        if (deviceCamera != null)
            try {
                deviceCamera.close();
            } catch (IllegalStateException e){System.err.println("Camera already closed");}
        if (previewSurface != null)
            previewSurface.release();
        stopBackgroundThread();
        super.onStop();
    }
    private void autoShutter(){
        int delay = Integer.parseInt(sharedPref.getString("delay",null));
        final int interval = 100;
        if (countdown != null)
            countdown.cancel();
        countdown = new CountDownTimer(delay*1000, interval) {
            @SuppressLint("SetTextI18n")
            @Override
            public void onTick(long millisUntilFinished) {
                feedback.setText((millisUntilFinished+1000-1)/1000 + "s");
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onFinish() {
                feedback.setText("0s");
                capture();
            }
        }.start();
    }
    private void capture (){
        feedback.setText(R.string.capture_txt);
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSwap(View view){
        if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK)
            cameraFacing = CameraCharacteristics.LENS_FACING_FRONT;
        else
            cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
        onPause();
        deviceCamera.close();
        findCamera();
        onResume();
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickPause(View view){
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
            feedback.setText(R.string.pause_txt);
        }
        else
            autoShutter();
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickSettings(View view){
        startActivity (new Intent(ViewfinderActivity.this, SettingsActivity.class));
    }
    private void checkPermissions(){
        if (ContextCompat.checkSelfPermission(this, permLegend[0]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[0]}, 0);
        else if (ContextCompat.checkSelfPermission(this, permLegend[1]) != ok)
            ActivityCompat.requestPermissions(this, new String[]{permLegend[1]}, 0);
        else
            setSurfaceTexture();
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
    private void setSurfaceTexture(){
        if (previewTexture.isAvailable())
            initCamera();
        else
            previewTexture.setSurfaceTextureListener(viewHandler);
    }
    private final TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {initCamera();}
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {previewTexture = null;return false;}
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private void initCamera(){
        cameraman = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{ cameras = cameraman.getCameraIdList();
            cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
        } catch (CameraAccessException e){System.err.println("Camera manager can\'t find cameras");}
        findCamera();
    }
    private void findCamera() {
        CameraCharacteristics chars;
        Integer faceValue;
        try{ for (String camID : cameras) {
            chars = cameraman.getCameraCharacteristics(camID);
            faceValue = chars.get(CameraCharacteristics.LENS_FACING);
            if (faceValue != null && faceValue == cameraFacing) {
                cameraID = camID;
                Size[] sizesAvailable = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                if (sizesAvailable != null) {
                    setSizes(sizesAvailable);
                    break;
                }
            }
        }
        cameraman.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException | NullPointerException e) {
            System.err.println("Camera manager can\'t open camera");}
    }
    private void setSizes (Size[] jpgSizes){
        previewSize = new Size(jpgSizes[0].getWidth(), jpgSizes[0].getHeight());
        for (Size size : jpgSizes) {
            int thisWidth = size.getWidth();
            int thisHeight = size.getHeight();
            if (thisWidth > screenSize.getWidth() && thisWidth < previewSize.getWidth() &&
                    thisHeight > screenSize.getHeight() && thisHeight < previewSize.getHeight())
                previewSize = new Size(thisWidth, thisHeight);
        }
    }
    private final CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            initPreview();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera.close();
            deviceCamera = null;
        }
        public void onDisconnected(@NonNull CameraDevice camera){this.onClosed(camera);}
        public void onError(@NonNull CameraDevice camera, int error){this.onClosed(camera);}
    };
    private void initPreview() {
        SurfaceTexture texture = previewTexture.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        List<Surface> outputList = Arrays.asList(previewSurface);
        try { deviceCamera.createCaptureSession(outputList, previewSessionHandler, null);
        } catch (CameraAccessException e) {System.err.println("Can\'t access camera to start session");}
    }
    private final CameraCaptureSession.StateCallback previewSessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            cameraSession = session;
            try {
                CaptureRequest.Builder previewRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequest.addTarget(previewSurface);
                previewRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler);
            } catch (CameraAccessException e){System.err.println("Preview session can\'t build request");}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {initCamera();}
        public void onClosed(@NonNull CameraCaptureSession session){cameraSession = null;}
    };
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {System.err.println("Couldn\'t join background thread");}
    }
}

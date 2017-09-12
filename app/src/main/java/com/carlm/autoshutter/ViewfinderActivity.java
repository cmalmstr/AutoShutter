package com.carlm.autoshutter;

import android.Manifest;
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
    private TextureView previewTexture;
    private Surface previewSurface;
    private Size previewSize;
    private TextView feedback;
    private CountDownTimer countdown;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private SharedPreferences sharedPref;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        setContentView(R.layout.activity_viewfinder);
        previewTexture = (TextureView) findViewById(R.id.viewfinderView);
        feedback = (TextView)findViewById(R.id.shutterText);
    }
    @Override
    protected void onStart(){
        super.onStart();
        startBackgroundThread();
    }
    @Override
    protected void onResume(){
        super.onResume();
        findPermissions();
        autoShutter();
    }
    @Override
    protected void onPause(){
        try { cameraSession.stopRepeating();
        } catch (CameraAccessException e) {}
        cameraSession.close();
        super.onPause();
    }
    @Override
    protected void onStop(){
        if (cameraSession != null)
            onPause();
        deviceCamera.close();
        previewSurface.release();
        stopBackgroundThread();
        super.onStop();
    }
    private void autoShutter(){
        System.out.println();
        int delay = Integer.parseInt(sharedPref.getString("delay",null));
        final int interval = 100;
        if (countdown != null)
            countdown.cancel();
        countdown = new CountDownTimer(delay*1000, interval) {
            @Override
            public void onTick(long millisUntilFinished) {
                feedback.setText((millisUntilFinished+1000-1)/1000 + "s");
            }
            @Override
            public void onFinish() {
                feedback.setText("0s");
                capture();
            }
        }.start();
    }
    private void capture (){
    }
    protected void swapCameras (View view){
        if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK)
            cameraFacing = CameraCharacteristics.LENS_FACING_FRONT;
        else
            cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
        try { cameraSession.stopRepeating();
        } catch (CameraAccessException e) {}
        cameraSession.close();
        deviceCamera.close();
        findCamera();
        autoShutter();
    }
    protected void pauseCount (View view){
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
            feedback.setText(R.string.pausetext);
        }
        else
            autoShutter();
    }
    protected void openSettings (View view){
        Intent intent = new Intent(ViewfinderActivity.this, SettingsActivity.class);
        startActivity(intent);
    }
    protected void setPermissions(View view){

    }
    private void findPermissions (){
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 1);
        }
        else
            findSurface();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    findSurface();
                else
                    noPermissions();
            }}
    }
    private void noPermissions (){
        setContentView(R.layout.activity_permissions);
    }
    private void findSurface(){
        if (previewTexture.isAvailable()) {
            initCamera();
        } else {
            previewTexture.setSurfaceTextureListener(viewHandler);
        }
    }
    private TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            initCamera();
        }
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            previewTexture = null;
            return false;
        }
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private void initCamera(){
        cameraman = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{ cameras = cameraman.getCameraIdList();
            cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
        } catch (CameraAccessException e){}
        findCamera();
    }
    private void findCamera() {
        CameraCharacteristics chars;
        Integer facevalue;
        try{ for (String camID : cameras) {
            chars = cameraman.getCameraCharacteristics(camID);
            facevalue = chars.get(CameraCharacteristics.LENS_FACING);
            if (facevalue != null && facevalue == cameraFacing) {
                cameraID = camID;
                Size[] JPGsizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                setSizes(JPGsizes);
                break;
            }
        }
        } catch (CameraAccessException e) {}
        openCamera();
    }
    private void setSizes (Size[] JPGsizes){
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        previewSize = new Size(JPGsizes[0].getWidth(), JPGsizes[0].getHeight());
        for (Size size : JPGsizes) {
            int thisWidth = size.getWidth();
            int thisHeight = size.getHeight();
            int setWidth = previewSize.getWidth();
            int setHeight = previewSize.getHeight();
            if (thisWidth > screenWidth && thisWidth < setWidth && thisHeight > screenHeight && thisHeight < setHeight)
                previewSize = new Size(thisWidth, thisHeight);
        }
    }
    private void openCamera(){
        try { cameraman.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException e) {
        }
    }
    private CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            initPreview();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera.close();
            deviceCamera = null;
        }
        public void onDisconnected(@NonNull CameraDevice camera){
            deviceCamera.close();
            deviceCamera = null;
        }
        public void onError(@NonNull CameraDevice camera, int error){
            deviceCamera.close();
            deviceCamera = null;
        }
    };
    private void initPreview() {
        SurfaceTexture texture = previewTexture.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        List<Surface> outputList = Arrays.asList(previewSurface);
        try {
            deviceCamera.createCaptureSession(outputList, sessionHandler, null);
        } catch (CameraAccessException e) {
        }
    }
    private CameraCaptureSession.StateCallback sessionHandler = new CameraCaptureSession.StateCallback() {
        public void onConfigured(@NonNull CameraCaptureSession session){
            cameraSession = session;
            try {
                CaptureRequest.Builder previewRequest = deviceCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequest.addTarget(previewSurface);
                previewRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler);
            }
            catch (CameraAccessException e){}
        }
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            initCamera();
        }
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
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

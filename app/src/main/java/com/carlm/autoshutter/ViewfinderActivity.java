package com.carlm.autoshutter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.hardware.camera2.*;
import java.util.Arrays;
import java.util.List;

public class ViewfinderActivity extends AppCompatActivity {
    protected CameraManager cameraman;
    protected CameraDevice deviceCamera;
    protected CameraCaptureSession cameraSession;
    private TextureView previewTexture;
    private Surface previewSurface;
    private String cameraID;
    private String[] cameras;
    private Size[] JPGsizes;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewfinder);
        startBackgroundThread();
        findPermissions();
    }
    @Override
    protected void onPause(){
        super.onPause();
        try { cameraSession.stopRepeating();
        } catch (CameraAccessException e) {}
        stopBackgroundThread();
    }
    @Override
    protected void onResume(){
        super.onResume();
        startBackgroundThread();
        findSurface();
    }
    public void openSettings (View view){
        Intent intent = new Intent(ViewfinderActivity.this, SettingsActivity.class);
        startActivity(intent);
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
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    findSurface();
                } else {
                    noPermissions();
                }
            }
        }
    }
    private void noPermissions (){
        //Ask again, nicely
    }
    private void findSurface(){
        previewTexture = (TextureView) findViewById(R.id.viewfinderView);
        previewTexture.setSurfaceTextureListener(viewHandler);
    }
    private void initCamera(){
        cameraman = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{ cameras = cameraman.getCameraIdList();
        } catch (CameraAccessException e){}
        findCamera(CameraCharacteristics.LENS_FACING_BACK);
    }
    private void findCamera(int cameraFacing) {
        CameraCharacteristics chars;
        Integer facevalue;
        try{ for (int i = 0; i < cameras.length; i++) {
                chars = cameraman.getCameraCharacteristics(cameras[i]);
                facevalue = chars.get(CameraCharacteristics.LENS_FACING);
                if (facevalue != null && facevalue == cameraFacing) {
                    cameraID = cameras[i];
                    JPGsizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                    break;
                }
            }
        } catch (CameraAccessException e) {}
        openCamera();
    }
    private void openCamera(){
        try { cameraman.openCamera(cameraID, cameraHandler, backgroundHandler);
        } catch (SecurityException | CameraAccessException e) {
        }
    }
    private void initPreview() {
        SurfaceTexture texture = previewTexture.getSurfaceTexture();
        texture.setDefaultBufferSize(JPGsizes[0].getWidth(), JPGsizes[0].getHeight());
        previewSurface = new Surface(texture);
        List<Surface> outputList = Arrays.asList(previewSurface);
        try {
            deviceCamera.createCaptureSession(outputList, sessionHandler, null);
        } catch (CameraAccessException e) {
        }
    }
    private final TextureView.SurfaceTextureListener viewHandler = new TextureView.SurfaceTextureListener(){
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            initCamera();
        }
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            findSurface();
            return true;
        }
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private final CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice camera){
            deviceCamera = camera;
            initPreview();
        }
        public void onClosed(@NonNull CameraDevice camera){
            deviceCamera.close();
            openCamera();
        }
        public void onDisconnected(@NonNull CameraDevice camera){
            deviceCamera.close();
            initCamera();
        }
        public void onError(@NonNull CameraDevice camera, int error){
            deviceCamera.close();
            initCamera();
        }
    };
    private final CameraCaptureSession.StateCallback sessionHandler = new CameraCaptureSession.StateCallback() {
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
    };
    protected void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    protected void stopBackgroundThread() {
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

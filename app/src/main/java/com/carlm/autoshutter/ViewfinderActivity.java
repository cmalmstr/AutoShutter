package com.carlm.autoshutter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.hardware.camera2.*;

import java.util.Arrays;
import java.util.List;

public class ViewfinderActivity extends AppCompatActivity {
    CameraManager cameraman;
    String cameraID;
    boolean hasPermissions = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewfinder);
        checkPermissions();
        if(foundCamera() && hasPermissions)
            previewCamera();
    }
    private void checkPermissions (){
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 1);
        }
        else
            hasPermissions = true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    hasPermissions = true;
                } else {
                    hasPermissions = false;
                }
                return;
            }
        }
    }
    private boolean foundCamera(){
        cameraman = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameras = cameraman.getCameraIdList();
            for(int i=0;i<cameras.length;i++){
                CameraCharacteristics chars = cameraman.getCameraCharacteristics(cameras[i]);
                Integer facevalue = chars.get(CameraCharacteristics.LENS_FACING);
                if (facevalue != null && facevalue == CameraCharacteristics.LENS_FACING_BACK)
                    cameraID = cameras[i];
                return true;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return false;
    }
    private void previewCamera(){
        try{
            cameraman.openCamera(cameraID, cameraHandler, null);
        } catch (CameraAccessException e) {
        }
        catch (SecurityException e){
        }
    }
    private CameraDevice.StateCallback cameraHandler = new CameraDevice.StateCallback() {
        @Override
        public void onClosed(CameraDevice camera){
            camera.close();
        }
        @Override
        public void onDisconnected(CameraDevice camera){
            camera.close();
        }
        @Override
        public void onError(CameraDevice camera, int error){
            camera.close();
        }
        @Override
        public void onOpened(CameraDevice camera){
         /*   List<Surface> output =  Arrays.asList(viewHandler.getHolder().getSurface());
            try {
             camera.createCaptureSession(output, sessionHandler, null);
            }
            catch (CameraAccessException e){}
        */}
    };
    private CameraCaptureSession.StateCallback sessionHandler = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
        }
        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }
    };
    //private SurfaceView viewHandler = new SurfaceView(this){

    //};
    public void openSettings (View view){
        Intent intent = new Intent(ViewfinderActivity.this, SettingsActivity.class);
        startActivity(intent);
    }
}

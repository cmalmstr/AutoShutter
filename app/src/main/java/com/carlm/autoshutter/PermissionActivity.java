package com.carlm.autoshutter;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

public class PermissionActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissons);
    }
    @SuppressWarnings("UnusedParameters")
    public void onClickPermissions(View view){
        startActivity(new Intent(PermissionActivity.this, ViewfinderActivity.class));
    }
}

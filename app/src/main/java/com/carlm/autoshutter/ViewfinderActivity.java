package com.carlm.autoshutter;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class ViewfinderActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewfinder);
    }
    public void openSettings (View view){
        Intent intent = new Intent(ViewfinderActivity.this, SettingsActivity.class);
        startActivity(intent);
    }
}

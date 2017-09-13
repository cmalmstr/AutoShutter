package com.carlm.autoshutter;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;

public class SettingsActivity extends AppCompatPreferenceActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new PrefFragment()).commit();
        setupActionBar();
    }
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);
    }
    @Override
    public void onBackPressed(){
        startActivity(new Intent(this, ViewfinderActivity.class));
    }
}

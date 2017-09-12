package com.carlm.autoshutter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.support.v7.app.ActionBar;
import android.preference.PreferenceFragment;
import android.view.MenuItem;

public class SettingsActivity extends AppCompatPreferenceActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new PrefFragment())
                .commit();
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
    public static class PrefFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_general);
            setHasOptionsMenu(true);
            String [] prefKeys = {"resolution","delay","frequency"};
            for (String key : prefKeys){
                ListPreference listPref = (ListPreference) findPreference(key);
                listPref.setSummary(listPref.getEntry());
            }
        }
        @Override
        public void onResume(){
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }
        @Override
        public void onPause(){
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                              String key){
            Preference pref = findPreference(key);
            if (pref instanceof ListPreference) {
                ListPreference listPref = (ListPreference) pref;
                listPref.setSummary(listPref.getEntry());
            }
        }
        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                startActivity(new Intent(getActivity(), ViewfinderActivity.class));
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }
}

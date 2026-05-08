package com.example.castlynk.legacy;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("CastLynkSettings", MODE_PRIVATE);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // Background Activity
        findViewById(R.id.card_bg_activity).setOnClickListener(v -> {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            } else {
                intent.setAction(Settings.ACTION_SETTINGS);
            }
            startActivity(intent);
        });

        // Reset Settings
        findViewById(R.id.card_reset).setOnClickListener(v -> {
            prefs.edit().clear().apply();
            Toast.makeText(this, "Settings Reset to Default", Toast.LENGTH_SHORT).show();
            recreate();
        });

        setupSwitches();
        setupBitrateSeekBar();
    }

    private void setupBitrateSeekBar() {
        android.widget.SeekBar seekBar = findViewById(R.id.seekbar_bitrate);
        seekBar.setProgress(prefs.getInt("bitrate_value", 5));
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) prefs.edit().putInt("bitrate_value", progress).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
    }

    private void setupSwitches() {
        Switch fpsSwitch = findViewById(R.id.switch_fps);
        fpsSwitch.setChecked(prefs.getBoolean("high_fps", false));
        fpsSwitch.setOnCheckedChangeListener((v, isChecked) -> prefs.edit().putBoolean("high_fps", isChecked).apply());

        Switch bitrateSwitch = findViewById(R.id.switch_bitrate_auto);
        bitrateSwitch.setChecked(prefs.getBoolean("bitrate_auto", true));
        bitrateSwitch.setOnCheckedChangeListener((v, isChecked) -> prefs.edit().putBoolean("bitrate_auto", isChecked).apply());

        Switch hwAccelSwitch = findViewById(R.id.switch_hw_accel);
        hwAccelSwitch.setChecked(prefs.getBoolean("hw_accel", true));
        hwAccelSwitch.setOnCheckedChangeListener((v, isChecked) -> prefs.edit().putBoolean("hw_accel", isChecked).apply());
    }
}

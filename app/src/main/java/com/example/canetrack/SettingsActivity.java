package com.example.canetrack;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        SwitchCompat switchTheme = findViewById(R.id.switch_theme);
        TextView tvThemeMode = findViewById(R.id.tv_theme_mode);

        // Set initial state based on current theme
        int currentMode = AppCompatDelegate.getDefaultNightMode();
        boolean isNightMode = currentMode == AppCompatDelegate.MODE_NIGHT_YES;
        
        switchTheme.setChecked(isNightMode);
        updateThemeText(tvThemeMode, isNightMode);

        switchTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateThemeText(tvThemeMode, isChecked);
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
        });
    }

    private void updateThemeText(TextView textView, boolean isNightMode) {
        textView.setText(isNightMode ? "Dark Mode" : "Light Mode");
    }
}

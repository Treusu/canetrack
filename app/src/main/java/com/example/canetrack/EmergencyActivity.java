package com.example.canetrack;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class EmergencyActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }
}

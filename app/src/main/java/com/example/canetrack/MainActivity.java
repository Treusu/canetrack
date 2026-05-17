package com.example.canetrack;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String DB_URL = "https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app";

    private TextView tvConnectionStatus;
    private TextView tvBatteryLevel;
    private TextView tvLastLocation;
    private ProgressBar progressBattery;
    private View indicatorConnection;
    private CardView btnTrack;
    private CardView btnSettings;
    private CardView btnHistory;
    private CardView btnEmergency;

    private DatabaseReference dbRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        listenToFirebase();
        setButtonListeners();
    }

    private void initViews() {
        tvConnectionStatus  = findViewById(R.id.tv_connection_status);
        tvBatteryLevel      = findViewById(R.id.tv_battery_level);
        tvLastLocation      = findViewById(R.id.tv_last_location);
        progressBattery     = findViewById(R.id.progress_battery);
        indicatorConnection = findViewById(R.id.indicator_connection);
        btnTrack            = findViewById(R.id.btn_track);
        btnSettings         = findViewById(R.id.btn_settings);
        btnHistory          = findViewById(R.id.btn_history);
        btnEmergency        = findViewById(R.id.btn_emergency);
    }

    private void listenToFirebase() {
        // Use the regional URL as suggested by the server error
        dbRef = FirebaseDatabase.getInstance("https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("smartcane");

        // ── Connection status ─────────────────────────────────────────────────
        dbRef.child("status").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String status = snapshot.getValue(String.class);
                boolean connected = "connected".equals(status);

                tvConnectionStatus.setText(connected
                        ? "Cane Connected" : "Cane Disconnected");

                indicatorConnection.setBackgroundResource(connected
                        ? R.drawable.circle_green
                        : R.drawable.circle_red);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvConnectionStatus.setText("Connection Error");
            }
        });

        // ── Battery level ─────────────────────────────────────────────────────
        dbRef.child("battery").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer battery = snapshot.getValue(Integer.class);
                if (battery != null) {
                    tvBatteryLevel.setText(battery + "%");
                    progressBattery.setProgress(battery);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });

        // ── Last known location ───────────────────────────────────────────────
        dbRef.child("location").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);
                if (lat != null && lng != null) {
                    tvLastLocation.setText(
                            String.format("%.4f° N,  %.4f° E", lat, lng));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void setButtonListeners() {
        btnTrack.setOnClickListener(v ->
                startActivity(new Intent(this, TrackerActivity.class)));

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        btnEmergency.setOnClickListener(v -> {
            // Write emergency flag to Firebase
            dbRef.child("emergency").setValue(true);
            // Launch Emergency Activity layout
            startActivity(new Intent(this, EmergencyActivity.class));
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE
                && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this,
                    "Location permission is required for tracking.",
                    Toast.LENGTH_LONG).show();
        }
    }
}

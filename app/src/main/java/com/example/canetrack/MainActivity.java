package com.example.canetrack;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
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
    private TextView tvLastLocation;
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
        tvLastLocation      = findViewById(R.id.tv_last_location);
        indicatorConnection = findViewById(R.id.indicator_connection);
        btnTrack            = findViewById(R.id.btn_track);
        btnSettings         = findViewById(R.id.btn_settings);
        btnHistory          = findViewById(R.id.btn_history);
        btnEmergency        = findViewById(R.id.btn_emergency);
    }

    private void writeEmergencyLog(String location) {
        DatabaseReference logsRef = FirebaseDatabase
                .getInstance("https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("smartcane/emergency_logs");

        String logId = logsRef.push().getKey(); // auto-generated unique key
        if (logId == null) return;

        long timestamp = System.currentTimeMillis();

        logsRef.child(logId).child("location").setValue(location);
        logsRef.child(logId).child("timestamp").setValue(timestamp);
    }
    private void listenToFirebase() {
        dbRef = FirebaseDatabase.getInstance("https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("smartcane");

        //Connection status
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


        //Last known location
        dbRef.child("location").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                if (lat == null || lng == null) return;
                tvLastLocation.setText("Locating...");

                LocationHelper.reverseGeocode(lat, lng, new LocationHelper.ReverseGeocodeCallback() {
                    @Override
                    public void onAddressFound(String address) {
                        tvLastLocation.setText(address);
                    }

                    @Override
                    public void onError() {
                        // Fallback
                        tvLastLocation.setText(
                                String.format("%.4f° N, %.4f° E", lat, lng));
                    }
                });
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
        // TEMPORARY TEST BUTTON — remove after testing
        findViewById(R.id.btn_test_emergency).setOnClickListener(v -> {
            btnEmergency.performClick();
        });

        btnEmergency.setOnClickListener(v -> {
            // 1. Set emergency flag
            dbRef.child("emergency").setValue(true);

            // 2. Fetch location THEN write log THEN open activity
            dbRef.child("location").get()
                    .addOnSuccessListener(snapshot -> {
                        Double lat = snapshot.child("lat").getValue(Double.class);
                        Double lng = snapshot.child("lng").getValue(Double.class);

                        if (lat != null && lng != null) {
                            LocationHelper.reverseGeocode(lat, lng,
                                    new LocationHelper.ReverseGeocodeCallback() {
                                        @Override
                                        public void onAddressFound(String address) {
                                            writeEmergencyLog(address);
                                            startActivity(new Intent(MainActivity.this,
                                                    EmergencyActivity.class));
                                        }

                                        @Override
                                        public void onError() {
                                            writeEmergencyLog(
                                                    String.format("%.4f° N, %.4f° E", lat, lng));
                                            startActivity(new Intent(MainActivity.this,
                                                    EmergencyActivity.class));
                                        }
                                    });
                        } else {
                            writeEmergencyLog("Location unavailable");
                            startActivity(new Intent(MainActivity.this,
                                    EmergencyActivity.class));
                        }

                    }).addOnFailureListener(e -> {
                        writeEmergencyLog("Location unavailable");
                        startActivity(new Intent(MainActivity.this,
                                EmergencyActivity.class));
                    });
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

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
    private static final long HISTORY_LOG_INTERVAL = 30 * 60 * 1000; //time interval to update in history log; 30 mins

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
                .getInstance(DB_URL)
                .getReference("smartcane/emergency_logs");

        // Save timestamp to SharedPreferences BEFORE writing
        // This prevents duplicate logs if app reopens quickly
        getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .edit()
                .putLong("last_emergency_handled", System.currentTimeMillis())
                .apply();

        // Check existing log count first — keep only 10 max
        logsRef.get().addOnSuccessListener(snapshot -> {
            long count = snapshot.getChildrenCount();

            if (count >= 10) {
                // Delete the oldest log entry before adding new one
                DataSnapshot oldest = snapshot.getChildren().iterator().next();
                oldest.getRef().removeValue();
            }

            // Write the new log
            String logId = logsRef.push().getKey();
            if (logId == null) return;

            logsRef.child(logId).child("location").setValue(location);
            logsRef.child(logId).child("timestamp").setValue(System.currentTimeMillis());
        });
    }

    private void writeHistoryLog(String location) {
        DatabaseReference logsRef = FirebaseDatabase
                .getInstance(DB_URL)
                .getReference("smartcane/history_logs");

        String logId = logsRef.push().getKey();
        if (logId == null) return;

        logsRef.child(logId).child("location").setValue(location);
        logsRef.child(logId).child("timestamp").setValue(System.currentTimeMillis());
    }

    /**
     * Listens for the emergency flag set by the CANE (hardware / ESP32).
     * When the cane flips emergency = true, this:
     *   1. Resets the flag immediately
     *   2. Fetches latest location, reverse-geocodes it
     *   3. Writes an emergency log
     *   4. Opens EmergencyActivity for the caregiver
     */
    private void attachEmergencyListener() {
        dbRef.child("emergency").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = snapshot.getValue(Boolean.class);

                // Only react when the cane sets the flag to true
                if (emergency == null || !emergency) return;

                // Reset the flag immediately so we don't handle the same event twice
                dbRef.child("emergency").setValue(false);

                // Handle the incoming emergency
                handleIncomingEmergency();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    /**
     * Runs the emergency response flow when triggered by the cane.
     * Never called from a UI tap.
     */
    private void handleIncomingEmergency() {
        dbRef.child("location").get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Double lat = task.getResult().child("lat").getValue(Double.class);
                        Double lng = task.getResult().child("lng").getValue(Double.class);

                        if (lat != null && lng != null) {
                            final double fLat = lat;
                            final double fLng = lng;

                            LocationHelper.reverseGeocode(lat, lng,
                                    new LocationHelper.ReverseGeocodeCallback() {
                                        @Override
                                        public void onAddressFound(String address) {
                                            writeEmergencyLog(address);
                                            startActivity(new Intent(
                                                    MainActivity.this, EmergencyActivity.class));
                                        }

                                        @Override
                                        public void onError() {
                                            writeEmergencyLog(String.format(
                                                    "%.4f° N, %.4f° E", fLat, fLng));
                                            startActivity(new Intent(
                                                    MainActivity.this, EmergencyActivity.class));
                                        }
                                    });
                            return;
                        }
                    }

                    writeEmergencyLog("Location unavailable");
                    startActivity(new Intent(MainActivity.this, EmergencyActivity.class));
                });
    }

    private void listenToFirebase() {
        dbRef = FirebaseDatabase.getInstance(DB_URL).getReference("smartcane");

        // Connection status
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

        // Bootstrap: handle any leftover emergency = true from a previous session,
        // then attach the live listener.
        dbRef.child("emergency").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = snapshot.getValue(Boolean.class);
                if (emergency != null && emergency) {
                    dbRef.child("emergency").setValue(false);
                    handleIncomingEmergency();  // handle emergency missed while app was closed
                }
                attachEmergencyListener();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                attachEmergencyListener();
            }
        });

        // Last known location
        dbRef.child("location").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                if (lat == null || lng == null) return;
                tvLastLocation.setText("Locating...");

                LocationHelper.reverseGeocode(lat, lng,
                        new LocationHelper.ReverseGeocodeCallback() {
                            @Override
                            public void onAddressFound(String address) {
                                tvLastLocation.setText(address);

                                // Read last log time from SharedPreferences
                                long lastLogTime = getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                                        .getLong("last_history_log_time", 0);

                                long now = System.currentTimeMillis();
                                if (now - lastLogTime >= HISTORY_LOG_INTERVAL) {
                                    getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                                            .edit()
                                            .putLong("last_history_log_time", now)
                                            .apply();
                                    writeHistoryLog(address);
                                }
                            }

                            @Override
                            public void onError() {
                                String coords = String.format("%.4f° N, %.4f° E", lat, lng);
                                tvLastLocation.setText(coords);

                                long lastLogTime = getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                                        .getLong("last_history_log_time", 0);

                                long now = System.currentTimeMillis();
                                if (now - lastLogTime >= HISTORY_LOG_INTERVAL) {
                                    getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                                            .edit()
                                            .putLong("last_history_log_time", now)
                                            .apply();
                                    writeHistoryLog(coords);
                                }
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

        // Emergency card is now VIEW-ONLY — opens the log screen.
        // The trigger comes from the cane (hardware) via Firebase.
        btnEmergency.setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyActivity.class)));

        // TEMPORARY TEST BUTTON — remove after hardware is integrated.
        // Simulates the cane setting emergency = true in Firebase.
        findViewById(R.id.btn_test_emergency).setOnClickListener(v ->
                dbRef.child("emergency").setValue(true));
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
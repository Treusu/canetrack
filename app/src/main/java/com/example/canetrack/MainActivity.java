package com.example.canetrack;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
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

    // Emergency dialog state
    private AlertDialog emergencyDialog;

    // Receives broadcast from the background service when an emergency
    // is detected (or dismissed by the caregiver). Shows/hides the dialog.
    private final BroadcastReceiver emergencyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("EMERGENCY_TRIGGERED".equals(action)) {
                showEmergencyDialog();
            } else if ("EMERGENCY_DISMISSED".equals(action)) {
                dismissEmergencyDialog();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        listenToFirebase();
        setButtonListeners();

        // Start the background monitoring service
        Intent serviceIntent = new Intent(this, CaneTrackAlertService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
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

    // =====================================================================
    // EMERGENCY DIALOG LIFECYCLE
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("EMERGENCY_TRIGGERED");
        filter.addAction("EMERGENCY_DISMISSED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(emergencyReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(emergencyReceiver, filter, RECEIVER_NOT_EXPORTED);
            }
        }

        boolean alerting = CaneTrackAlertService.alertActive
                || getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .getBoolean("alert_active", false);

        if (alerting) {
            showEmergencyDialog();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(emergencyReceiver);
    }

    private void showEmergencyDialog() {
        // Dismiss existing dialog if somehow showing already
        if (emergencyDialog != null && emergencyDialog.isShowing()) {
            emergencyDialog.dismiss();
        }

        emergencyDialog = new AlertDialog.Builder(this)
                .setTitle("EMERGENCY ALERT")
                .setMessage("The smart cane user needs help!\n\nCheck their location immediately.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setCancelable(false) // can't dismiss by tapping outside
                .setPositiveButton("View Location", (dialog, which) -> {
                    dismissEmergencyAlert();
                    startActivity(new Intent(this, TrackerActivity.class));
                })
                .setNegativeButton("Dismiss", (dialog, which) -> {
                    dismissEmergencyAlert();
                })
                .create();

        // Make dialog appear even on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            emergencyDialog.getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        emergencyDialog.show();
    }

    private void dismissEmergencyDialog() {
        if (emergencyDialog != null && emergencyDialog.isShowing()) {
            emergencyDialog.dismiss();
        }
    }

    private void dismissEmergencyAlert() {
        // Tell the service to stop sound + vibration directly.
        Intent stopIntent = new Intent(this, CaneTrackAlertService.class);
        stopIntent.setAction(CaneTrackAlertService.ACTION_STOP_ALERT);
        startService(stopIntent);

        Intent dismissed = new Intent("EMERGENCY_DISMISSED");
        dismissed.setPackage(getPackageName());
        sendBroadcast(dismissed);

        dismissEmergencyDialog();
    }

    // =====================================================================
    // SHAPE-AGNOSTIC VALUE EXTRACTORS
    //
    // The ESP32 firmware is fixed and we don't know exactly what JSON shape
    // it writes at /smartcane/status and /smartcane/emergency. Rather than
    // assume, we walk the snapshot manually. These helpers never call
    // getValue(X.class) on a node whose shape we're unsure of, so they
    // can't throw a DatabaseException the way the old code did.
    // =====================================================================

    private String extractStatusString(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        Object raw = snapshot.getValue();
        if (raw instanceof String) {
            return (String) raw;
        }

        String preferred = findStatusInChildren(snapshot, 0);
        if (preferred != null) return preferred;

        return findAnyStringInChildren(snapshot, 0);
    }

    private String findStatusInChildren(DataSnapshot snapshot, int depth) {
        if (depth > 3) return null;
        for (DataSnapshot child : snapshot.getChildren()) {
            Object v = child.getValue();
            if (v instanceof String) {
                String s = (String) v;
                if ("connected".equals(s) || "disconnected".equals(s)) {
                    return s;
                }
            } else if (v instanceof java.util.Map) {
                String nested = findStatusInChildren(child, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private String findAnyStringInChildren(DataSnapshot snapshot, int depth) {
        if (depth > 3) return null;
        for (DataSnapshot child : snapshot.getChildren()) {
            Object v = child.getValue();
            if (v instanceof String) {
                return (String) v;
            } else if (v instanceof java.util.Map) {
                String nested = findAnyStringInChildren(child, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private Boolean extractEmergencyFlag(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        Object raw = snapshot.getValue();
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String)  return Boolean.parseBoolean((String) raw);

        return findBoolInChildren(snapshot, 0);
    }

    private Boolean findBoolInChildren(DataSnapshot snapshot, int depth) {
        if (depth > 3) return null;
        for (DataSnapshot child : snapshot.getChildren()) {
            Object v = child.getValue();
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String)  return Boolean.parseBoolean((String) v);
            if (v instanceof java.util.Map) {
                Boolean nested = findBoolInChildren(child, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    // =====================================================================
    // WRITE HELPERS
    // =====================================================================

    private void writeEmergencyLog(String location) {
        DatabaseReference logsRef = FirebaseDatabase
                .getInstance(DB_URL)
                .getReference("smartcane/emergency_logs");

        getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .edit()
                .putLong("last_emergency_handled", System.currentTimeMillis())
                .apply();

        logsRef.get().addOnSuccessListener(snapshot -> {
            long count = snapshot.getChildrenCount();

            if (count >= 10) {
                DataSnapshot oldest = snapshot.getChildren().iterator().next();
                oldest.getRef().removeValue();
            }

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

    // =====================================================================
    // EMERGENCY LISTENERS
    // =====================================================================

    private void attachEmergencyListener() {
        dbRef.child("emergency").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = extractEmergencyFlag(snapshot);

                if (emergency == null || !emergency) return;

                dbRef.child("emergency").setValue(false);

                handleIncomingEmergency();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

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
                                        }

                                        @Override
                                        public void onError() {
                                            writeEmergencyLog(String.format(
                                                    "%.4f° N, %.4f° E", fLat, fLng));
                                        }
                                    });
                            return;
                        }
                    }

                    writeEmergencyLog("Location unavailable");
                });
    }

    // =====================================================================
    // FIREBASE LISTENERS
    // =====================================================================

    private void listenToFirebase() {
        dbRef = FirebaseDatabase.getInstance(DB_URL).getReference("smartcane");

        dbRef.child("status").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String status = extractStatusString(snapshot);
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

        // Bootstrap emergency flag, then attach live listener
        dbRef.child("emergency").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = extractEmergencyFlag(snapshot);
                if (emergency != null && emergency) {
                    dbRef.child("emergency").setValue(false);
                    handleIncomingEmergency();
                }
                attachEmergencyListener();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                attachEmergencyListener();
            }
        });

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

    // =====================================================================
    // BUTTONS & PERMISSIONS
    // =====================================================================

    private void setButtonListeners() {
        btnTrack.setOnClickListener(v ->
                startActivity(new Intent(this, TrackerActivity.class)));

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        btnEmergency.setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyActivity.class)));
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
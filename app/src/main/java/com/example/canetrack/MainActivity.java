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

    // =====================================================================
    // SHAPE-AGNOSTIC VALUE EXTRACTORS
    //
    // The ESP32 firmware is fixed and we don't know exactly what JSON shape
    // it writes at /smartcane/status and /smartcane/emergency. Rather than
    // assume, we walk the snapshot manually. These helpers never call
    // getValue(X.class) on a node whose shape we're unsure of, so they
    // can't throw a DatabaseException the way the old code did.
    // =====================================================================

    /**
     * Pulls a status string out of /smartcane/status.
     * Handles:
     *   - "connected"                  (bare string)
     *   - {token: "connected"}         (object, any single key)
     *   - {value: "connected"}         (object, any single key)
     *   - {anything: "connected"}      (object, any single key)
     *   - {state: "connected", ts: ..} (object, multiple keys)
     *   - nested {a: {b: "connected"}} (object, nested)
     * Returns null if nothing usable is found.
     */
    private String extractStatusString(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        // Case 1: the value at this path is a plain string.
        Object raw = snapshot.getValue();
        if (raw instanceof String) {
            return (String) raw;
        }

        // Case 2: the value is an object / map. Look through children
        // (and grandchildren) for a string that looks like our status.
        String preferred = findStatusInChildren(snapshot, 0);
        if (preferred != null) return preferred;

        // Case 3: fall back to any string anywhere in the subtree.
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

    /**
     * Pulls a boolean flag out of /smartcane/emergency.
     * Handles:
     *   - true / false                 (bare boolean)
     *   - "true" / "false"             (bare string)
     *   - {token: true}                (object)
     *   - {value: true}                (object)
     *   - {anything: true}             (object)
     * Returns null if nothing usable is found.
     */
    private Boolean extractEmergencyFlag(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;

        Object raw = snapshot.getValue();
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String)  return Boolean.parseBoolean((String) raw);

        // It's an object. Walk children looking for a boolean or a string.
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
    // WRITE HELPERS (unchanged from original)
    // =====================================================================

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

    // =====================================================================
    // EMERGENCY LISTENERS
    // =====================================================================

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
                Boolean emergency = extractEmergencyFlag(snapshot);

                // Only react when the cane sets the flag to true
                if (emergency == null || !emergency) return;

                // Reset the flag immediately so we don't handle the same event twice
                dbRef.child("emergency").setValue(false);

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

    // =====================================================================
    // FIREBASE LISTENERS
    // =====================================================================

    private void listenToFirebase() {
        dbRef = FirebaseDatabase.getInstance(DB_URL).getReference("smartcane");

        // ---------------------------------------------------------------
        // Connection status — HARDENED to accept any shape the firmware
        // might write. The crash we saw (HashMap -> String) was because
        // the app assumed the value was a bare string. Now it walks the
        // snapshot and finds whatever string is inside.
        // ---------------------------------------------------------------
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

        // Bootstrap: handle any leftover emergency = true from a previous session,
        // then attach the live listener.
        dbRef.child("emergency").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = extractEmergencyFlag(snapshot);
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

        // Emergency card is now VIEW-ONLY — opens the log screen.
        // The trigger comes from the cane (hardware) via Firebase.
        btnEmergency.setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyActivity.class)));

        /* TEMPORARY TEST BUTTON — remove after hardware is integrated.
        // Simulates the cane setting emergency = true in Firebase.
        findViewById(R.id.btn_test_emergency).setOnClickListener(v ->
                dbRef.child("emergency").setValue(true));*/
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
package com.example.canetrack;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import android.preference.PreferenceManager;

public class TrackerActivity extends AppCompatActivity {

    private static final double DEFAULT_SAFE_ZONE_RADIUS = 50.0;
    private static final String DB_URL = "https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app";

    private MapView mapView;
    private TextView tvAddress;       // replaces tvLatitude + tvLongitude
    private TextView tvSafeZoneStatus;
    private TextView tvLastUpdated;
    private View     indicatorSafeZone;

    private DatabaseReference locationRef;
    private DatabaseReference safeZoneRef;

    private Marker userMarker;
    private Marker safeZoneCenterMarker;
    private Polygon safeZoneCircle;

    private double safeZoneLat    = 0;
    private double safeZoneLng    = 0;
    private double safeZoneRadius = DEFAULT_SAFE_ZONE_RADIUS;

    private double currentLat = 0;
    private double currentLng = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(
                getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
        );
        Configuration.getInstance().setUserAgentValue("CaneTrack/1.0 (com.example.canetrack)");

        setContentView(R.layout.tracker_activity);

        initViews();
        setupMap();
        listenToSafeZone();
        listenToLocation();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_set_safe_zone).setOnClickListener(v -> {
            Toast.makeText(this,
                    "Long-press anywhere on the map to set the safe zone.",
                    Toast.LENGTH_LONG).show();
            enableSafeZonePlacement();
        });
    }

    private void initViews() {
        mapView           = findViewById(R.id.map_view);
        tvAddress         = findViewById(R.id.tv_address);      // new single address field
        tvSafeZoneStatus  = findViewById(R.id.tv_safe_zone_status);
        tvLastUpdated     = findViewById(R.id.tv_last_updated);
        indicatorSafeZone = findViewById(R.id.indicator_safe_zone);
    }

    private void setupMap() {
        XYTileSource hotTiles = new XYTileSource(
                "HOT", 1, 18, 256, ".png",
                new String[]{
                        "https://a.tile.openstreetmap.fr/hot/",
                        "https://b.tile.openstreetmap.fr/hot/",
                        "https://c.tile.openstreetmap.fr/hot/"
                }
        );
        mapView.setTileSource(hotTiles);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(17.0);

        userMarker = new Marker(mapView);
        userMarker.setTitle("Smart Cane User");
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mapView.getOverlays().add(userMarker);

        safeZoneCenterMarker = new Marker(mapView);
        safeZoneCenterMarker.setTitle("Safe Zone Center");
        safeZoneCenterMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        safeZoneCenterMarker.setVisible(false);
        mapView.getOverlays().add(safeZoneCenterMarker);
    }

    //Read safe zone from Firebase
    private void listenToSafeZone() {
        safeZoneRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("smartcane/safezone");

        safeZoneRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat    = snapshot.child("lat").getValue(Double.class);
                Double lng    = snapshot.child("lng").getValue(Double.class);
                Double radius = snapshot.child("radius").getValue(Double.class);

                if (lat == null || lng == null) return;

                safeZoneLat    = lat;
                safeZoneLng    = lng;
                safeZoneRadius = (radius != null) ? radius : DEFAULT_SAFE_ZONE_RADIUS;

                drawSafeZoneOnMap(safeZoneLat, safeZoneLng, safeZoneRadius);
                checkSafeZoneStatus();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(TrackerActivity.this,
                        "Failed to load safe zone.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    //Read live GPS location from Firebase
    private void listenToLocation() {
        locationRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("smartcane/location");

        locationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                if (lat == null || lng == null) return;

                currentLat = lat;
                currentLng = lng;

                tvLastUpdated.setText("Just now");
                tvAddress.setText("Locating...");

                LocationHelper.reverseGeocode(lat, lng,
                        new LocationHelper.ReverseGeocodeCallback() {
                            @Override
                            public void onAddressFound(String address) {
                                tvAddress.setText(address);
                            }

                            @Override
                            public void onError() {
                                // Fallback to raw latitude and longitude
                                tvAddress.setText(
                                        String.format("%.4f° N, %.4f° E", lat, lng));
                            }
                        });

                // Move marker on map
                GeoPoint point = new GeoPoint(lat, lng);
                userMarker.setPosition(point);
                mapView.getController().animateTo(point);
                mapView.invalidate();

                checkSafeZoneStatus();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvLastUpdated.setText("Update failed");
            }
        });
    }

    //Draw safe zone circle on map
    private void drawSafeZoneOnMap(double lat, double lng, double radius) {
        if (safeZoneCircle != null) {
            mapView.getOverlays().remove(safeZoneCircle);
        }

        safeZoneCircle = new Polygon();
        safeZoneCircle.setPoints(
                Polygon.pointsAsCircle(new GeoPoint(lat, lng), radius));
        safeZoneCircle.setFillColor(Color.argb(40, 79, 142, 247));
        safeZoneCircle.setStrokeColor(Color.argb(180, 79, 142, 247));
        safeZoneCircle.setStrokeWidth(2f);
        mapView.getOverlays().add(safeZoneCircle);

        safeZoneCenterMarker.setPosition(new GeoPoint(lat, lng));
        safeZoneCenterMarker.setVisible(true);
        mapView.invalidate();
    }

    //Check if user is inside the safe zone
    private void checkSafeZoneStatus() {
        if (safeZoneLat == 0 && safeZoneLng == 0) return;
        if (currentLat == 0 && currentLng == 0) return;

        float[] result = new float[1];
        android.location.Location.distanceBetween(
                currentLat, currentLng,
                safeZoneLat, safeZoneLng,
                result);

        boolean inside = result[0] <= safeZoneRadius;

        tvSafeZoneStatus.setText(inside ? "Inside Safe Zone" : "Outside Safe Zone!");
        tvSafeZoneStatus.setTextColor(inside
                ? Color.parseColor("#22c55e")
                : Color.parseColor("#ef4444"));

                indicatorSafeZone.setBackgroundResource(inside
                        ? R.drawable.circle_green
                        : R.drawable.circle_red);
    }

    //Caregiver long-presses map to place new safe zone
    private void enableSafeZonePlacement() {
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                DatabaseReference ref = FirebaseDatabase.getInstance(DB_URL)
                        .getReference("smartcane/safezone");
                ref.child("lat").setValue(p.getLatitude());
                ref.child("lng").setValue(p.getLongitude());
                ref.child("radius").setValue(DEFAULT_SAFE_ZONE_RADIUS);

                Toast.makeText(TrackerActivity.this,
                        "Safe zone updated!", Toast.LENGTH_SHORT).show();

                mapView.getOverlays().remove(this);
                return true;
            }
        });

        mapView.getOverlays().add(eventsOverlay);
        mapView.invalidate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
    }
}
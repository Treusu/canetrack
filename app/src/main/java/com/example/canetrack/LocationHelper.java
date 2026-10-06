package com.example.canetrack;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class LocationHelper {

    public interface ReverseGeocodeCallback {
        void onAddressFound(String address);
        void onError();
    }

    // Single background thread for all geocoding requests
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Handler to post results back to the main (UI) thread
    private static final Handler mainHandler  = new Handler(Looper.getMainLooper());

    public static void reverseGeocode(double lat, double lng,
                                      ReverseGeocodeCallback callback) {
        executor.execute(() -> {
            try {
                String urlStr = "https://nominatim.openstreetmap.org/reverse"
                        + "?format=json"
                        + "&lat=" + lat
                        + "&lon=" + lng
                        + "&zoom=16"
                        + "&addressdetails=1";

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent",
                        "CaneTrack/1.0 (com.example.canetrack)");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    mainHandler.post(callback::onError);
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json    = new JSONObject(sb.toString());
                JSONObject address = json.getJSONObject("address");

                // Pick most specific area name available
                String neighbourhood = address.optString("neighbourhood", "");
                String suburb        = address.optString("suburb", "");
                String village       = address.optString("village", "");
                String city          = address.optString("city", "");
                String town          = address.optString("town", "");
                String municipality  = address.optString("municipality", "");

                String area = !neighbourhood.isEmpty() ? neighbourhood
                        : !suburb.isEmpty()        ? suburb
                        : !village.isEmpty()       ? village
                        : "";

                String cityName = !city.isEmpty()         ? city
                        : !town.isEmpty()         ? town
                        : !municipality.isEmpty() ? municipality
                        : "";

                String readableAddress;
                if (!area.isEmpty() && !cityName.isEmpty()) {
                    readableAddress = area + ", " + cityName;
                } else if (!cityName.isEmpty()) {
                    readableAddress = cityName;
                } else {
                    readableAddress = json.optString("display_name", "Unknown location");
                }

                final String result = readableAddress;
                // Post result back to UI thread
                mainHandler.post(() -> callback.onAddressFound(result));

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(callback::onError);
            }
        });
    }
}
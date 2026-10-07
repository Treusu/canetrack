package com.example.canetrack;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class HistoryActivity extends AppCompatActivity {

    private static final String DB_URL = "https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app";

    private LinearLayout logsContainer;
    private TextView tvEndOfHistory;
    private TextView tvNoLogs;
    private DatabaseReference historyLogsRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        logsContainer  = findViewById(R.id.logs_container);
        tvEndOfHistory = findViewById(R.id.tv_end_of_history);
        tvNoLogs       = findViewById(R.id.tv_no_logs);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        loadHistoryLogs();
    }

    private void loadHistoryLogs() {
        historyLogsRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("smartcane/history_logs");

        historyLogsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                logsContainer.removeAllViews();

                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    tvNoLogs.setVisibility(View.VISIBLE);
                    tvEndOfHistory.setVisibility(View.GONE);
                    return;
                }

                tvNoLogs.setVisibility(View.GONE);
                tvEndOfHistory.setVisibility(View.VISIBLE);

                // Collect logs and reverse to show newest first
                List<DataSnapshot> logList = new ArrayList<>();
                for (DataSnapshot log : snapshot.getChildren()) {
                    logList.add(log);
                }
                Collections.reverse(logList);

                for (DataSnapshot log : logList) {
                    String location  = log.child("location").getValue(String.class);
                    Long   timestamp = log.child("timestamp").getValue(Long.class);

                    if (location == null) location = "Unknown location";

                    // Format timestamp to PH time
                    String dateTime = "Unknown time";
                    if (timestamp != null) {
                        Date date = new Date(timestamp);
                        SimpleDateFormat sdf = new SimpleDateFormat(
                                "MMM dd, yyyy - hh:mm a", Locale.getDefault());
                        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
                        dateTime = sdf.format(date);
                    }

                    addLogCard(location, dateTime);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvNoLogs.setVisibility(View.VISIBLE);
                tvNoLogs.setText("Failed to load history.");
            }
        });
    }

    private void addLogCard(String location, String dateTime) {
        View card = LayoutInflater.from(this)
                .inflate(R.layout.item_history_log, logsContainer, false);

        TextView tvLocation = card.findViewById(R.id.tv_log_location);
        TextView tvDateTime = card.findViewById(R.id.tv_log_datetime);

        tvLocation.setText(location);
        tvDateTime.setText(dateTime);

        logsContainer.addView(card);
    }
}
package com.example.canetrack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class CaneTrackAlertService extends Service {

    // ── Channel IDs ────────────────────────────────────────────────────────
    private static final String CHANNEL_FOREGROUND = "canetrack_foreground";
    private static final String CHANNEL_EMERGENCY  = "canetrack_emergency";
    private static final int    NOTIF_FOREGROUND   = 10;
    private static final int    NOTIF_EMERGENCY    = 11;

    private static final String DB_URL =
            "https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app";

    // ── CONFIGURE YOUR ALERT SOUND HERE ───────────────────────────────────
    // Option A — use a file from res/raw/ folder:
    //   1. Add your sound file to app/src/main/res/raw/  (e.g. alert.mp3)
    //   2. Change the line below to: R.raw.alert
    //   Example: private static final int ALERT_SOUND_RES_ID = R.raw.alert;
    //
    // Option B — use the default system alarm sound (no file needed):
    //   Leave ALERT_SOUND_RES_ID = -1 and the code will use RingtoneManager
    //
    private static final int ALERT_SOUND_RES_ID = R.raw.alert;; // -1 = use system alarm

    private DatabaseReference emergencyRef;
    private ValueEventListener emergencyListener;
    private MediaPlayer        mediaPlayer;
    private Vibrator           vibrator;

    // ==========================================
    // LIFECYCLE
    // ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Show persistent foreground notification so Android keeps us alive
        startForeground(NOTIF_FOREGROUND, buildForegroundNotification());
        listenForEmergency();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (emergencyRef != null && emergencyListener != null) {
            emergencyRef.removeEventListener(emergencyListener);
        }
        stopAlert();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==========================================
    // FIREBASE EMERGENCY LISTENER
    // ==========================================

    private void listenForEmergency() {
        emergencyRef = FirebaseDatabase
                .getInstance(DB_URL)
                .getReference("smartcane/emergency");

        // Single read on start — silently reset leftover true from previous session
        emergencyRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(emergency)) {
                    // Leftover from previous session — reset silently
                    emergencyRef.setValue(false);
                }
                attachLiveEmergencyListener();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                attachLiveEmergencyListener();
            }
        });
    }

    private void attachLiveEmergencyListener() {
        emergencyListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = snapshot.getValue(Boolean.class);
                if (!Boolean.TRUE.equals(emergency)) return;

                // Reset flag immediately
                emergencyRef.setValue(false);

                // Trigger alert
                triggerEmergencyAlert();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        emergencyRef.addValueEventListener(emergencyListener);
    }

    // ==========================================
    // EMERGENCY ALERT
    // ==========================================

    private void triggerEmergencyAlert() {
        playAlertSound();
        vibratePhone();
        showEmergencyNotification();
        broadcastEmergencyToApp();
    }

    private void playAlertSound() {
        try {
            stopAlert(); // stop any existing sound first

            if (ALERT_SOUND_RES_ID != -1) {
                // ── Option A: play from res/raw/ ──────────────────────────
                mediaPlayer = MediaPlayer.create(this, ALERT_SOUND_RES_ID);
            } else {
                // ── Option B: use system alarm sound ─────────────────────
                Uri alarmUri = android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM);
                if (alarmUri == null) {
                    alarmUri = android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_NOTIFICATION);
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                mediaPlayer.setDataSource(this, alarmUri);
                mediaPlayer.prepare();
            }

            // Max volume
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0);
            }

            mediaPlayer.setLooping(true); // keep playing until dismissed
            mediaPlayer.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void vibratePhone() {
        if (vibrator == null) return;
        // Pattern: wait 0ms, vibrate 1s, wait 0.5s, vibrate 1s — repeat
        long[] pattern = {0, 1000, 500, 1000, 500, 1000};
        vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, 0)); // 0 = repeat from start
    }

    private void showEmergencyNotification() {
        // Tapping the notification opens MainActivity
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Dismiss action — stops the sound and vibration
        Intent dismissIntent = new Intent(this, AlertDismissReceiver.class);
        PendingIntent dismissPending = PendingIntent.getBroadcast(
                this, 0, dismissIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_EMERGENCY)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ EMERGENCY ALERT")
                .setContentText("Smart Cane user needs help! Tap to open app.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Smart Cane user needs help!\nTap to open the app and check their location immediately."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Dismiss", dismissPending)
                .build();

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIF_EMERGENCY, notification);
    }

    private void broadcastEmergencyToApp() {
        // Tells MainActivity to show the popup dialog if app is open
        Intent broadcast = new Intent("EMERGENCY_TRIGGERED");
        sendBroadcast(broadcast);
    }

    // ==========================================
    // STOP ALERT (called by AlertDismissReceiver)
    // ==========================================

    public void stopAlert() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        // Cancel the emergency notification
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIF_EMERGENCY);
    }

    // ==========================================
    // NOTIFICATION CHANNELS
    // ==========================================

    private void createNotificationChannels() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Foreground channel — silent, just keeps service alive
        NotificationChannel foregroundChannel = new NotificationChannel(
                CHANNEL_FOREGROUND,
                "CaneTrack Background",
                NotificationManager.IMPORTANCE_LOW);
        foregroundChannel.setDescription("Monitors emergency alerts in background");
        manager.createNotificationChannel(foregroundChannel);

        // Emergency channel — high priority, makes sound
        NotificationChannel emergencyChannel = new NotificationChannel(
                CHANNEL_EMERGENCY,
                "CaneTrack Emergency",
                NotificationManager.IMPORTANCE_HIGH);
        emergencyChannel.setDescription("Emergency alerts from the smart cane");
        emergencyChannel.enableVibration(true);
        emergencyChannel.setBypassDnd(true); // bypass Do Not Disturb
        manager.createNotificationChannel(emergencyChannel);
    }

    private Notification buildForegroundNotification() {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("CaneTrack")
                .setContentText("Monitoring for emergency alerts...")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}

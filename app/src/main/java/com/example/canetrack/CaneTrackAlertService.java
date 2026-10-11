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
    public static volatile boolean alertActive = false;

    // Action sent by AlertDismissReceiver (and by MainActivity's dialog)
    // to tell the service to stop the alert.
    public static final String ACTION_STOP_ALERT = "STOP_ALERT";

    private static final String DB_URL =
            "https://canetrack-1142c-default-rtdb.asia-southeast1.firebasedatabase.app";

    private static final int ALERT_SOUND_RES_ID = R.raw.alert;; // -1 = use system alarm

    private DatabaseReference emergencyRef;
    private ValueEventListener emergencyListener;
    private MediaPlayer        mediaPlayer;
    private Vibrator           vibrator;

    // Guards against attaching a second Firebase listener on each
    // onStartCommand() call (e.g. every dismiss broadcast).
    private boolean emergencyListenerAttached = false;

    // ==========================================
    // LIFECYCLE
    // ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createNotificationChannels();

        alertActive = getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .getBoolean("alert_active", false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Show the persistent foreground notification so Android keeps us alive.
        // Safe to call repeatedly; Android just updates the same notification.
        startForeground(NOTIF_FOREGROUND, buildForegroundNotification());

        // Handle the "stop the alert" command if this start was triggered by
        // AlertDismissReceiver or the MainActivity dialog.
        if (intent != null && ACTION_STOP_ALERT.equals(intent.getAction())) {
            stopAlert();
            return START_STICKY;
        }

        // Otherwise, this is a normal service start (boot / app launch).
        // Attach the Firebase listener, but only once.
        if (!emergencyListenerAttached) {
            listenForEmergency();
            emergencyListenerAttached = true;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (emergencyRef != null && emergencyListener != null) {
            emergencyRef.removeEventListener(emergencyListener);
        }
        emergencyListenerAttached = false;
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

        emergencyRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean emergency = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(emergency)) {
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

                emergencyRef.setValue(false);
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
        alertActive = true;
        getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("alert_active", true)
                .apply();

        playAlertSound();
        vibratePhone();
        showEmergencyNotification();
        broadcastEmergencyToApp();
    }

    private void playAlertSound() {
        try {
            stopAlert();

            if (ALERT_SOUND_RES_ID != -1) {
                mediaPlayer = MediaPlayer.create(this, ALERT_SOUND_RES_ID);
            } else {
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

            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0);
            }

            mediaPlayer.setLooping(true);
            mediaPlayer.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void vibratePhone() {
        if (vibrator == null) return;
        long[] pattern = {0, 1000, 500, 1000, 500, 1000};
        vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, 0));
    }

    private void showEmergencyNotification() {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Dismiss action — send the same STOP_ALERT command the dialog uses.
        Intent dismissIntent = new Intent(this, AlertDismissReceiver.class);
        PendingIntent dismissPending = PendingIntent.getBroadcast(
                this, 0, dismissIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_EMERGENCY)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠EMERGENCY ALERT")
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
        Intent broadcast = new Intent("EMERGENCY_TRIGGERED");
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    //stopping the alert

    public void stopAlert() {
        alertActive = false;

        getSharedPreferences("canetrack_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("alert_active", false)
                .apply();

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (IllegalStateException ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (vibrator != null) {
            vibrator.cancel();
        }

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

        NotificationChannel foregroundChannel = new NotificationChannel(
                CHANNEL_FOREGROUND,
                "CaneTrack Background",
                NotificationManager.IMPORTANCE_LOW);
        foregroundChannel.setDescription("Monitors emergency alerts in background");
        manager.createNotificationChannel(foregroundChannel);

        NotificationChannel emergencyChannel = new NotificationChannel(
                CHANNEL_EMERGENCY,
                "CaneTrack Emergency",
                NotificationManager.IMPORTANCE_HIGH);
        emergencyChannel.setDescription("Emergency alerts from the smart cane");
        emergencyChannel.enableVibration(true);
        emergencyChannel.setBypassDnd(true);
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
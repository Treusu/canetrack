package com.example.canetrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Auto-restart the monitoring service after phone reboot
            Intent serviceIntent = new Intent(context, CaneTrackAlertService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}

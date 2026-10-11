package com.example.canetrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlertDismissReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Tell the service to stop the sound and vibration
        Intent stopAlert = new Intent(context, CaneTrackAlertService.class);
        stopAlert.setAction("STOP_ALERT");
        context.startService(stopAlert);

        // Also broadcast to MainActivity to dismiss the dialog if open
        Intent broadcast = new Intent("EMERGENCY_DISMISSED");
        context.sendBroadcast(broadcast);
    }
}

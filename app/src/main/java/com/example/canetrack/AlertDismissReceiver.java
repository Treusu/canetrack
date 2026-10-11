package com.example.canetrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlertDismissReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Tell the service to stop the sound and vibration
        Intent stopAlert = new Intent(context, CaneTrackAlertService.class);
        stopAlert.setAction(CaneTrackAlertService.ACTION_STOP_ALERT);
        context.startService(stopAlert);

        Intent broadcast = new Intent("EMERGENCY_DISMISSED");
        broadcast.setPackage(context.getPackageName());
        context.sendBroadcast(broadcast);
    }
}
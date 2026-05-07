package com.example.castlynk.legacy;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationSyncService extends NotificationListenerService {

    private static final String TAG = "NotificationSync";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        String packageName = sbn.getPackageName();

        Log.d(TAG, "Notification from " + packageName + ": " + title + " - " + text);

        HostService hostService = HostService.getInstance();
        if (hostService != null) {
            hostService.broadcastNotification(packageName, title, text != null ? text.toString() : "");
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optional: Notify client to remove the notification
    }
}

package com.example.castlynk.legacy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

public class NetworkHelper {

    public static String getWifiName(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null) {
                String ssid = info.getSSID();
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (ssid.equals("<unknown ssid>")) {
                    return "WiFi Connected";
                }
                return ssid;
            }
        }
        
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
            return "Local Network";
        }
        
        return "Not Connected";
    }

    public static String getLocalIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            return String.format("%d.%d.%d.%d",
                    (ipAddress & 0xff),
                    (ipAddress >> 8 & 0xff),
                    (ipAddress >> 16 & 0xff),
                    (ipAddress >> 24 & 0xff));
        }
        return "Unknown IP";
    }

    public static String getSignalStrength(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null) {
                int level = WifiManager.calculateSignalLevel(info.getRssi(), 5);
                switch (level) {
                    case 4: return "Excellent";
                    case 3: return "Good";
                    case 2: return "Fair";
                    case 1: return "Weak";
                    default: return "No Signal";
                }
            }
        }
        return "N/A";
    }
}

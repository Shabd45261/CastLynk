package com.example.castlynk.legacy;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_FILE_PICK = 1002;
    private static final int PERMISSION_REQUEST_CODE = 1003;
    
    private MediaProjectionManager projectionManager;
    private LinearLayout notificationContainer;
    private TextView tvNetworkName;
    private TextView tvDeviceName;
    private String connectedHostIp;
    private static MainActivity instance;

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        notificationContainer = findViewById(R.id.notification_container);
        tvNetworkName = findViewById(R.id.tv_network_name);
        tvDeviceName = findViewById(R.id.tv_device_name);

        updateNetworkInfo();
        tvDeviceName.setText(android.os.Build.MODEL);

        checkAndRequestPermissions();

        // Host Card
        findViewById(R.id.card_host).setOnClickListener(v -> startHostMode());

        // Client Card
        findViewById(R.id.card_client).setOnClickListener(v -> startClientMode());

        // Manual Connect (Change Network Button reused)
        findViewById(R.id.btn_change_network).setOnClickListener(v -> showManualConnectDialog());

        // Settings Button
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        // Clear Notifications
        findViewById(R.id.btn_clear_notifications).setOnClickListener(v -> {
            notificationContainer.removeAllViews();
            Toast.makeText(MainActivity.this, "Cleared", Toast.LENGTH_SHORT).show();
        });
        
        startService(new Intent(this, FileTransferService.class));
    }

    private void showManualConnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.enter_host_ip);
        
        final EditText input = new EditText(this);
        input.setHint("192.168.x.x");
        builder.setView(input);

        builder.setPositiveButton(R.string.connect, (dialog, which) -> {
            String ip = input.getText().toString().trim();
            if (!ip.isEmpty()) {
                Intent intent = new Intent(this, MirrorActivity.class);
                intent.putExtra("host_ip", ip);
                intent.putExtra("port", 8888);
                startActivity(intent);
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void checkAndRequestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                updateNetworkInfo();
            } else {
                Toast.makeText(this, "Permissions required for Network Discovery", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateNetworkInfo() {
        String wifiName = NetworkHelper.getWifiName(this);
        tvNetworkName.setText(wifiName);
    }

    private void startHostMode() {
        Intent intent = new Intent(this, HostActivity.class);
        startActivity(intent);
    }

    private void startClientMode() {
        Intent intent = new Intent(this, ClientActivity.class);
        startActivity(intent);
    }

    public void addNotification(String pkg, String title, String message) {
        runOnUiThread(() -> {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_notification, notificationContainer, false);
            ((TextView) itemView.findViewById(R.id.tv_app_name)).setText(pkg.substring(pkg.lastIndexOf('.') + 1).toUpperCase());
            ((TextView) itemView.findViewById(R.id.tv_title)).setText(title);
            ((TextView) itemView.findViewById(R.id.tv_message)).setText(message);
            
            notificationContainer.addView(itemView, 0);
            if (notificationContainer.getChildCount() > 5) {
                notificationContainer.removeViewAt(5);
            }
        });
    }

    public void connectToNotifications(String hostIp) {
        this.connectedHostIp = hostIp;
        new Thread(() -> {
            try (Socket socket = new Socket(hostIp, 9998);
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                while (!socket.isClosed()) {
                    String pkg = in.readUTF();
                    String title = in.readUTF();
                    String msg = in.readUTF();
                    addNotification(pkg, title, msg);
                }
            } catch (IOException e) {
                Log.e("MainActivity", "Notification listener error", e);
            }
        }).start();
    }
}

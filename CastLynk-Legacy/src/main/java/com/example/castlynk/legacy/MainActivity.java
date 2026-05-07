package com.example.castlynk.legacy;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_FILE_PICK = 1002;
    private MediaProjectionManager projectionManager;
    private LinearLayout notificationContainer;
    private TextView tvNetworkName;
    private TextView tvSignalQuality;
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
        tvSignalQuality = findViewById(R.id.tv_signal_quality);
        tvDeviceName = findViewById(R.id.tv_device_name);

        updateNetworkInfo();
        tvDeviceName.setText(android.os.Build.MODEL);

        // Host Card
        findViewById(R.id.card_host).setOnClickListener(v -> startHostMode());

        // Client Card
        findViewById(R.id.card_client).setOnClickListener(v -> startClientMode());

        // Change Network Button
        findViewById(R.id.btn_change_network).setOnClickListener(v -> 
            pickFile());

        // Clear Notifications
        findViewById(R.id.btn_clear_notifications).setOnClickListener(v -> {
            notificationContainer.removeAllViews();
            Toast.makeText(MainActivity.this, "Cleared", Toast.LENGTH_SHORT).show();
        });
        
        startService(new Intent(this, FileTransferService.class));
    }

    private void updateNetworkInfo() {
        String wifiName = NetworkHelper.getWifiName(this);
        String signal = NetworkHelper.getSignalStrength(this);
        tvNetworkName.setText(wifiName);
        tvSignalQuality.setText("Signal: " + signal);
    }

    private void pickFile() {
        if (connectedHostIp == null) {
            Toast.makeText(this, "Connect to a host first to share files", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE_PICK);
    }

    private void startHostMode() {
        Intent intent = new Intent(this, HostActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, HostService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", data);
                startService(serviceIntent);
            }
        } else if (requestCode == REQUEST_FILE_PICK && resultCode == RESULT_OK && data != null) {
            handleFileSelected(data.getData());
        }
    }

    private void handleFileSelected(Uri uri) {
        try {
            File file = getFileFromUri(uri);
            if (connectedHostIp != null) {
                FileTransferService.sendFile(connectedHostIp, file);
                Toast.makeText(this, "Sending file...", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e("MainActivity", "File handling error", e);
        }
    }

    private File getFileFromUri(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        File file = new File(getCacheDir(), "temp_file");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
        return file;
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

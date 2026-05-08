package com.example.castlynk.legacy;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ClientActivity extends AppCompatActivity {

    private static final String TAG = "ClientActivity";
    private static final String CLIENT_SERVICE_TYPE = "_castlynk_client._tcp"; // Removed dot

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private ServerSocket pairingSocket;
    private boolean isWaiting = true;

    private TextView tvStatus;
    private ProgressBar pbDiscovery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        tvStatus = findViewById(R.id.tv_status);
        pbDiscovery = findViewById(R.id.pb_discovery);

        tvStatus.setText("Waiting for Host to connect...");
        
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        startPairingServer();
    }

    private void startPairingServer() {
        new Thread(() -> {
            try {
                pairingSocket = new ServerSocket(0); // Pick available port
                int port = pairingSocket.getLocalPort();
                Log.d(TAG, "Pairing server started on port: " + port);
                
                registerClientService(port);

                while (isWaiting && !pairingSocket.isClosed()) {
                    Socket hostSocket = pairingSocket.accept();
                    handlePairingRequest(hostSocket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Pairing server error", e);
            }
        }).start();
    }

    private void registerClientService(int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        // More unique service name for older devices
        serviceInfo.setServiceName("CastLynkClient-" + Build.MODEL + "-" + (System.currentTimeMillis() % 1000));
        serviceInfo.setServiceType(CLIENT_SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Client Service registered: " + serviceInfo.getServiceName());
                runOnUiThread(() -> tvStatus.setText("Visible on network: " + serviceInfo.getServiceName()));
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {}

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void handlePairingRequest(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String hostName = in.readUTF();
            String hostIp = socket.getInetAddress().getHostAddress();

            runOnUiThread(() -> {
                showPairingConfirmation(hostName, hostIp);
            });
        } catch (IOException e) {
            Log.e(TAG, "Pairing request error", e);
        }
    }

    private void showPairingConfirmation(String hostName, String hostIp) {
        new AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("Device " + hostName + " wants to mirror to your screen. Accept?")
                .setPositiveButton("Accept", (dialog, which) -> {
                    startMirroring(hostIp);
                })
                .setNegativeButton("Decline", null)
                .show();
    }

    private void startMirroring(String hostIp) {
        isWaiting = false;
        
        MainActivity main = MainActivity.getInstance();
        if (main != null) {
            main.connectToNotifications(hostIp);
        }

        Intent intent = new Intent(this, MirrorActivity.class);
        intent.putExtra("host_ip", hostIp);
        intent.putExtra("port", 8888); 
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        isWaiting = false;
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
        }
        try {
            if (pairingSocket != null) pairingSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }
}

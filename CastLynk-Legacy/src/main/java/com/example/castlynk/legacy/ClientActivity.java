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
    private static final String CLIENT_SERVICE_TYPE = "_castlynk_client._tcp.";

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
                pairingSocket = new ServerSocket(0); // Random port
                int port = pairingSocket.getLocalPort();
                registerClientService(port);

                Log.d(TAG, "Pairing server started on port: " + port);

                while (isWaiting) {
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
        serviceInfo.setServiceName("Waiting-Client-" + Build.MODEL);
        serviceInfo.setServiceType(CLIENT_SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Client Service registered");
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
            e.printStackTrace();
        }
    }

    private void showPairingConfirmation(String hostName, String hostIp) {
        new AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("Device " + hostName + " (" + hostIp + ") wants to mirror to your screen. Accept?")
                .setPositiveButton("Accept", (dialog, which) -> {
                    startMirroring(hostIp);
                })
                .setNegativeButton("Decline", (dialog, which) -> {
                    Toast.makeText(this, "Pairing Declined", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void startMirroring(String hostIp) {
        isWaiting = false;
        // In a real flow, we'd send an "OK" back to the host here.
        // For this demo, let's just go to MirrorActivity.
        
        MainActivity main = MainActivity.getInstance();
        if (main != null) {
            main.connectToNotifications(hostIp);
        }

        Intent intent = new Intent(this, MirrorActivity.class);
        intent.putExtra("host_ip", hostIp);
        intent.putExtra("port", 8888); // Need to coordinate this port
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        isWaiting = false;
        if (nsdManager != null && registrationListener != null) {
            nsdManager.unregisterService(registrationListener);
        }
        try {
            if (pairingSocket != null) pairingSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }
}

package com.example.castlynk.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class HostActivity extends AppCompatActivity {

    private static final String TAG = "HostActivity";
    private static final String CLIENT_SERVICE_TYPE = "_castlynk_client._tcp";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private List<NsdServiceInfo> discoveredClients = new ArrayList<>();
    private List<String> clientNames = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private TextView tvStatus;
    private ProgressBar pbDiscovery;
    private ListView lvClients;
    private NsdServiceInfo selectedClient;
    private boolean isDiscoveryStarted = false;

    private final ActivityResultLauncher<Intent> projectionLauncher = 
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Log.d(TAG, "MediaProjection permission granted");
                startMirroring(result.getResultCode(), result.getData());
            } else {
                Log.e(TAG, "MediaProjection permission denied or cancelled");
                Toast.makeText(this, "Permission denied. Screen capture cannot start.", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host);

        tvStatus = findViewById(R.id.tv_host_status);
        pbDiscovery = findViewById(R.id.pb_host_discovery);
        lvClients = findViewById(R.id.lv_clients);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, clientNames);
        lvClients.setAdapter(adapter);

        String localIp = NetworkHelper.getLocalIpAddress(this);
        tvStatus.setText("Host IP: " + localIp + "\nScanning for waiting clients...");

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        initializeDiscoveryListener();

        lvClients.setOnItemClickListener((parent, view, position, id) -> {
            selectedClient = discoveredClients.get(position);
            showPairingDialog(selectedClient);
        });
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: " + errorCode);
                isDiscoveryStarted = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop Discovery failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started");
                isDiscoveryStarted = true;
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName() + " (" + serviceInfo.getServiceType() + ")");
                if (serviceInfo.getServiceType().contains("castlynk_client")) {
                    runOnUiThread(() -> {
                        boolean exists = false;
                        for (NsdServiceInfo n : discoveredClients) {
                            if (n.getServiceName().equals(serviceInfo.getServiceName())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            discoveredClients.add(serviceInfo);
                            clientNames.add(serviceInfo.getServiceName());
                            adapter.notifyDataSetChanged();
                            pbDiscovery.setVisibility(View.GONE);
                            tvStatus.setText("Found " + discoveredClients.size() + " potential clients");
                        }
                    });
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                runOnUiThread(() -> {
                    for (int i = 0; i < discoveredClients.size(); i++) {
                        if (discoveredClients.get(i).getServiceName().equals(serviceInfo.getServiceName())) {
                            discoveredClients.remove(i);
                            clientNames.remove(i);
                            adapter.notifyDataSetChanged();
                            break;
                        }
                    }
                });
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                isDiscoveryStarted = false;
            }
        };
    }

    private void showPairingDialog(NsdServiceInfo client) {
        new AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("Pair with " + client.getServiceName() + "?")
                .setPositiveButton("Pair & Start", (dialog, which) -> {
                    requestMirroringPermission();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestMirroringPermission() {
        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpManager != null) {
            Log.d(TAG, "Launching MediaProjection permission intent");
            projectionLauncher.launch(mpManager.createScreenCaptureIntent());
        } else {
            Log.e(TAG, "MediaProjectionManager is null");
        }
    }

    private void startMirroring(int resultCode, Intent data) {
        nsdManager.resolveService(selectedClient, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                String clientIp = serviceInfo.getHost().getHostAddress();
                int clientPort = serviceInfo.getPort();
                Log.d(TAG, "Resolved client: " + clientIp + ":" + clientPort);
                
                new Thread(() -> {
                    try (Socket socket = new Socket(clientIp, clientPort);
                         java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream())) {
                        
                        out.writeUTF(android.os.Build.MODEL);
                        out.flush();
                        Log.d(TAG, "Handshake sent to client");
                        
                        runOnUiThread(() -> {
                            Intent serviceIntent = new Intent(HostActivity.this, HostService.class);
                            serviceIntent.putExtra("resultCode", resultCode);
                            serviceIntent.putExtra("data", data);
                            startService(serviceIntent);
                            finish();
                        });
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to connect to client for handshake", e);
                        runOnUiThread(() -> Toast.makeText(HostActivity.this, "Handshake failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nsdManager != null && !isDiscoveryStarted) {
            nsdManager.discoverServices(CLIENT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        }
    }

    @Override
    protected void onPause() {
        if (nsdManager != null && isDiscoveryStarted) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
        }
        super.onPause();
    }
}

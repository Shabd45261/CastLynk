package com.example.castlynk.legacy;

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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class HostActivity extends AppCompatActivity {

    private static final String TAG = "HostActivity";
    private static final String CLIENT_SERVICE_TYPE = "_castlynk_client._tcp.";
    private static final int REQUEST_MEDIA_PROJECTION = 2001;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private List<NsdServiceInfo> discoveredClients = new ArrayList<>();
    private List<String> clientNames = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private TextView tvStatus;
    private ProgressBar pbDiscovery;
    private ListView lvClients;
    private NsdServiceInfo selectedClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host);

        tvStatus = findViewById(R.id.tv_host_status);
        pbDiscovery = findViewById(R.id.pb_host_discovery);
        lvClients = findViewById(R.id.lv_clients);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, clientNames);
        lvClients.setAdapter(adapter);

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
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Client discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType().equals(CLIENT_SERVICE_TYPE)) {
                    runOnUiThread(() -> {
                        discoveredClients.add(serviceInfo);
                        clientNames.add(serviceInfo.getServiceName());
                        adapter.notifyDataSetChanged();
                        pbDiscovery.setVisibility(View.GONE);
                        tvStatus.setText("Found " + discoveredClients.size() + " waiting clients");
                    });
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                runOnUiThread(() -> {
                    int index = clientNames.indexOf(serviceInfo.getServiceName());
                    if (index != -1) {
                        discoveredClients.remove(index);
                        clientNames.remove(index);
                        adapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {}
        };
    }

    private void showPairingDialog(NsdServiceInfo client) {
        new AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("Do you want to pair with " + client.getServiceName() + "?")
                .setPositiveButton("Pair & Start", (dialog, which) -> {
                    requestMirroringPermission();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestMirroringPermission() {
        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpManager != null) {
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            startMirroring(resultCode, data);
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
                
                new Thread(() -> {
                    try (Socket socket = new Socket(clientIp, clientPort);
                         java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream())) {
                        
                        // Send Host name to Client for pairing request
                        out.writeUTF(android.os.Build.MODEL);
                        out.flush();
                        
                        runOnUiThread(() -> {
                            Toast.makeText(HostActivity.this, "Pairing request sent to " + clientIp, Toast.LENGTH_SHORT).show();
                            
                            Intent serviceIntent = new Intent(HostActivity.this, HostService.class);
                            serviceIntent.putExtra("resultCode", resultCode);
                            serviceIntent.putExtra("data", data);
                            serviceIntent.putExtra("clientIp", clientIp);
                            startService(serviceIntent);
                            
                            finish();
                        });
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to connect to client for pairing", e);
                        runOnUiThread(() -> Toast.makeText(HostActivity.this, "Connection failed", Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        nsdManager.discoverServices(CLIENT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    @Override
    protected void onPause() {
        if (nsdManager != null) {
            nsdManager.stopServiceDiscovery(discoveryListener);
        }
        super.onPause();
    }
}

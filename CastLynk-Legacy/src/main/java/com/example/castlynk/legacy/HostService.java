package com.example.castlynk.legacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HostService extends Service {

    private static final String TAG = "HostService";
    private static final String CHANNEL_ID = "CastLynkHostChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SERVICE_TYPE = "_castlynk._tcp.";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Surface inputSurface;
    private ServerSocket videoServerSocket;
    private ServerSocket notificationServerSocket;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean isRunning = false;

    private List<DataOutputStream> notificationClients = new ArrayList<>();
    private static HostService instance;

    public static HostService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "HostService created");
        
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        // Normalize resolution (Multiple of 16 for encoder compatibility)
        screenWidth = (metrics.widthPixels / 16) * 16;
        screenHeight = (metrics.heightPixels / 16) * 16;
        screenDensity = metrics.densityDpi;
        
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "HostService onStartCommand");
        if (intent == null) return START_NOT_STICKY;
        
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        // 1. MUST start foreground IMMEDIATELY for Android 14+
        startForegroundService();

        if (resultCode != -1 && data != null) {
            Log.d(TAG, "ResultCode and Data received, setting up servers and projection");
            startServers();
            setupMediaProjection(resultCode, data);
            registerService();
        } else {
            Log.e(TAG, "Invalid intent data for MediaProjection");
        }

        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CastLynk Host Active")
                .setContentText("Ready to stream screen...")
                .setSmallIcon(R.drawable.ic_host)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.d(TAG, "Foreground service started with type mediaProjection");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
        }
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        try {
            MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpManager.getMediaProjection(resultCode, data);
            
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is NULL - System blocked permission or token expired");
                return;
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped by system");
                    stopSelf();
                }
            }, null);

            prepareEncoder();

            virtualDisplay = mediaProjection.createVirtualDisplay("CastLynkScreen",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);
            
            Log.d(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight);
        } catch (Exception e) {
            Log.e(TAG, "MediaProjection setup failed", e);
        }
    }

    private void prepareEncoder() throws IOException {
        SharedPreferences prefs = getSharedPreferences("CastLynkSettings", MODE_PRIVATE);
        boolean highFps = prefs.getBoolean("high_fps", false);
        boolean bitrateAuto = prefs.getBoolean("bitrate_auto", true);
        int bitrate = prefs.getInt("bitrate_value", 5);

        // AVC (H.264) with Baseline profile for maximum compatibility (Android 5)
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateAuto ? 2000000 : bitrate * 1000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, highFps ? 60 : 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        
        // For older decoders
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();
        Log.d(TAG, "Encoder started successfully");
    }

    private void startServers() {
        if (videoServerSocket != null) return;

        executor.execute(() -> {
            try {
                videoServerSocket = new ServerSocket(8888);
                Log.d(TAG, "Video Server listening on 8888");
                while (isRunning && !videoServerSocket.isClosed()) {
                    Socket clientSocket = videoServerSocket.accept();
                    handleVideoClient(clientSocket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Video Server error", e);
            }
        });

        executor.execute(() -> {
            try {
                notificationServerSocket = new ServerSocket(9998);
                Log.d(TAG, "Notification Server listening on 9998");
                while (isRunning && !notificationServerSocket.isClosed()) {
                    Socket clientSocket = notificationServerSocket.accept();
                    handleNotificationClient(clientSocket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Notification Server error", e);
            }
        });
    }

    private void handleNotificationClient(Socket socket) {
        executor.execute(() -> {
            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                synchronized (notificationClients) {
                    notificationClients.add(dos);
                }
                while (isRunning && !socket.isClosed()) {
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                Log.d(TAG, "Notification client disconnected");
            }
        });
    }

    private void handleVideoClient(Socket socket) {
        executor.execute(() -> {
            try (OutputStream out = socket.getOutputStream()) {
                Log.d(TAG, "Video Client connected: " + socket.getInetAddress());
                
                DataOutputStream dos = new DataOutputStream(out);
                dos.writeInt(screenWidth);
                dos.writeInt(screenHeight);
                dos.flush();

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                
                while (isRunning && !socket.isClosed()) {
                    if (encoder == null) {
                        Thread.sleep(100);
                        continue;
                    }

                    int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer != null) {
                            byte[] outData = new byte[bufferInfo.size];
                            outputBuffer.get(outData);
                            out.write(ByteBuffer.allocate(4).putInt(bufferInfo.size).array());
                            out.write(outData);
                            out.flush();
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Video Client session ended: " + e.getMessage());
            }
        });
    }

    public void broadcastNotification(String pkg, String title, String text) {
        synchronized (notificationClients) {
            List<DataOutputStream> toRemove = new ArrayList<>();
            for (DataOutputStream out : notificationClients) {
                try {
                    out.writeUTF(pkg);
                    out.writeUTF(title != null ? title : "No Title");
                    out.writeUTF(text != null ? text : "");
                    out.flush();
                } catch (IOException e) {
                    toRemove.add(out);
                }
            }
            notificationClients.removeAll(toRemove);
        }
    }

    private void registerService() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("CastLynk-" + Build.MODEL);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(8888);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                Log.d(TAG, "Service registered: " + NsdServiceInfo.getServiceName());
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Host Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        instance = null;
        Log.d(TAG, "HostService destroying");
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception ignored) {}
        }
        try {
            if (videoServerSocket != null) videoServerSocket.close();
            if (notificationServerSocket != null) notificationServerSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing server sockets", e);
        }
        executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

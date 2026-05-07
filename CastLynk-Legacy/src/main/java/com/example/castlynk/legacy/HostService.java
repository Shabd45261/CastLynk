package com.example.castlynk.legacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
    private boolean isStreaming = false;

    private List<DataOutputStream> notificationClients = new ArrayList<>();
    private static HostService instance;

    public static HostService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != -1 && data != null) {
            startForegroundService();
            setupMediaProjection(resultCode, data);
            startServers();
            registerService();
        }

        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CastLynk Host Active")
                .setContentText("Broadcasting screen to local network...")
                .setSmallIcon(R.drawable.ic_host)
                .setColor(Color.BLUE)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpManager.getMediaProjection(resultCode, data);
        
        try {
            prepareEncoder();
        } catch (IOException e) {
            Log.e(TAG, "Encoder preparation failed", e);
        }

        virtualDisplay = mediaProjection.createVirtualDisplay("CastLynkScreen",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null);
    }

    private void prepareEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000); // 2 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();
    }

    private void startServers() {
        // Video Server
        executor.execute(() -> {
            try {
                videoServerSocket = new ServerSocket(8888);
                Log.d(TAG, "Video Server started on port: 8888");
                while (!videoServerSocket.isClosed()) {
                    Socket clientSocket = videoServerSocket.accept();
                    handleVideoClient(clientSocket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Video Server error", e);
            }
        });

        // Notification Server
        executor.execute(() -> {
            try {
                notificationServerSocket = new ServerSocket(9998);
                Log.d(TAG, "Notification Server started on port: 9998");
                while (!notificationServerSocket.isClosed()) {
                    Socket clientSocket = notificationServerSocket.accept();
                    DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                    synchronized (notificationClients) {
                        notificationClients.add(dos);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Notification Server error", e);
            }
        });
    }

    private void handleVideoClient(Socket socket) {
        executor.execute(() -> {
            try (OutputStream out = socket.getOutputStream()) {
                Log.d(TAG, "Video Client connected: " + socket.getInetAddress());
                isStreaming = true;
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                
                while (isStreaming && !socket.isClosed()) {
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
                Log.e(TAG, "Video Client stream error", e);
            } finally {
                isStreaming = false;
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
                    CHANNEL_ID, "Host Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        isStreaming = false;
        instance = null;
        if (nsdManager != null && registrationListener != null) nsdManager.unregisterService(registrationListener);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (encoder != null) {
            encoder.stop();
            encoder.release();
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

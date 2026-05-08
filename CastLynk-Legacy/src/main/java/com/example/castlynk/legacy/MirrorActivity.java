package com.example.castlynk.legacy;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

public class MirrorActivity extends AppCompatActivity {

    private static final String TAG = "MirrorActivity";
    private String hostIp;
    private int port;
    private boolean isRunning = true;
    private MediaCodec decoder;
    private DataOutputStream commandOut;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mirror);

        SurfaceView surfaceView = findViewById(R.id.surface_view);
        hostIp = getIntent().getStringExtra("host_ip");
        port = getIntent().getIntExtra("port", -1);

        Log.d(TAG, "Starting MirrorActivity with Host: " + hostIp + ":" + port);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface created, starting decode thread");
                new Thread(() -> startDecoding(holder.getSurface())).start();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                isRunning = false;
            }
        });

        surfaceView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendTap(event.getX(), event.getY());
            }
            return true;
        });
    }

    private void startDecoding(Surface surface) {
        int retryCount = 0;
        int maxRetries = 10;
        Socket socket = null;
        DataInputStream in = null;

        while (isRunning && retryCount < maxRetries) {
            try {
                Log.d(TAG, "Connecting to Host (Attempt " + (retryCount + 1) + ")...");
                socket = new Socket(hostIp, port);
                socket.setSoTimeout(10000); // 10s read timeout
                in = new DataInputStream(socket.getInputStream());
                Log.d(TAG, "Connected to Host video stream");
                break;
            } catch (IOException e) {
                retryCount++;
                Log.w(TAG, "Connection failed: " + e.getMessage());
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {}
            }
        }

        if (socket == null || in == null) {
            Log.e(TAG, "Failed to connect after retries");
            runOnUiThread(() -> {
                Toast.makeText(this, "Host is not responding. Ensure 'Start Now' was clicked on the Host device.", Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }

        try {
            // Read dimensions
            int width = in.readInt();
            int height = in.readInt();
            Log.d(TAG, "Video Dimensions: " + width + "x" + height);

            if (width <= 0 || height <= 0) return;
            
            while (isRunning && !socket.isClosed()) {
                try {
                    int size = in.readInt();
                    if (size > 0) {
                        byte[] data = new byte[size];
                        in.readFully(data);
                        
                        if (decoder == null) {
                            initDecoder(surface, width, height);
                        }
                        
                        feedDecoder(data);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Stream interrupted", e);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Decoding/Connection Error", e);
            runOnUiThread(() -> Toast.makeText(this, "Connection lost: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } finally {
            try {
                if (in != null) in.close();
                if (socket != null) socket.close();
            } catch (IOException ignored) {}
            cleanup();
            if (isRunning) runOnUiThread(this::finish);
        }
    }

    private void initDecoder(Surface surface, int width, int height) throws IOException {
        Log.d(TAG, "Initializing Decoder: " + width + "x" + height);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
        
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(format, surface, null, 0);
        decoder.start();
    }

    private void feedDecoder(byte[] data) {
        if (decoder == null) return;
        
        int inputBufferIndex = decoder.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
                inputBuffer.put(data);
                decoder.queueInputBuffer(inputBufferIndex, 0, data.length, System.currentTimeMillis() * 1000, 0);
            }
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
        while (outputBufferIndex >= 0) {
            decoder.releaseOutputBuffer(outputBufferIndex, true);
            outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    private void sendTap(float x, float y) {
        new Thread(() -> {
            try {
                if (commandOut == null) {
                    Socket cmdSocket = new Socket(hostIp, 9999);
                    commandOut = new DataOutputStream(cmdSocket.getOutputStream());
                }
                commandOut.writeInt(1); // Type TAP
                commandOut.writeFloat(x);
                commandOut.writeFloat(y);
                commandOut.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to send tap", e);
                commandOut = null; // Reset for next attempt
            }
        }).start();
    }

    private void cleanup() {
        isRunning = false;
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception ignored) {}
            decoder = null;
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        cleanup();
        super.onDestroy();
    }
}

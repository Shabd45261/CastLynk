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

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
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
        try (Socket socket = new Socket(hostIp, port);
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            Log.d(TAG, "Connected to Host stream");
            
            while (isRunning && !socket.isClosed()) {
                int size = in.readInt();
                if (size > 0) {
                    byte[] data = new byte[size];
                    in.readFully(data);
                    
                    if (decoder == null) {
                        initDecoder(surface);
                    }
                    
                    feedDecoder(data);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Decoding error", e);
        } finally {
            cleanup();
        }
    }

    private void initDecoder(Surface surface) throws IOException {
        // Defaulting to 1080x1920, ideally this comes from the host
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920);
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(format, surface, null, 0);
        decoder.start();
    }

    private void feedDecoder(byte[] data) {
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
            }
        }).start();
    }

    private void cleanup() {
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception ignored) {}
        }
        if (commandOut != null) {
            try {
                commandOut.close();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        cleanup();
        super.onDestroy();
    }
}

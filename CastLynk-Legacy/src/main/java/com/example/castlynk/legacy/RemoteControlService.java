package com.example.castlynk.legacy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class RemoteControlService extends AccessibilityService {

    private static final String TAG = "RemoteControlService";
    private static final int PORT = 9999;
    private boolean isRunning = true;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Remote Control Service Connected");
        startCommandServer();
    }

    private void startCommandServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                Log.d(TAG, "Command Server started on port " + PORT);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    handleCommandClient(socket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Command server error", e);
            }
        }).start();
    }

    private void handleCommandClient(Socket socket) {
        new Thread(() -> {
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                while (isRunning && !socket.isClosed()) {
                    int type = in.readInt();
                    if (type == 1) { // TAP
                        float x = in.readFloat();
                        float y = in.readFloat();
                        performTap(x, y);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Command client error", e);
            }
        }).start();
    }

    private void performTap(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            Path path = new Path();
            path.moveTo(x, y);
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
            dispatchGesture(builder.build(), null, null);
            Log.d(TAG, "Tapped at: " + x + ", " + y);
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        isRunning = false;
        return super.onUnbind(intent);
    }
}

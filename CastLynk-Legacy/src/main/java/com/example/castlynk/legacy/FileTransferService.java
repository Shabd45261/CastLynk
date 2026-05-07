package com.example.castlynk.legacy;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";
    private static final int FILE_PORT = 9997;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private boolean isRunning = true;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        executor.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(FILE_PORT)) {
                Log.d(TAG, "File Server started on port " + FILE_PORT);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    handleFileReceive(socket);
                }
            } catch (IOException e) {
                Log.e(TAG, "File server error", e);
            }
        });
    }

    private void handleFileReceive(Socket socket) {
        executor.execute(() -> {
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                String fileName = in.readUTF();
                long fileSize = in.readLong();
                
                File downloadDir = new File(getExternalFilesDir(null), "Downloads");
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File file = new File(downloadDir, fileName);
                
                try (FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    while (totalRead < fileSize && (bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }
                }
                Log.d(TAG, "File received: " + fileName);
            } catch (IOException e) {
                Log.e(TAG, "File receive error", e);
            }
        });
    }

    public static void sendFile(String hostIp, File file) {
        new Thread(() -> {
            try (Socket socket = new Socket(hostIp, FILE_PORT);
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                
                out.writeUTF(file.getName());
                out.writeLong(file.length());
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
                Log.d(TAG, "File sent: " + file.getName());
            } catch (IOException e) {
                Log.e(TAG, "File send error", e);
            }
        }).start();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        executor.shutdown();
        super.onDestroy();
    }
}

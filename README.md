# CastLynk

CastLynk is an Android ecosystem designed for seamless screen mirroring and remote control. It consists of a modern app and a legacy version to ensure compatibility across a wide range of devices.

## Vision
To provide a lightweight, low-latency solution for Android-to-Android interaction, enabling users to view and control devices remotely with ease. This is ideal for remote assistance, multi-device management, and accessibility support.

## Features
- **Real-time Screen Mirroring**: Low-latency video streaming between devices.
- **Remote Control**: Perform touch gestures and navigate the host device from the client.
- **Notification Synchronization**: See notifications from your host device on your client device in real-time.
- **File Transfer**: Easily share files between connected devices.
- **Legacy Compatibility**: Optimized for older Android versions (starting from Android 5.0).

## Instructions
### Host Mode
1. Launch CastLynk and select **Host**.
2. Grant **Media Projection** permission when prompted to allow screen capture.
3. Enable the **CastLynk Remote Control** Accessibility Service in your device settings for remote gesture support.
4. Ensure the device is connected to the local network.

### Client Mode
1. Launch CastLynk and select **Client**.
2. Use **Find & Connect** to discover available Host devices on your local network.
3. Once connected, the host screen will appear, and you can interact with it using touch gestures.

## Update Convention
APKs are named following the pattern: `(name of the apk-N).apk` where `N` is the update count.
Current Version: 1.1 (Build 2)
Latest APK: `CastLynk-Legacy-debug-2.apk`

# WiFi Direct Share

Android-to-Android file sharing over Wi-Fi Direct (P2P), no router required.

## Build
- Locally: `gradle assembleDebug`
- CI: push to `main`; the build workflow uploads `app-debug.apk` as an artifact.

## Use
1. Install on two devices.
2. Tap **Discover Peers** on both.
3. Tap a peer name to connect (one becomes group owner).
4. On the **group owner**, tap **Start Receive Server**.
5. On either device, tap **Pick File to Send**, then **Send Picked File**.
   Files are saved to `Android/data/com.example.wifidirectshare/files/Download/`.

## Permissions
Location (pre-13) / Nearby Wi-Fi Devices + Notifications (13+). Required by Android for Wi-Fi Direct.

## Notes
- Verified to compile via CI; runtime P2P behavior must be tested on two physical devices.
- Transfer uses a plain TCP socket on port 8988 over the P2P interface.

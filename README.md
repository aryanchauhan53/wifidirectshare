# WiFi Direct Share (v2)

Symmetric Android-to-Android file sharing over Wi-Fi Direct. Both devices can send and receive simultaneously. Files land in the shared **Downloads** folder.

## Build
- CI: push to `main`; the workflow uploads `app-debug.apk`.
- Local: `gradle assembleDebug`.

## Use
1. Install on two phones.
2. Tap **Discover** on both. Wait for peers.
3. Tap a peer on **one** phone only. The system forms a P2P group.
4. Both phones now show "Connected as …". Both show a **Send Files** button.
5. On either (or both) phones: tap **Send Files**, pick one or more files.
6. Transfers appear in the list with a live progress bar.
   Destination: `Downloads/<filename>` on the receiving phone.

## Notes
- 3 parallel outgoing transfers per device; incoming unlimited.
- Files received on API 29+ go through `MediaStore.Downloads` → visible in any file manager.
- Handshake: client sends `0x02 REGISTER` to `192.168.49.1:8988` on connect, so the GO learns the client IP. Both directions then work symmetrically.
- Runtime P2P behaviour must be validated on two physical devices. CI only verifies compilation.

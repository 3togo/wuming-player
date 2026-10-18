# Casting quick start

1. Connect the phone and DLNA television to the same LAN.
2. Start a video in the mobile app.
3. Tap the Cast button and select the television.
4. After casting starts, the phone shows the active television and episode in
   the video screen and in a persistent notification.
5. Tap the status chip or the notification's **Disconnect** action to stop the
   television and cancel automatic episode advancement.

Only DLNA/UPnP MediaRenderer casting is currently supported. The TV/Leanback
build does not initiate casts.

If no device appears, confirm that Wi-Fi client isolation is disabled and that
multicast/SSDP traffic is permitted. Refresh discovery after the television's
renderer mode is enabled.

For implementation details and guardrails, read
[docs/projection-protocols.md](docs/projection-protocols.md) and
[docs/cast-auto-next.md](docs/cast-auto-next.md).

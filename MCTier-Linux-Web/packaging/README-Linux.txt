MCTier Linux Web - Debian/Ubuntu x86_64
========================================

Install the runtime packages:

  sudo apt update
  sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1

Install the OpenSSL runtime package provided by your release:

  Debian 12 / Ubuntu releases that provide it: libssl3
  Debian 13: libssl3t64

You also need a graphical desktop session, an installed default browser
(Firefox, Chrome, or Chromium), a Secret Service keyring available on the
user D-Bus session, and /dev/net/tun. GNOME Keyring is a common Secret Service
provider. These are runtime dependencies; WebKitGTK and Tauri are not used.

Extract the complete archive and run the executable named:

  ./mctier-linux-web

The launcher checks the bundled EasyTier SHA-256, requests pkexec authorization
only when the EasyTier core lacks cap_net_admin,cap_net_raw=ep, verifies the
result, starts the service as the normal user, waits for readiness, and opens
http://127.0.0.1:14700 with xdg-open. Close the launching terminal or press
Ctrl+C to stop the service. Do not start the service or EasyTier as root.

The service binds only to 127.0.0.1. Joining a room, connecting to the selected
MCTier signaling server and EasyTier node, and granting microphone/screen
permissions still require explicit actions in the browser.

This build is experimental. File sending, shared folders and remote input are
not included. Cross-device networking, voice, screen viewing and reconnection
have not completed release acceptance. Use the project README and issue
tracker to report problems.

Firefox has previously shown repeated MCTier signaling WebSocket close code
1006 disconnects. One diagnostic showed about 1.4 seconds connected, no
received traffic, eight queued messages, and no pending heartbeat. This was
also reported with a clean Firefox profile and AdBlock disabled. The cause is
unknown and this build does not claim the Firefox issue is fixed. Chrome
working in a user test does not by itself validate Firefox or WebRTC.

Bundled EasyTier core SHA-256:
  f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322

# VaxoScope

A packet inspector for Android that works without root. It shows which app talks to which server, how much data it uses, and records traffic to a `.pcap` file you can open in Wireshark.

Everything stays on your phone. VaxoScope has no accounts, no analytics and no ads, and it does not send anything anywhere on its own.

## Features

- Live connection list grouped by app, with host names taken from DNS and TLS SNI
- Per-connection details: addresses, ports, protocol, packet and byte counts, timing, owner of the IP (country, company, ASN)
- Packet inspector with IP, TCP, UDP, DNS, TLS and plain HTTP summaries, plus a hex viewer
- TCP conversation view with relative sequence and acknowledgement numbers
- Filters (TCP, UDP, DNS, TLS) and a search box, for example:
  - `tcp port 443`
  - `app chrome`
  - `host tiktok`
  - `country us`, `org google`, `asn as13335`
  - `>1mb`, `tracker`, `blocked`, `quic`, `ipv6`
- PCAP recording, export (everything, one app or one connection) and import (`.pcap` and `.pcapng`)
- DNS history and a 7-day usage report
- Firewall: block an app or a host (works while the VPN is on)
- Ad and tracker flagging with optional notifications
- Quick settings tile, home screen widget, backup and restore
- Interface in Turkish, English and Russian

## How it works

VaxoScope uses Android's `VpnService`. The VPN is local: packets are not sent to any server. The phone's traffic is routed into the app, which reads and records it and then passes it on to the internet.

    Android TUN <-> packet pump (counts, decodes) <-> socketpair <-> hev-socks5-tunnel <-> local SOCKS5 relay <-> internet

Because it is a VPN, Android allows only one VPN at a time and the app can only see traffic while it is switched on.

## What it does not do

- It does not decrypt HTTPS. No fake certificates are installed. You see host names, addresses, ports and sizes, not page contents.
- It only sees the traffic of the phone it runs on.
- QUIC (UDP 443) is encrypted. Names for such connections come from earlier DNS answers and may be missing or approximate.
- If Private DNS is enabled in Android settings, DNS queries are encrypted and cannot be read.
- The country of an IP is the registration country of its network, not necessarily where the server is.
- IPv6 traffic is currently not routed through the tunnel.
- Blocking works only while the VPN is on.

## Install

1. Download the latest `VaxoScope-*.apk` from the Releases page.
2. Install it and allow the VPN request on first start.
3. Android 10 or newer is needed to show app names for connections.

## Build

The CI workflow in `.github/workflows` shows the exact steps. A local build needs the Android SDK and NDK because the tunnel engine is native code.

## Privacy

VaxoScope does not collect or transmit any data of its own. Packet recordings, usage reports and settings are stored in the app's private storage on your phone. You decide when to export them.

## Credits and licenses

- VaxoScope is released under the MIT License (see `LICENSE`).
- Tunnel engine: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) by hev (MIT), including its bundled components. Full texts are in `NOTICE`.
- IP ownership data: [iptoasn.com](https://iptoasn.com), public domain (PDDL).

Wireshark is a registered trademark of the Wireshark Foundation. VaxoScope is an independent project and is not affiliated with it.

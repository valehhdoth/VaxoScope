# Changelog

## 1.1.0

- New Network tab: Wi-Fi network scanner (ICMP, TCP, mDNS, ARP where Android allows it)
- Device list with online and offline devices, filters, details, hostnames, type guesses
- Offline devices are detected by comparing with earlier scans and re-checking before marking them offline
- Vendor names from the IEEE OUI registry when a MAC address is readable
- Loading screen on start

## 1.0.1

- Blocking is stricter: incoming packets of blocked apps are dropped too
- App detection for new connections is retried instead of tried once
- Blocking an app that is not in the monitored list adds it automatically
- Optional IPv6 capture (experimental, off by default)

## 1.0.0

First public release.

- Live connection list grouped by app, with host names from DNS and TLS SNI
- Packet inspector, hex viewer and TCP conversation view
- Filters (TCP, UDP, DNS, TLS) and a search language (`tcp port 443`, `app chrome`, `country us`, `org google`, `>1mb`, `tracker`, `blocked`)
- PCAP recording, export (all, per app, per connection) and import (.pcap and .pcapng)
- DNS history and 7-day usage report
- Built-in firewall: block an app or a host while the VPN is on
- Ad and tracker flagging with optional alerts
- IP owner lookup (country, company, ASN) from an offline database
- Quick settings tile and home screen widget
- Backup and restore of settings, blocks and reports
- Turkish, English and Russian interface

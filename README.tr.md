# VaxoScope

Root gerektirmeyen bir Android paket inceleme uygulaması. Hangi uygulamanın hangi sunucuyla konuştuğunu ve ne kadar veri kullandığını gösterir, trafiği Wireshark'ta açılabilen bir `.pcap` dosyasına kaydeder.

Her şey telefonunda kalır. VaxoScope'ta hesap, analitik ve reklam yoktur, kendiliğinden hiçbir yere veri göndermez.

## Özellikler

- Uygulamaya göre gruplanmış canlı bağlantı listesi, DNS ve TLS SNI'dan alınan site adlarıyla
- Bağlantı ayrıntısı: adresler, portlar, protokol, paket ve bayt sayıları, zaman, IP'nin sahibi (ülke, şirket, ASN)
- IP, TCP, UDP, DNS, TLS ve düz HTTP özetli paket inceleyici ve hex görüntüleyici
- Göreli seq/ack numaralı TCP konuşma görünümü
- Filtreler (TCP, UDP, DNS, TLS) ve arama kutusu, örneğin:
  - `tcp port 443`
  - `app chrome`
  - `host tiktok`
  - `country us`, `org google`, `asn as13335`
  - `>1mb`, `izleyici`, `engelli`, `quic`, `ipv6`
- PCAP kaydı, dışa aktarma (hepsi, tek uygulama ya da tek bağlantı) ve içe aktarma (`.pcap` ve `.pcapng`)
- DNS geçmişi ve 7 günlük kullanım raporu
- Güvenlik duvarı: uygulama ya da site engelleme (VPN açıkken çalışır)
- Reklam/izleyici işaretleme ve isteğe bağlı bildirim
- Hızlı ayar kutucuğu, ana ekran widget'ı, yedekleme ve geri yükleme
- Türkçe, İngilizce ve Rusça arayüz

## Nasıl çalışır

VaxoScope Android'in `VpnService` mekanizmasını kullanır. VPN yereldir: paketler hiçbir sunucuya gönderilmez. Telefonun trafiği uygulamaya yönlendirilir, uygulama paketleri okuyup kaydeder ve internete yoluna devam ettirir.

    Android TUN <-> paket pompası (sayar, çözer) <-> socketpair <-> hev-socks5-tunnel <-> yerel SOCKS5 aktarıcı <-> internet

Bir VPN olduğu için Android aynı anda tek VPN'e izin verir ve uygulama yalnızca açıkken trafiği görür.

## Yapmadıkları

- HTTPS'i çözmez, sahte sertifika kurmaz. Site adı, adres, port ve boyutları görürsün, sayfa içeriğini görmezsin.
- Yalnızca üzerinde çalıştığı telefonun trafiğini görür.
- QUIC (UDP 443) şifrelidir. Bu bağlantıların adları eski DNS yanıtlarından gelir, eksik ya da yaklaşık olabilir.
- Android'de Private DNS açıksa DNS sorguları şifreli gider ve okunamaz.
- IP'nin ülkesi ağın kayıtlı olduğu ülkedir, sunucunun fiziksel konumu olmayabilir.
- IPv6 trafiği şu an tünelden geçirilmez.
- Engelleme yalnızca VPN açıkken çalışır.

## Kurulum

1. Releases sayfasından son `VaxoScope-*.apk` dosyasını indir.
2. Kur ve ilk açılışta VPN iznini ver.
3. Bağlantılarda uygulama adlarını görmek için Android 10 ya da üstü gerekir.

## Derleme

Tam adımlar `.github/workflows` içindeki iş akışında. Tünel motoru yerel (native) kod olduğu için yerel derleme Android SDK ve NDK ister.

## Gizlilik

VaxoScope kendi başına hiçbir veri toplamaz ya da göndermez. Paket kayıtları, kullanım raporları ve ayarlar telefondaki uygulamaya özel alanda tutulur. Dışa aktarmayı sen yaparsın.

## Katkılar ve lisanslar

- VaxoScope MIT lisanslıdır (bkz. `LICENSE`).
- Tünel motoru: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), hev (MIT), birlikte gelen bileşenleriyle. Lisans metinleri `NOTICE` dosyasında.
- IP sahipliği verisi: [iptoasn.com](https://iptoasn.com), kamu malı (PDDL).

Wireshark, Wireshark Foundation'ın tescilli markasıdır. VaxoScope bağımsız bir projedir ve onunla bağlantısı yoktur.

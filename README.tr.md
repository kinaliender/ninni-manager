# Ninni

[English](README.md) · **Türkçe**

Bebek odasındaki ninni telefonunu başka bir odadan Bluetooth Low Energy ile kontrol edin.

Uyuyan çocuğun yanında eski bir Android telefon durur ve Spotify'dan ninni çalar. Telefon
çevrimdışıdır, Wi-Fi kapalıdır; böylece hiçbir bildirim kimseyi uyandırmaz. Bu telefonda
**Ninni Çalar** çalışır. Ebeveynlerin telefonlarındaki **Ninni Kumanda** onu yan odadan
kontrol eder. Wi-Fi, internet, hesap ya da sunucu gerekmez.

- Çalan şarkıyı kapağıyla, ilerlemesini ve çalar telefonun pilini görün
- Çalma listesini görün, bir parçaya dokunup onu çalın
- Sesi değiştirin (kumandanın kendi ses tuşları da çalışır), durdurun/başlatın, önceki/sonraki şarkıya geçin
- Şarkının istediğiniz yerine atlayın, 15 sn geri ya da ileri gidin, karıştır ve tekrarla'yı açıp kapatın
- **Ses sınırı:** müzik sınırın üstüne çıkmaz, telefonun kendi ses tuşlarıyla bile
- **Uyku zamanlayıcısı** (10–60 dk): istenirse son 10 dakikada sesi yavaşça kısar, sonra müziği
  durdurur ve ses seviyesini bir dahaki sefer için eski haline getirir
- Çalarda görünen 4 haneli kodla eşleşme; aynı anda birden fazla kumanda bağlanabilir

## Gereksinimler

- **Çalar:** BLE çevre birimi (peripheral) olabilen Android 10+ bir telefon ve Spotify ya da
  başka bir müzik uygulaması. Uygulama bunu kendisi kontrol eder: *BLE yayın desteği* ✓.
- **Kumanda:** Bluetooth'u olan herhangi bir Android 10+ telefon.
- Menzil Bluetooth menzilidir: duvarlara göre bir iki oda öte.

## Derleme ve kurulum

Hazır APK yoktur; bu repodan derleyin. JDK 17 (Gradle 8.10.2 daha yeni JDK'larda çalışmaz) ve
Android SDK gerekir.

```sh
cd android
export JAVA_HOME=/jdk-17/yolu
./gradlew :protocol:testDebugUnitTest :player:assembleDebug :manager:assembleDebug
adb install -r player/build/outputs/apk/debug/player-debug.apk    # bebeğin yanındaki telefon
adb install -r manager/build/outputs/apk/debug/manager-debug.apk  # ebeveynlerin telefonları
```

## Çalar telefonda ilk kurulum

1. **Ninni Çalar**'ı açın ve **Başlat**'a dokunun; Bluetooth iznini verin (Android 12+).
2. **Bildirim erişimi → Aç** ile Ninni Çalar'ı etkinleştirin. Şarkı adı, ilerleme, ileri-geri
   sarma, karıştır ve tekrarla için gerekir; ses ve durdur/başlat onsuz da çalışır. Android
   13+'da dışarıdan kurulan uygulama için önce *Uygulama bilgisi › ⋮ › Kısıtlı ayarlara izin
   ver* gerekebilir.
3. **Pil optimizasyonu → Aç** ile izin verin; Android hizmeti hiç durdurmasın.
4. **BLE yayın desteği**'nin ✓ olduğunu kontrol edin. ✗ ise bu telefon BLE çevre birimi
   olamaz, çalar olarak kullanılamaz.
5. Ebeveyn telefonunda **Ninni Kumanda**'yı açın ve çalarda görünen 4 haneli kodu bir kez girin.

Wi-Fi kapalı kalabilir. Uçak modu Bluetooth'u kapatır; kullanırsanız Bluetooth'u yeniden açın.
Spotify, indirilen şarkıları silmemek için en az 30 günde bir internete bağlanmayı ister; arada
bir Wi-Fi'yi açın.

Android 10–11'de kumandanın tarama yapabilmesi için konum izni **ve konumun açık olması**
gerekir (BLE taraması için bir Android kuralı); Android 12+'da gerekmez.

## Nasıl çalışır

| Parça | Dosya | Ne yapar |
|---|---|---|
| Protokol | `android/protocol/.../Protocol.kt` | UUID'ler, durum JSON'u, komutlar; iki uygulama da kullanır (+ birim testleri). Ayrıntısı [PROTOCOL.md](PROTOCOL.md)'de (İngilizce) |
| Çalar ekranı | `android/player/.../MainActivity.kt` | Eşleşme kodu, başlat/durdur, çalan şarkı, kurulum listesi. Bilerek karanlık |
| Çalar hizmeti | `android/player/.../PlayerService.kt` | Ön plan hizmeti: durumu toplar, komutları uygular, ekran kapanınca da çalışır, yeniden başlatma ya da güncellemeden sonra kendini açar (`BootReceiver.kt`) |
| Çalar Bluetooth | `android/player/.../BleServer.kt` | Yayın + GATT sunucusu, bağlantı başına kod kontrolü, bildirim kuyruğu, uzun okumalar |
| Çalar medya | `android/player/.../MediaWatcher.kt`, `MediaListenerService.kt` | Müzik uygulamasının medya oturumunu izler: şarkı adı, konum, sarma, karıştır/tekrarla, çalma sırası; olmazsa medya tuşlarını kullanır |
| Kapak | `android/player/.../Artwork.kt` | Şarkının kapağını kumandaların parça parça çektiği küçük bir JPEG'e çevirir |
| Uyku zamanlayıcısı | `android/player/.../SleepTimer.kt` | Sesi adım adım kısar, durdurur, müzik durduktan sonra sesi eski haline getirir |
| Kumanda ekranı | `android/manager/.../MainActivity.kt`, `QueueAdapter.kt` | Büyük düğmeler, kapak, ilerleme, çalma listesi, ses ve sınır, uyku zamanlayıcısı, pil; uygulama açıkken telefonun ses tuşları çaları kontrol eder |
| Kumanda Bluetooth | `android/manager/.../PlayerConnection.kt` | Servis UUID'siyle tarama, GATT işlem kuyruğu, eşleşme kodu, bekleyerek yeniden bağlanma, kapak ve çalma listesini çekme |

Üçüncü taraf kütüphane yok: sadece `androidx.core`, çalarda karıştır/tekrarla için ayrıca
`androidx.media`.

## Çaları kumanda olmadan denemek

**nRF Connect** ile: bağlanın, STATE'te bildirimleri açın ve COMMAND'a metin yazın:
`auth:1234`, `vol-`, `toggle` …

Ya da adb ile, Bluetooth ve kod olmadan (`dumpsys` sadece adb'ye ve sisteme açıktır):

```sh
adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService                # durum
adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService cmd sleep:120:60
```

---

Spotify ile bir bağlantısı yoktur. Uygulamalar sadece Android'in standart medya kontrollerini kullanır.

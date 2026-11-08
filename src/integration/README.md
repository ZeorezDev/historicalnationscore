# Prototipin sunucu testleri

JDK 17 ile depo kökünde `.\gradlew.bat build integrationJar` çalıştırın (Linux/macOS üzerinde `./gradlew`). Dağıtım modu `build/libs/` altında, test modu `build/integration/` altında oluşur.

## Kayıt, komut ve yetki testleri

1. Boş bir klasöre resmî Forge 1.20.1-47.4.28 dedicated server kurun. `mods/` içine Historical Nations Core dağıtım JAR'ını, test JAR'ını ve OPAC 0.33.0 normal Forge JAR'ını koyun. Minecraft EULA'sını inceleyip kabul ediyorsanız bu test kurulumu için onaylayın.
2. Yeni bir dünya adı kullanın. `server.properties` içinde `server-ip=127.0.0.1` ayarlayın. Aşağıdaki iki komutu test sunucusunun klasöründe sırayla çalıştırın. Linux/macOS üzerinde `win_args.txt` yerine `unix_args.txt` kullanın.

```powershell
java -Xmx2G "-Dhn.test.phase=setup" "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt" nogui
java -Xmx2G "-Dhn.test.phase=verify" "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt" nogui
```

Her süreç kendi kendine kapanır. İlkinde `HN INTEGRATION PASS setup`, ikincisinde `HN INTEGRATION PASS verify` beklenir. Her iki işareti de kontrol edin. Normal kapanış tek başına başarı ölçütü değildir.

`setup` sırasında dört Forge test oyuncusu yetki seviyesi sıfırla gerçek komut ağacını kullanır. Test belediye başkanlığını devreder, şehir ve devlet davetleri bırakır, yalnız test dünyasında OPAC üzerinden bir claim oluşturur. Aynı negatif X ve pozitif Z koordinatlarını Overworld ve Nether'da sorgular. İkinci süreç aynı dünya kaydını açar, liderliği ve claim sahibini okur, bekleyen davetleri kabul eder. Şehrin devletten ayrılması, devletin dağıtılması, claim sahibinin şehirden ayrılması ve şehrin silinmesi de doğrulanır.

## İki ayrı Forge istemcisi

Ayrı bir boş test dünyasında `server-ip=127.0.0.1`, `server-port=25486`, `online-mode=false` ve `enforce-secure-profile=false` ayarlayın. Bu ayarlar yerel geliştirme profilleri içindir. Sunucuyu yukarıdaki Java komutuyla, `-Dhn.test.phase=network` seçerek başlatın.

Depo kökünde iki ayrı terminal açın. İlk komut istemciyi başlattıktan sonra ikinci komutu diğer terminalde çalıştırın.

```powershell
.\gradlew.bat runClient -PhnTestClient=HnNetA --project-cache-dir run-HnNetA/gradle-cache
.\gradlew.bat runClient -PhnTestClient=HnNetB --project-cache-dir run-HnNetB/gradle-cache
```

Her istemci kendi `run-HnNetA` veya `run-HnNetB` klasörünü kullanır. İlk açılışta erişilebilirlik ayarları gösterilirse ana menüye devam edin. Test istemcileri buradan yerel sunucuya bağlanır. İlk istemci Bursa'yı kurup ikinci oyuncuyu davet eder. İkinci istemci sohbet davetini aldıktan sonra kabul komutunu gönderir. Sunucu her iki bağlı oyuncunun vatandaşlığını ve belediye başkanını doğruladığında `HN INTEGRATION PASS network` yazar.

## Zorunlu bağımlılık

Ayrı bir test kurulumunda yalnız Historical Nations Core ile sunucuyu başlatın. Forge yüklemeyi durdurmalı ve gereken `openpartiesandclaims 0.33.0` sürümünü bildirmelidir.

## v0.1.0 doğrulama kaydı

| Deneme | Gözlenen sonuç |
| --- | --- |
| Java 17 temiz derleme | Dağıtım ve ayrı test JAR'ı üretildi. |
| Dedicated server, iki ardışık süreç | `PASS setup` ve `PASS verify`. Şehir, vatandaşlık, liderlik, davet ve OPAC sahibi yeniden açılışta okundu. |
| İki ayrı Forge istemcisi | `PASS network`. HnNetA Bursa'yı kurdu, HnNetB Türkçe sohbet davetini alıp kabul etti. |
| OPAC eksik veya sürüm 0.32.7 | Forge her iki durumda da yüklemeyi durdurdu ve gerekli 0.33.0 sürümünü bildirdi. |

İlk otomatik istemci denemesinde hızlı bağlantı zaman aşımına uğradı. Test, oyun ana menüye ulaştıktan sonra normal bağlantıyı başlatacak şekilde güncellendi. OPAC, isteğe bağlı parti ve izin sağlayıcıları bulunmadığında günlükte varsayılan sağlayıcıları seçtiğini bildirdi.

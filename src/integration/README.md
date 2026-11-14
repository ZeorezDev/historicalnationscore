# Prototipin sunucu testleri

## v0.6.0 doğrulaması

1. v0.5.0 bölümündeki `currencyVerify` tamamlanmış dünyayı sunucu kapalıyken yeni bir dizine kopyalayın. v0.6.0 dağıtım ve test JAR’larını OPAC 0.33.0 ile kullanın.
2. Kopyada `tradeNetwork` çalıştırın. Aşağıdaki iki Forge istemcisini başlatın. Test eski DEM bakiyesini okur, OPAC claim ve daire inşa izinlerini kontrol eder. İki ulusun oyuncuları NBT etiketli 64 demir külçesi için 500 RHM bedel ve 50 RHM gümrük ile işlem yapar. Alıcı aynı teklifin iki kabulünü ve aynı eşya için rakip bir teklifi ağ üzerinden gönderir. Her iki envanter, bakiyeler ve devlet hesapları kontrol edilir.
3. Aynı dünyayı ayrı bir süreçte `tradeRestart` ile açın. İthalat ve ihracatta birer işlem, 500 RHM hacim, 50 RHM hazine bakiyesi, yakın geçmiş ve ambargo ilişkisi doğrulanır. Devlet dağıtılınca karşı tarafın ambargo listesi temizlenir.

Forge 47.4.28 ve Java 17 ile `HN INTEGRATION PASS tradeNetwork` ve `HN INTEGRATION PASS tradeRestart` alındı. Değişen oran, mesafe, farklı NBT, dolu envanter, yetersiz toplam ödeme, sonradan konulan ambargo, taraf yetkileri ve yurt içi sıfır vergi de kontrol edildi. İlk denemede test modülü kayıt görüntüsünü yanlış eşlenmiş bir Java metodu üzerinden okudu. Test düzeltildikten sonra iki aşama temiz dünya kopyasında geçti. Yeniden başlatma, sunucunun normal kayıt ve kapanış yolunu kullanır.

## v0.5.0 doğrulaması

Üretim JAR’ı Java 17 ile derlendi ve Forge 47.4.28 ile OPAC 0.33.0 kullanılan dedicated server üzerinde sınandı. Aşağıdaki senaryolar sunucunun gerçek komut ağacını Forge test oyuncuları ile kullanır.

1. v0.4.0 dağıtım JAR’ı ve v0.5.0 test JAR’ı ile boş bir dünyada `currencyLegacy` çalıştırın. Dünya iki devlet, eski bakiyeler, vergi ve 31 Liralık açık bir ilan içerir. Sunucuyu kapatın ve dünyayı kopyalayın.
2. Dağıtım JAR’ını v0.5.0 ile değiştirin. Kopya dünyada `currencySetup` çalıştırın. Eski ilanı TRY ile satın alma, iki ulusal para, kod çakışması, yetkisiz basım, günlük limit, birden çok bakiye, banka, rezerv yetersizliği, ücretli quarter ve vergi sınandı. Yapay gün geçişinde fiyat endeksi ve kurlar hesaplandı. Aynı ve önceki günün tekrar işlenmediği kontrol edildi.
3. Aynı kopyayı yeni bir sunucu sürecinde `currencyVerify` ile açın. Kayıtlı ilan, bakiyeler, günlük basım limiti ve devlet dağıtıldıktan sonra eski parayla özel satış doğrulandı. Üç aşamada da `HN INTEGRATION PASS` alındı.
4. Historical Documents 0.3.0, Core 0.2.0 sürümüne sabitlenmiştir. Ayrı bir test kopyasında yalnızca bu bağımlılık bildirimi 0.5.0 yapıldı. Documents’ın kendi testleri `DOCUMENTS PASS setup` ve `DOCUMENTS PASS restart` verdi.

## v0.4.0 doğrulaması

- v0.3.0 dağıtım ve test JAR’larıyla yeni bir dünyada önce `territorySetup`, sonra `municipalSetup` çalıştırın. Sunucu kapalıyken iki JAR’ı v0.4.0 ile değiştirin. Aynı dünyada ayrı süreçlerde `economySetup` ve `economyVerify` çalıştırın. Her iki aşama Forge 47.4.28 üzerinde geçti.
- Belediye ve özel mülk satışları, yetersiz bakiye ve alıcı taşması, yetkisiz çekim, eski mülkiyetin yüklenmesi, bakiyelerin yeniden okunması, kurum tasfiyesi ve liderlik devri kontrol edildi. Günlük tahsilatın aynı güne tekrar çağrılması ve geçmiş güne dönülmesi test saatinde sınandı.
- Ayrı bir dünyada `network` ve aşağıda tarif edilen iki Forge istemcisiyle `PASS network` alındı. İki istemci aynı ilan için ayrı bağlantılardan `plot claim` gönderdi. Tek oyuncunun satın alma bedeli kesildi. Belediye geliri ve her iki bakiye doğrulandı. Aynı testte iki dairenin OPAC inşa kontrolleri de geçti.
- Historical Documents 0.3.0’ın özgün JAR’ı, Core için tam `[0.2.0]` bağımlılığı nedeniyle Forge yüklemesinde reddedildi. Ayrı bir test kopyasında yalnızca bu sürüm bildirimi `[0.4.0]` yapıldı. Documents’ın kendi testleriyle `DOCUMENTS PASS setup` ve ikinci süreçte `DOCUMENTS PASS restart` alındı. Bu kopya dağıtım paketine dahil edilmedi.

## v0.3.0 doğrulaması

Forge 47.4.28, Java 17 ve OPAC 0.33.0 kullanılan yerel dedicated server üzerinde şu aşamaları çalıştırın. Her aşama, aşağıda tarif edilen sunucu komutunda `-Dhn.test.phase=<aşama>` ile seçilir. Günlükte ilgili `HN INTEGRATION PASS` satırını arayın.

| Aşama | Dünya ve doğrulama |
| --- | --- |
| `territorySetup` → `municipalSetup` → `municipalVerify` | İlk aşamada v0.2.0 dağıtım ve test JAR’larıyla yeni bir dünya oluşturun. İkisini v0.3.0 ile değiştirin ve kalan iki aşamayı aynı dünya üzerinde ayrı sunucu süreçlerinde çalıştırın. Belediye yetkileri, ücretsiz tahsis, pozitif fiyat reddi, çok hacimli daire, kesişim reddi, kayıtların yeniden okunması ve dışarıdan değişen OPAC claim’i kontrol edilir. |
| `boundaries` | Yeni dünya ve Forge test oyuncuları. Seçim ve parsel kotaları, plot dışına taşma, daire birleştirme, iki plotta ortak izin, belediye başkanlığı devri ve şehir dağıtma. Vanilla hopper aktarımı ve su akışı hem daire içinde hem sınırda denenir. Piston ve patlama için Forge olayları çalıştırılır. |
| `network` | Yeni dünya ve aşağıdaki iki Forge istemcisi. HnNetA ile HnNetB aynı şehirde, tek chunk içinde farklı yüksekliklerde iki daire alır. İki oyuncu için de sunucunun normal oyuncu yerleştirme ve kırma işlemleri çalıştırılır. Dört oyuncu–daire eşleşmesinde gerçek blok durumu okunur. |

Yükseltme ve yeniden başlatmada `PASS municipalSetup` ve `PASS municipalVerify`, sınır testlerinde `PASS boundaries`, iki istemciyle `PASS network` gözlendi. İlk istemcinin bağlantısı ilk denemede zaman aşımına uğradı. Test istemcisi yerel sunucuya yeniden bağlanınca iki katın karşılıklı inşa denetimi tamamlandı.

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

## v0.2.0 arazi testleri

Aynı Forge ve OPAC sürümleriyle, boş bir test dünyasında `territorySetup` çalıştırın. Sunucu kapandığında aynı dünyayı `territoryVerify` ile açın. Örneğin Windows'ta:

```powershell
java -Xmx2G "-Dhn.test.phase=territorySetup" "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt" --world hn-territory-test nogui
java -Xmx2G "-Dhn.test.phase=territoryVerify" "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt" --world hn-territory-test nogui
```

| Ortam | Sınanan davranış |
| --- | --- |
| `territorySetup`, dedicated server ve Forge test oyuncuları | Başkanın claim'i ve gerçek OPAC sahibi, dolu chunk çakışmaları, devlet üyeliği değişiklikleri, Overworld/Nether ayrımı, şehir ve OPAC kotaları, OPAC'nin oyuncuya claim yasağı, OPAC üzerinden kaldırılıp tekrar alınan kişisel claim. |
| `territoryVerify`, ikinci sunucu süreci | Gerçek claim, şehir ve alt profil yeniden açılışta eşleşir. Başkasının claim'ine işaret eden yapay eski bağ başlangıçta temizlenir. Boyut kısıtlaması dağıtmayı durdurur, kalan arazi tekrar bırakılabilir. Başkanın ve başka bir oyuncunun kişisel claim'leri korunur. |
| `network`, iki bağlı Forge istemcisi | İstemciler Bursa'yı kurar ve daveti kabul eder. Sunucu, bağlı oyuncularla yabancı, vatandaş ve eski vatandaş için OPAC'nin blok kırma ve yerleştirme kararlarını sınar. Vatandaş claim komutlarını kullanamaz. Arazili şehirde başkan devri reddedilir. Katılma ve ayrılma boyunca kişisel OPAC party kimliği korunur. |
| v0.1.0 → v0.2.0 | Eski dağıtım ve test JAR'larıyla `setup` çalıştırılır. Aynı dünyada iki JAR da v0.2.0 ile değiştirilip `verify` çalıştırılır. Başkanlık, devlet, üyelik, bekleyen davetler ve kişisel OPAC claim'i doğrulanır. |

Her aşamanın günlüğünde ilgili `HN INTEGRATION PASS` satırını arayın. Ağ senaryosu gerçek istemci bağlantıları ve komut paketleri kullanır. Blok izinleri bu oyuncularla sunucuda doğrudan OPAC'nin kırma ve yerleştirme arayüzüne sorulur.

v0.2.0 doğrulamasında Java 17, Forge 47.4.28 ve OPAC 0.33.0 ile `PASS territorySetup`, `PASS territoryVerify` ve `PASS network` gözlendi. Ayrı yükseltme dünyasında v0.1.0 `PASS setup` sonucunu, ardından v0.2.0 aynı kayıtla `PASS verify` sonucunu verdi.

## v0.1.0 doğrulama kaydı

| Deneme | Gözlenen sonuç |
| --- | --- |
| Java 17 temiz derleme | Dağıtım ve ayrı test JAR'ı üretildi. |
| Dedicated server, iki ardışık süreç | `PASS setup` ve `PASS verify`. Şehir, vatandaşlık, liderlik, davet ve OPAC sahibi yeniden açılışta okundu. |
| İki ayrı Forge istemcisi | `PASS network`. HnNetA Bursa'yı kurdu, HnNetB Türkçe sohbet davetini alıp kabul etti. |
| OPAC eksik veya sürüm 0.32.7 | Forge her iki durumda da yüklemeyi durdurdu ve gerekli 0.33.0 sürümünü bildirdi. |

İlk otomatik istemci denemesinde hızlı bağlantı zaman aşımına uğradı. Test, oyun ana menüye ulaştıktan sonra normal bağlantıyı başlatacak şekilde güncellendi. OPAC, isteğe bağlı parti ve izin sağlayıcıları bulunmadığında günlükte varsayılan sağlayıcıları seçtiğini bildirdi.

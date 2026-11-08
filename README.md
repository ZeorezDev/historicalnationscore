# Historical Nations Core · v0.2.0

Historical Nations Core, alternatif tarih temalı Minecraft sunucularında şehirleri, devletleri ve vatandaşlığı yönetir. Oyuncular yönetim işlemlerini sohbet komutlarıyla yapar. Her dünya kaydı kendi şehir ve devlet verisini taşır.

| Bileşen | Bu sürüm |
| --- | --- |
| Minecraft Java Edition | 1.20.1 |
| Minecraft Forge | 47.4.28 ile derlendi ve sınandı |
| Java | 17 |
| Open Parties and Claims (OPAC) | Forge 1.20.1 için **0.33.0**, zorunlu |
| Mod kimliği | `historical_nations` |
| Sohbet dilleri | Türkçe ve İngilizce |

## Kurulum ve derleme

1. JDK 17 ile `JAVA_HOME` ayarlayın. Depo kökünde Windows için `.\gradlew.bat build`, Linux veya macOS için `./gradlew build` çalıştırın.
2. Forge 47.4.28 sunucusunun ve katılacak Forge istemcilerinin `mods/` klasörlerine `build/libs/historical_nations-0.2.0.jar` ile `open-parties-and-claims-forge-1.20.1-0.33.0.jar` dosyalarını koyun. v0.1.0'dan yükseltirken sunucuyu kapatın, dünyayı yedekleyin ve eski Historical Nations JAR'ını yenisiyle değiştirin. Mevcut şehirler sıfır şehir arazisiyle başlar.
3. Sunucuyu açın. Oyunda `/town list` ve `/town claimstatus` komutlarıyla kurulumu deneyin. Sohbet metinleri istemcinin dil ayarını izler.

[Forge 1.20.1 indirmeleri](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) · [Resmî OPAC 0.33.0 paketleri](https://chocolateminecraft.com/maven/xaero/pac/open-parties-and-claims-forge-1.20.1/0.33.0/)

## Komutlar

Komutlar bir oyuncu adına çalışır. Şehir ve devlet adları 1–32 karakter olabilir ve boşluk içerebilir. Örneğin `/town create Yeni Ankara` komutunda tırnak kullanılmaz. Aynı türde adlar büyük/küçük harfe duyarsız olarak benzersizdir.

| Şehir komutu | Etki |
| --- | --- |
| `/town create <ad>` | Bağımsız bir şehir kurar ve kurucusunu belediye başkanı yapar. |
| `/town invite <oyuncu>` | Belediye başkanı, bir şehre üye olmayan oyuncuyu davet eder. |
| `/town accept <ad>`, `/town deny <ad>` | Oyuncu ilgili şehrin davetini kabul eder veya reddeder. |
| `/town leave` | Vatandaş şehirden ayrılır. Belediye başkanının önce görevi devretmesi gerekir. |
| `/town info [ad]`, `/town list`, `/town members` | Şehir bilgilerini, tüm şehirleri veya kendi şehrinizin vatandaşlarını gösterir. |
| `/town setmayor <oyuncu>` | Belediye başkanlığını aynı şehrin başka bir vatandaşına devreder. |
| `/town disband` | Belediye başkanı şehri dağıtır. Başkent için önce devlet dağıtılır. |
| `/town claimstatus` | Bulunulan boyutu, chunk koordinatlarını, OPAC sahiplik durumunu ve sahibin şehir üyeliğini gösterir. |
| `/town claim` | Belediye başkanı bulunduğu boş chunk'ı şehir arazisi olarak sahiplenir. |
| `/town unclaim` | Belediye başkanı bulunduğu şehir chunk'ını bırakır. |
| `/town claims` | Kendi şehrinizin tüm boyutlardaki doğrulanmış chunk sayısını gösterir. |
| `/town claiminfo` | Bulunduğunuz chunk'ın doğrulanmış şehir adını gösterir. Kişisel claim'lerde şehir alanı boştur. |

| Devlet komutu | Etki |
| --- | --- |
| `/nation create <ad>` | Bağımsız bir şehrin başkanı devlet kurar. Kurucu şehir başkent olur. |
| `/nation invite <şehir>` | Devlet lideri bağımsız bir şehri davet eder. |
| `/nation accept <ad>`, `/nation deny <ad>` | Davet edilen şehrin belediye başkanı karar verir. |
| `/nation leave` | Başkent dışındaki bir üye şehrin başkanı şehrini devletten ayırır. |
| `/nation info [ad]`, `/nation list`, `/nation towns` | Devlet bilgilerini, tüm devletleri veya devletinizin üye şehirlerini gösterir. |
| `/nation disband` | Devlet lideri devleti dağıtır. Üye şehirler bağımsız olur ve vatandaşlarını korur. |

Davetler yeniden başlatmada korunur. Bir oyuncu şehre, bir şehir devlete katıldığında aynı alıcıya gönderilmiş diğer davetler temizlenir. Başkent belediye başkanlığı devredildiğinde devlet liderliği de devredilir.

## Kalıcı veri ve Java API

Dünya kaydının `data/historical_nations.dat` dosyasını Forge/Minecraft `SavedData` yönetir. Tüm boyutlar Overworld üzerinden aynı kayda erişir. Oyuncu, şehir ve devletler UUID ile tanımlanır. Devlet vatandaşlığı oyuncunun şehrinin güncel devletinden hesaplanır. Claim kayıtları OPAC'de tutulur.

Diğer modlar mantıksal sunucu iş parçacığında `HistoricalNationsApi.get(server)` kullanabilir. Tekil sorgular `Optional`, liste sorguları salt okunur anlık sonuç döndürür.

```java
import dev.zeorez.historicalnations.api.HistoricalNationsApi;

// Örneğin bir pasaport komutunun sunucu tarafındaki işleyicisinde
var api = HistoricalNationsApi.get(server);
var town = api.getPlayerTown(playerId);
var nation = api.getPlayerNation(playerId);
boolean leader = api.isNationLeader(playerId);
```

API ayrıca `getTownById`, `getNationById`, `isTownMayor`, `getNationTowns` ve `isPlayerCitizenOfNation` sorgularını sunar. İsteğe bağlı entegrasyonda tüketici mod, API başvurularını ayrı bir sınıfta tutabilir ve bu sınıfı yalnızca `ModList.get().isLoaded("historical_nations")` doğruysa çağırabilir.

## OPAC entegrasyonu ve sınırlar

**Sahiplik ve kotalar.** OPAC'deki fiziksel sahip başkanın gerçek oyuncu UUID'sidir. OPAC'nin party modunda da claim, party sahibinin hesabına yazılır. Sunucu ve süresi dolmuş claim'ler için OPAC'nin özel kimlikleri vardır. Şehir kurmak bunlardan hiçbirini kullanmaz. İlk şehir claim'i için başkanın boş bir OPAC alt profil hakkı gerekir. HNC, şehir UUID'sinden türettiği `hn_` ön ekli profili kullanır. Başkanın kişisel claim'leriyle şehir claim'leri aynı OPAC kotasına sayılır. Dünya klasöründe `serverconfig/historical_nations-server.toml` içindeki `maxChunksPerTown` ayrıca şehir kotasını belirler (varsayılan 16).

**Koruma ve vatandaşlık.** Normal oyuncu izin kontrolü ile OPAC'nin zorlamasız `tryToClaim` ve `tryToUnclaim` çağrıları geçerlidir. Alınmış chunk'ları dönüştürmek için önce mevcut sahip OPAC üzerinden bırakmalıdır. Bir şehre katılan oyuncu, doğrulanmış şehir chunk'larında OPAC'nin erişim eklentisi üzerinden inşa hakkı kazanır. Ayrıldığında bu hak biter. OPAC'nin ayrıca tanıdığı kişisel izinler, yönetici yetkisi ve koruma ayarları geçerlidir. Başkan şehir profilinin korumasını açık tutmalıdır. OPAC birincil party üyeliğini kendi yönetir. HNC şehirler için party kurmadığından davet kabulü mevcut party üyelikleriyle çakışmaz. Devlet liderliği ayrıca inşa hakkı vermez.

**Başkan devri ve şehir dağıtma.** OPAC 0.33.0 çok sayıda claim'i yeni başkana tek işlemde, iki oyuncunun kotalarını ve kişisel claim'lerini koruyarak aktaramıyor. Bu nedenle önce şehrin arazilerini bırakın, sonra başkanlığı devredin. Şehri dağıtırken HNC yalnızca doğrulanmış şehir arazilerini bırakır. Başka boyut, mesafe veya anchor kısıtlaması temizliği durdurursa şehir varlığını sürdürür. İlgili konuma gidip kalan arazileri bırakabilir ve komutu yeniden çalıştırabilirsiniz. OPAC alt profili kişisel kullanım ihtimali nedeniyle otomatik silinmez. Devlete katılma, ayrılma ve devletin dağılması şehir arazilerini etkilemez.

**Kayıt ve kurtarma.** Her ilişki boyut, chunk X/Z, şehir UUID'si, sahip UUID'si ve OPAC alt profil indeksini taşır. Başlangıçta ve kullanım sırasında canlı OPAC kaydı ile profil karşılaştırılır. OPAC üzerinden kaldırılan veya sahipliği değişen claim'in şehir bağı temizlenir. Kesinti sonrası yalnızca OPAC'de kalan claim kişisel kalır. Yalnızca HNC'de kalan bağlantı kaldırılır. Yedekten geri yüklerken tüm dünya klasörünü aynı tarihten alın.

Gradle, Mojang mappings için OPAC'nin resmî `:dev` paketini ve MixinGradle kullanır. Sunucuya normal OPAC paketi kurulur. [İncelenen 1.20 kaynak sürümü](https://github.com/thexaero/open-parties-and-claims/tree/fd12115e97af9642005742da9586939e9803a327) · [OPAC API belgeleri](https://thexaero.github.io/open-parties-and-claims/javadoc/) · [Forge SavedData](https://docs.minecraftforge.net/en/1.20.1/datastorage/saveddata/)

## Test

`gradlew build` dağıtım JAR'ını üretir. Komut, çok oyunculu yetki, claim ve yeniden başlatma senaryoları ayrı bir test modülüyle **gerçek Forge dedicated server** üzerinde çalıştırılır. Test JAR'ı yalnızca geçici test sunucusu içindir. [Testleri yeniden çalıştırma](src/integration/README.md)

## Yol haritası

- **Sonraki sürümler.** Pasaport ve belge modları salt okunur vatandaşlık API'sine bağlanabilir. Ekonomi, seçimler ve diplomasi sonraki sürümlerin kapsamındadır.

## Lisans

Historical Nations Core, depodaki [GNU GPLv3 lisansı](LICENSE) altında dağıtılır. Open Parties and Claims, Xaero96'nın [LGPL-3.0-only lisanslı ayrı projesidir](https://github.com/thexaero/open-parties-and-claims/tree/fd12115e97af9642005742da9586939e9803a327).

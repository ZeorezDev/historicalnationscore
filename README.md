# Historical Nations Core · v0.6.0

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
2. Forge 47.4.28 sunucusuna ve istemcilere `build/libs/historical_nations-0.6.0.jar` ile `open-parties-and-claims-forge-1.20.1-0.33.0.jar` kurun. Yükseltmeden önce sunucuyu kapatıp dünyayı yedekleyin ve eski Historical Nations JAR’ını değiştirin. v0.2.0 dünyasındaki her doğrulanmış şehir chunk’ı, ilk yüklemede belediyeye ait bir residential plot alır.
3. Sunucuyu açın. Oyunda `/town list` ve `/town claimstatus` komutlarıyla kurulumu deneyin. Sohbet metinleri istemcinin dil ayarını izler.

[Forge 1.20.1 indirmeleri](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) · [Resmî OPAC 0.33.0 paketleri](https://chocolateminecraft.com/maven/xaero/pac/open-parties-and-claims-forge-1.20.1/0.33.0/)

## Komutlar

Komutlar bir oyuncu adına çalışır. Şehir ve devlet adları 1–32 karakter olabilir ve boşluk içerebilir. Örneğin `/town create Yeni Ankara` komutunda tırnak kullanılmaz. Aynı türde adlar büyük/küçük harfe duyarsız olarak benzersizdir.

| Şehir komutu | Etki |
| --- | --- |
| `/town create <ad>` | Bağımsız bir şehir kurar ve kurucusunu belediye başkanı yapar. |
| `/town invite <oyuncu>` | Belediye başkanı veya yardımcısı bağımsız bir oyuncuyu davet eder. |
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

## Ekonomi ve bankacılık

`/money` bakiyenizi gösterir. Örneğin `/money pay Ayse 25` hesabınızdan Ayşe’ye 25 tam birim aktarır. Dünya klasöründeki `serverconfig/historical_nations-server.toml` dosyasında `currencyName` ve `currencySymbol` varsayılan olarak Lira ve ₺ değerlerini alır. `startingBalance` varsayılanı sıfırdır. Ayar, her oyuncunun v0.4.0 ile ilk girişinde bir kez uygulanır. Eski dünyalardaki şehirler, üyelikler ve satış teklifleri korunur. Mevcut pozitif fiyatlı ilanlar yükseltmeyle satın alınabilir.

| Komut | Kullanım |
| --- | --- |
| `/town bank`, `/nation bank` | Kendi belediyenizin veya devletinizin bakiyesini gösterir. |
| `bank deposit <tutar>`, `bank withdraw <tutar>` | İki komut kökünde üyeler yatırabilir. Şehirden başkan, devletten başkent belediye başkanı çekebilir. |
| `/town bank history`, `/nation bank history` | Hesabın son 32 kaydını zaman, yetkili, hesaplar, tutar ve gerekçeyle gösterir. |
| `/town tax [set <tutar>]`, `/nation tax [set <tutar>]` | Günlük sabit vergiyi gösterir. İlgili lider tutarı değiştirir. Başkandan da vatandaş vergisi, başkentten de devlet vergisi alınır. |
| `/town upkeep [set <tutar>]`, `/nation upkeep [set <tutar>]` | Kurumun günlük bakım giderini gösterir veya değiştirir. |
| `/economy give\|take\|set <player\|town\|nation> <hedef> <tutar> [gerekçe]` | Konsol veya seviye 2 operatör hesabı düzenleyebilir. Örnek: `/economy give town "Yeni Ankara" 100 kuruluş desteği`. |
| `/economy info <player\|town\|nation> <hedef>` | Operatöre hedefin bakiyesini ve geçmişini gösterir. |

Vergi ve bakım sıfırla başlar. Sunucu, UTC takvim günü değiştiğinde sırayla vatandaş vergilerini, üye şehir vergilerini ve bakım giderlerini işler. Kapatıldığı günleri topluca tahsil etmez. Tam ödenemeyen işlem için para kesilmez. İlgili hesaplara eksik tahsilat yazılır ve çevrimiçi ilgili oyunculara haber verilir. Son işlenen gün ve bakiyeler aynı dünya kaydında tutulur.

Şehir ve devlet bakiyeleri kurumun UUID’sine bağlıdır. `bankLiquidation = "REQUIRE_EMPTY"` bakiyeli bir kurumun dağıtılmasını önler. `"PAY_LEADER"` kalan tutarı güncel lidere aktarır. Alıcının bakiyesi taşacaksa işlem durur. Belediye başkanlığı devri ve devlet üyeliği değişikliklerinde hesap korunur.

Diğer modlar sunucu iş parçacığında `EconomyApi.get(server)` üzerinden `getBalance(UUID)`, `getTownBalance(UUID)` ve `getNationBalance(UUID)` çağrılarını kullanabilir. `transfer(ServerPlayer, UUID, long)` oyuncular arası ödeme yapar. `deposit(ServerPlayer, EconomyApi.Bank, long)` ve `withdraw(ServerPlayer, EconomyApi.Bank, long)` mevcut fonları kişisel hesap ile oyuncunun bağlı olduğu kurum arasında taşır. Banka seçenekleri `TOWN` ve `NATION` değerleridir. Yetersiz bakiye, geçersiz tutar veya yetki durumunda `RuleViolation` döner. API üzerinden para basma işlemi sunulmaz.

Historical Documents 0.3.0 dağıtım JAR’ı Core sürümünü tam olarak 0.2.0’a sabitler. İki modu birlikte kurmak için Documents paketinin bağımlılık aralığının Core 0.6.0’ı kapsaması gerekir.

## Eşya ticareti ve gümrük

Satıcı ve alıcı birer devletin vatandaşı olmalı ve aynı boyutta birbirlerine en fazla sekiz blok uzakta durmalıdır. Satıcı eşyayı ana eline alır. Örneğin `/trade offer Ayse 64 500 DEM`, 64 eşyanın tamamını 500 DEM karşılığı teklif eder. Alıcıya eşya, satıcının payı, gümrük tutarı, toplam ödeme ve kabul komutu gönderilir. %10 ithalat vergisiyle alıcı 550 DEM öder. Satıcıya 500 DEM, alıcının devlet hazinesine 50 DEM geçer.

| Komut | Kullanım |
| --- | --- |
| `/trade info <id>`, `/trade accept <id>` | Taraflar teklifi inceleyebilir. Belirtilen alıcı kabul edebilir. |
| `/trade deny <id>`, `/trade cancel <id>` | Alıcı teklifi reddeder veya satıcı iptal eder. Teklifler 120 saniye geçerlidir. Satıcı aynı anda 32 açık teklif tutabilir. |
| `/nation tariff info`, `/nation tariff set <yüzde>`, `/nation tariff clear` | Lider ithalat vergisini %0–50 arasında, iki ondalık basamakla belirler. Varsayılan %0’dır. |
| `/nation embargo add <devlet>`, `remove <devlet>`, `list` | Lider diğer devlete ambargo koyar veya kaldırır. Tek tarafın kararı çift yönlü kayıtlı ticareti kapatır. Liste hem uygulanan hem alınan ambargoları gösterir. |
| `/nation trade stats`, `imports`, `exports` | Tamamlanan uluslararası işlemlerin sayısı, para koduna göre hacim ve gümrük geliri, işlem sayısına göre ilk beş ortak. İthalat ve ihracat görünümleri son 16 kaydı da gösterir. |

Gümrük yalnızca iki farklı devlet arasında uygulanır ve kesirli tutar bir üst tam birime yuvarlanır. Vatandaşlık veya oran değişince eski koşullarla ödeme yapılamaz. Alıcının normal envanterinde eşyanın tamamı için yer ve seçili parada toplam tutar bulunmalıdır. Teklif süresince mal satıcının elinde kalır. Kabul anında miktar, eşyanın NBT verileri ve Forge özellikleri yeniden kontrol edilir.

Gümrük ve ambargo `/trade` ile kaydedilen satışlara uygulanır. Yere atılan eşyalar, sandıklar ve başka modların aktarımları bu kapsamın dışındadır. Bir satış ekonomik faaliyet hacmine satış bedeli kadar, bir defa eklenir. İthalat ve ihracat hacimleri her para için kendi biriminde gösterilir.

## Ulusal paralar ve merkez bankası

v0.4.0 dünyasında var olan cüzdanlar, banka hesapları, vergi, bakım ve açık satış ilanları `TRY` cinsinden yüklenir. Referans paranın adı ve simgesi dünya ayarlarından okunur. Yeni devletlerin merkez bankası otomatik oluşur. Devlet lideri örneğin `/nation currency create Reichsmark RM DEM` ile resmî parayı tanımlar. Boşluk içeren adları ve ₺ gibi simgeleri çift tırnak içine alın. Kod 3–6 Latin harfinden oluşur ve kalıcıdır. Dağılan devletin parası listede † ile görünür. Eski bakiyelerle ödeme ve rezervli döviz işlemleri sürer.

| Komut | İşlem |
| --- | --- |
| `/nation currency list`, `/nation currency info` | Kayıtlı paralar ve devletin kullandığı para. |
| `/money balance [kod]`, `/money pay <oyuncu> <tutar> [kod]` | Cüzdan bakiyeleri ve belirtilen parayla ödeme. Eski ödeme komutu TRY kullanır. |
| `/town bank <kod>`, `/nation bank <kod>` | Kuruma ait seçili para bakiyesi. `bank deposit <tutar> [kod]`, `bank withdraw <tutar> [kod]` ve `bank history [kod]` aynı para seçimini destekler. |
| `/plot forsale <fiyat> [kod]`, `/quarter forsale <fiyat> [kod]` | Varsayılan olarak arazinin bağlı olduğu devletin o günkü resmî parasını kullanır. İlan kapatılana kadar parası sabittir. |
| `/nation centralbank issue <tutar>`, `/nation centralbank interest <yüzde>` | Lider kendi parasını devlet hazinesine basar ve %0–100 arasında politika faizi belirler. Günlük basım limiti varsayılan 10.000 birimdir. |
| `/nation economy`, `/nation centralbank info` | Arz, günlük enflasyon, başlangıç değeri 1 olan fiyat endeksi, TRY kuru ve politika faizi. Resmî para kurulmadan önce arz ve kur ortak TRY’ye aittir. |
| `/exchange rates`, `/exchange history <kod>` | Bir birim paranın TRY karşılığı, mevcut rezervler ve son 32 günlük kur kaydı. |
| `/exchange fund <kod> <tutar>` | Oyuncunun mevcut bakiyesinden ortak döviz rezervine karşılıksız ve kalıcı katkı. |
| `/exchange convert <kaynak> <hedef> <tutar>` | Alınan miktar, verilen miktar × kaynak kuru ÷ hedef kuru ile hesaplanır ve tam birime aşağı yuvarlanır. Sıfır çıktı, yetersiz fon veya dolu hedef hesapta işlem reddedilir. |

**Dövizi başlatmak için** bir lider önce para basabilir, ardından hazineden çekerek `/exchange fund DEM 500` ile rezerv sağlayabilir. TRY karşılığı satmak için ayrıca TRY rezervi gerekir. Her yeni para 1 TRY kuruyla başlar. Bir dönüşümde oyuncu ve havuzun her iki para bakiyesi aynı kayıt içinde güncellenir.

**Günlük model** UTC günü değiştiğinde bir kez çalışır. Arz, rezerv dahil tüm hesapların toplamıdır. Model, önceki kapanışa göre arz artışını, oyuncular arası ödemeleri ve ücretli mülk satışlarını kullanır. Aşağıdaki formüllerde 100 baz puan yüzde 1’dir. Her bölmede tam sayıya doğru kesilir.

- Arz büyümesi = 10.000 × arz farkı / en büyük(önceki arz, faaliyet tabanı), ±4.000 baz puanla sınırlı. Faaliyet = 10.000 × günlük işlem hacmi / en büyük(güncel arz, faaliyet tabanı), en fazla 2.000 baz puan.
- Günlük enflasyon = büyüme / 8 − faaliyet / 40 − politika faizi / 50, ±500 baz puanla sınırlı. Endeks bu oranla çarpılarak güncellenir. Politika faizi yalnızca bu hesapta kullanılır. Özel para oluşturmamış bir devlette yerel faaliyet, o devletin oyuncularının TRY ödemeleri ve alımlarıdır.
- Kur hareketi = TRY enflasyonu − ilgili para enflasyonu + talep etkisi. Talep etkisi, oyuncuların dövizde aldığı eksi sattığı birimlerin arza oranıdır. Bu oran ±4.000 baz puanda sınırlandırılıp sekize bölünür. Günlük kur değişimi varsayılan ±%3’tür. Kurlar ve endeksler altı ondalık basamakla saklanır.
- Dünya ayarları `dailyIssueLimit`, `dailyFxLimitBps`, `economicActivityBaseline` (varsayılan 1.000) ve `indexTaxesAndUpkeep` (varsayılan kapalı) değerleridir. Vergi veya bakım tutarı belirlenirken kurumun o günkü resmî parası ve bu paranın endeksi saklanır. Endeksleme açılırsa tahsilat, belirlenen tutar × güncel endeks / başlangıç endeksi üzerinden yapılır. Oyuncu bakiyesi enflasyon nedeniyle doğrudan değiştirilmez.

**Java API.** Eski `EconomyApi` çağrıları TRY kullanır. Altı bakiye ve işlem metodunun son parametresi `String currency` olan sürümleri kayıtlı para kodu kabul eder. `ParcelView.currency()` ilan fiyatının para kodunu verir.

## Belediye, plot ve alt parsel komutları

Başkan `/town deputy set <oyuncu>` ve `/town deputy remove` ile bir vatandaşını yardımcı atayabilir veya görevden alabilir. Başkan ve yardımcısı `/town invite`, `/town kick <oyuncu>`, `/town description set <metin>` ve `/town description clear` kullanabilir. Başkanın görev devri ve şehir dağıtma yetkileri kendisinde kalır. `/town info` yardımcıyı ve açıklamayı gösterir.

Her plot bir doğrulanmış şehir chunk’ıdır. Her quarter tek plot içindeki bir veya daha fazla kapalı blok hacminden oluşur. Daire oluşturmak için şu sırayı izleyin:

1. Başkan veya seviye 2 sunucu yöneticisi olarak plotta `/quarter wand` alın. İlk köşeye sol, ikinci köşeye sağ tıklayın. Alternatif olarak köşelerde durup `/quarter pos1` ve `/quarter pos2` kullanın.
2. Aynı plotta `/quarter create Zemin Kat` çalıştırın. Dairenin içinde durup `/quarter set owner <oyuncu>` kullanın.
3. Üst kat için aynı işlemi farklı Y aralığında tekrarlayın. Her iki köşe dahildir. Birinci daire 64–67 yüksekliğini kaplıyorsa ikinci daire 68’den başlayabilir.
4. Başka bir hacim seçtikten sonra hedef dairenin içine dönüp `/quarter addselection` kullanın. `/quarter replace <parça numarası>` bir hacmi değiştirir. `/quarter merge <kaynak daire>` kaynak hacimlerini içinde bulunduğunuz daireye ekler. Hedefin sahibi ve izinleri geçerli olur. `/quarter delete` daireyi kaldırır.

| Komut | Kullanım |
| --- | --- |
| `/plot info`, `/quarter info` | Ad, tür, UUID, sahip, teklif, grup, açıklama, ayarlanmış izinler ve hacim sayısı. |
| `set name <ad>`, `set type <tür>`, `set owner <oyuncu>` | İki komut kökünde de geçerlidir. Tür ve tahsis başkana veya sunucu yöneticisine aittir. |
| `forsale <fiyat>`, `notforsale`, `claim`, `unclaim`, `evict` | Sahip veya yönetici teklif verebilir. Sıfır fiyatla tahsis yapılır. Ücretli alımda para özel mülkün sahibine veya belediye mülkü için şehir bankasına aktarılır. Tahsis değişiminde kişisel güven ve izinler sıfırlanır. |
| `trust add/remove <oyuncu>`, `perm <eylem> <grup> true/false` | Eylemler `build`, `destroy`, `interact`, `item-use`. Gruplar `trusted`, `citizen`, `outsider`. |
| `/plot group create/join/delete <ad>`, `/plot group leave` | Belediye yöneticisi grubu kurar ve plotları ekler. Gruptaki bir plotta verilen `/plot perm` tüm üyelerin aynı ayarını günceller. |
| `/quarter set color <RRGGBB>`, `/quarter set description <metin>`, `/quarter show` | Ad ve açıklamayı kişiselleştirir, sınırları seçilen renkte oyuncuya gösterir. |

**Erişim sırası.** OPAC erişimi üst sınırdır. Belediye mülkünde yönetici, tahsisli mülkte sahibi normal kullanım hakkına sahiptir. Quarter içinde quarter kuralları, dışında plot kuralları uygulanır. Güvenilen oyuncu, vatandaş ve yabancı için açık kurallar türün varsayılanından önce değerlendirilir. Plot grubunun kuralı aynı eylem ve grup için yerel ayarın önüne geçer. Başkan ve sunucu yöneticisi tahsisi değiştirebilir. Şehirden ayrılmak veya çıkarılmak, elde tutulan kişisel tahsisleri ve güven ilişkilerini kaldırır.

| Tür | Kullanım kuralı |
| --- | --- |
| Residential, apartment | Tahsisli mülkte inşa sahibine ve güvenilenlere ayrılır. Tahsissiz apartman dairesini belediye yönetir. |
| Commercial / shop | OPAC erişimi olan ziyaretçiler varsayılan olarak dükkân kapılarını ve düğmelerini kullanabilir. |
| Farm, wilderness / wilds | İnşa ve yıkım çiftlikte tanımlı ekinlere ve tarım bloklarına, wilds alanında kütük, yaprak, fidan ve çiçeklere sınırlıdır. |
| Industrial, public | Sanayi plotunda vatandaşlar varsayılan olarak etkileşim ve eşya kullanımı yapabilir. Public plotta blok etkileşimi ziyaretçilere de açıktır. |
| Embassy | Başka şehir vatandaşına tahsis ve güven verilebilir. Yabancıya ayrıca OPAC erişimi gerekir. |
| Inn, station | Handa ziyaretçilerin yatak etkileşimi, istasyonda vatandaşların etkileşimi ve ray üzerinde eşya kullanımı desteklenir. |
| Arena | Arena sınırını geçen oyuncu saldırıları engellenir. İçeride sunucunun ve OPAC’nin PvP kuralları geçerlidir. |
| Bank, jail | Bankada vatandaşlar ender sandığı kullanabilir. Cezaevi belediye mülkü olarak kalır. Cezalandırma ve hapis süreleri bu sürümün kapsamı dışındadır. |

Dünya sunucu ayarlarında `maxQuartersPerTown` (128), `maxCuboidsPerQuarter` (16), `maxCuboidVolume` (16.384 blok) ve `quarterEntryMessages` bulunur. Kotalar yeni seçimlere uygulanır. Claim kaybında mülkler temizlenir. OPAC’nin chunk erişim arayüzü Y koordinatını taşımadığı için tek bir daireye giren yabancıya yeni bir chunk izni verilmez. Forge katmanı oyuncu işlemini hedef blokta durdurabilir. Patlamalarda korunan bloklar listeden çıkarılır. Piston, vanilla sıvı akışı ve vanilla hopper giriş/çıkışı parsel sınırında kontrol edilir. Forge olaylarını ve vanilla aktarım yollarını atlayan modlu borular, makineler ve doğrudan dünya değişiklikleri ayrıca mod bazında entegrasyon gerektirir.

Davranışları incelenen sürümler için [Towny](https://github.com/TownyAdvanced/Towny/tree/7492ae6c5d4ddfb590fc298ba9fd5a4acbe7c2be), [Quarters](https://github.com/jwkerr/Quarters/tree/fbf604272e9077477f8cd13729d5b5df38764dfc) ve [OPAC](https://github.com/thexaero/open-parties-and-claims/tree/fd12115e97af9642005742da9586939e9803a327) kaynaklarına bakın.

## Kalıcı veri ve Java API

`Parcels.plot(server, dimension, blockPos)` ve `Parcels.quarter(...)` canlı OPAC bağını doğrular ve salt okunur `Optional<ParcelView>` döndürür. UUID, konum, sahip, fiyat, grup, güvenilenler, ayarlanmış izinler ve hacimler anlık görüntüde yer alır. Historical Documents mevcut liderlik ve vatandaşlık çağrılarını kullanabilir.

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

- **Sonraki sürümler.** Pasaport ve belge modları salt okunur vatandaşlık API'sine bağlanabilir. Seçimler ve diplomasi sonraki sürümlerin kapsamındadır.

## Lisans

Historical Nations Core, depodaki [GNU GPLv3 lisansı](LICENSE) altında dağıtılır. Open Parties and Claims, Xaero96'nın [LGPL-3.0-only lisanslı ayrı projesidir](https://github.com/thexaero/open-parties-and-claims/tree/fd12115e97af9642005742da9586939e9803a327).

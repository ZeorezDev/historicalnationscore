# Sürüm geçmişi

## 11.0.0 — Flag War & Territorial Conquest Update

- Devletler arası resmî savaş ilanı (`/war declare <devlet>`), varsayılan **24 saatlik** hazırlık süresi (`preparationHours`), **48 saatlik** savaş sonrası ateşkes (`truceHours`), karşılıklı ittifaklar (`/nation ally <devlet>`, `/nation unally <devlet>`) ve müttefiklerin savaşa katılımı (`/war join <devlet>`) eklendi.
- Zamanlanmış savaş saatleri (`[war.schedule]`: varsayılan `Europe/Istanbul` Cumartesi ve Pazar `18:00–23:00`) ve çevrimdışı savunma koruması + kasıtlı çıkışa karşı yapılandırılabilir tolerans süresi (`defenderLogoutGraceSeconds = 300`) eklendi. Savaş penceresi dışında yeni bayrak dikilemez ve aktif sayaçlar otomatik duraklatılır.
- Nodes tarzı **War Capture Flag** çit ele geçirme sistemi eklendi: düşman sınır chunk'ına `minecraft:oak_fence` (veya yapılandırılan çit blokları) yerleştirildiğinde 3 bloklu savaş bayrağına (`çit + 10 aşamalı ilerleme yünü + meşale`) dönüşür, yakındaki oyunculara BossBar geri sayımı gösterilir ve `300` saniye sonunda chunk **OCCUPIED** (işgal) durumuna geçer.
- Doğrudan gökyüzü erişimi, bina/quarter içi ve su altı engeli, bayrak çevresi inşaat/kova yasağı (`flagNoBuildRadius`), savunan oyuncunun veya patlamaların (TNT / Historical Artillery) eşya çoğaltmadan bayrağı imha etmesi ve şehir binaları ile sandıkların OPaC korumasının korunması sağlandı.
- Cephe hattı ilerleme sistemi (`WarFrontier`) eklendi: yalnızca kendi sınırına, işgal edilen cepheye veya en fazla `3` sahipsiz wilderness chunk uzağındaki dış düşman sınırına saldırılabilir; iç bölgelere chunk atlama engellenir.
- Çift taraflı sürüm kilitli **Peace Treaty GUI** (`/war peace <devlet>`) ve **iki aşamalı geri alınabilir (transactional) OPaC ilhak servisi** (`TownTerritories.annexChunksTransactionally`) eklendi. İlhak sırasında altın ücreti alınmaz; mevcut Plot, 3D Quarter ve v10.0.0 Şehir Gelişim Seviyesi / Yıldız değerlendirmeleri korunur. Herhangi bir OPaC hatasında tüm kısmi işlemler atomik olarak geri alınır.
- `/hnc` yönetim paneline **Savaş ve Diplomasi (`WAR & DIPLOMACY`)** sekmesi, **Political World Map**'e **WAR MAP MODE** (`/war map`), diyagonal işgal tarama deseni, kesikli işgal sınırları, tıklanabilir aktif saldırı bayrağı simgeleri ve işgal altındaki kasaba sağ-tık bilgileri eklendi.
- Dünya haritası kamera sınır kilidi (`lockCameraToBounds`), oyun içi `2:1` dikdörtgen Dünya sınırı koruyucusu (`WorldBoundsEnforcer`) ve OP sağ-tık harita ışınlanma kartı eklendi.

## 10.0.0 — Urban Development & City Evaluation

- OP 4 yöneticileri `/hnc admin cities` veya siyasi haritadaki şehir kartından değerlendirme açabilir. Rehber şehir, devlet, seviye ve yıldız filtrelerini, tarihe ve puana göre sıralamayı destekler.
- Mimari (30), ulaşım (20), konut (20), kamu hizmetleri (20) ve tamamlanmışlık (10) puanları elle girilir. Önerilen yıldızı değiştirmek için gerekçe gerekir. Yönetici gelişim seviyesini ayrıca seçer.
- Oyuncular şehir dosyasında kategori puanlarını, değerlendirme tarihini ve herkese açık özeti görür. Özel notlar ve sürümlü geçmiş yöneticilere açıktır. Eşzamanlı düzenlemelerde eski kopyanın kaydı reddedilir.
- Harita, şehir isimlerini gelişim seviyesine ve yakınlaştırmaya göre gösterir. Bir değerlendirmede yalnızca ilgili şehir için güncelleme gönderilir.
- v9 şehirleri ilk değerlendirmelerine kadar Yerleşim seviyesinde ve yıldızsız görünür. Kayıtlar kalıcı şehir kimliğine bağlıdır. Sunucu ve istemci modunu birlikte v10 sürümüne güncelleyin.

## 9.0.0 · Interactive Political World Map Update

- Tam ekran etkileşimli **Political World Map** (`M` tuşu ve `/map` komutu) eklendi. 1930–1950 stratejik harita estetiğiyle 6 bağımsız katman (Terrain, Political, Nation Borders, Town Borders, Settlement Labels, Player Location) desteklenir.
- Çok seviyeli (`z0`..`z7`, `1`..`128` blok/piksel) Anvil `.mca` arazi tile piramidi ve sunucu tick döngüsünden bağımsız arka plan/CLI harita hazırlama aracı (`AnvilTileGenerator` / `/map admin render`) eklendi. Harita açmak veya gezmek asla dünya chunk'larını zorla yüklemez; oyuncular ilk girişten itibaren tüm coğrafyayı inceleyebilir.
- 1:500 MOTFE Dünya haritası (`73.728 × 36.864` blok, `-36864..36863` X, `-18432..18431` Z, `204.8` blok/derece equirectangular) için `calibration.json` ve `[earthMap]` kalibrasyon desteği eklendi.
- Yalnızca OPAC üzerinden doğrulanmış gerçek şehir chunk'ları boyanır; sahipsiz ara bölgeler ve kişisel OPAC claim'leri siyasi sınırlara dahil edilmez. Aynı devlete bağlı bitişik kasabalar arasında devlet sınırı çizilmez, yalnızca ince belediye sınırı gösterilir.
- Kalıcı devlet harita renkleri, `/nation color <#RRGGBB>`, `/nation setcapital <kasaba>`, `/town rename <ad>` ve `/nation rename <ad>` komutları ile sürüm numaralı sıkıştırılmış snapshot + canlı delta senkronizasyonu (`MapNetwork` / `PoliticalMapServer`) eklendi.
- Claim edilmiş kasaba toprağına sağ tıklayınca açılan **Town Bilgi Kutucuğu** (`VIEW TOWN` ve tıklanabilir devlet başlığı), harita arama çubuğu (kasaba, devlet ve `X Z` koordinat arama) ve v8.0.0 yönetim ekranlarıyla çift yönlü `Haritada Göster` (`VIEW ON MAP`) entegrasyonu eklendi.
- Sunucu kimliğine (`MapId`) göre yalıtılmış GPU (`DynamicTexture` LRU) ve disk tile önbelleği (`ClientTileCache`) ile `64×64` chunk uzamsal indeksleme (`ClientMapState`) eklendi.
- `/town spawn`, `/town set spawn` (`/town setspawn`), `/nation spawn` ve `/nation set spawn` (`/nation setspawn`) komutları ile yönetim GUI (`OfficeActions`) kısayolları eklendi; spawn noktaları OPAC kasaba/devlet arazisi doğrulamasıyla kalıcı olarak saklanır.

## 8.0.0 · Political Management GUI Update

- `/hnc` yönetim paneli, oyuncu istatistikleri, belediye sakinleri, üye kasabalar ve ulusal ekonomi ekranları eklendi.
- `/t`, `/n`, `/town` ve `/nation` bilgi ve liste komutları, uyumlu oyunculara sunucudan güncel bir sayfa gönderir.
- Rehberler ad, nüfus ve doğrulanmış chunk sayısına göre iki yönlü sıralanabilir. Devletler ayrıca kasaba sayısı ve güncel kurla TRY cinsinden toplam hazine değerine göre karşılaştırılır.
- Yönetim işlemleri mevcut sunucu komutlarını ve yetki kontrollerini kullanır. Dağıtma, görev devri ve üyelik sonlandırma için onay ekranı eklendi.
- Türkçe ve İngilizce arayüz metinleri, dönem temalı simgeler, arama ve sayfalama eklendi. Dünya kayıt biçimi korundu.

## 7.0.0 · Gold-Based Settlement Update

- Şehir kurma, devlet kurma ve şehir chunk claim işlemleri için doğrudan vanilla Minecraft Altın Külçesi (`minecraft:gold_ingot`) maliyeti eklendi. Mevcut ulusal para, para basımı, döviz, enflasyon, ticaret ve banka sistemleri bozulmadan korundu.
- `/town create <ad>` varsayılan olarak envanterden **2 Altın Külçesi** tahsil eder ve oyuncunun bulunduğu ilk şehir chunk'ını OPAC üzerinden ücretsiz olarak sahiplenir. Kuruluş veya ilk claim başarısız olursa altın alınmaz.
- `/town claim` şehir büyüklüğünden ve devlet üyeliğinden bağımsız olarak sabit **1 Altın Külçesi** bedel alır. Aynı chunk iki kez ücretlendirilmez, başarısız işlemlerde altın kaybolmaz ve varsayılan olarak `/town unclaim` altın iadesi yapmaz.
- `/nation create <ad>` varsayılan olarak envanterden **128 Altın Külçesi (2 stack)** tahsil eder; başka bir devletin varlığına ihtiyaç duymadan ilk devlet sıfırdan kurulabilir.
- Sunucu ayarlarına (`serverconfig/historical_nations-server.toml`) `[economy] enableGoldCosts`, `[town] creationGoldCost`, `[nation] creationGoldCost`, `[claims] claimGoldCost` ve `[claims] refundOnUnclaim` seçenekleri eklendi. Önceden kurulmuş şehir, devlet ve claim kayıtlarına geriye dönük ücret uygulanmaz.

## 0.6.0 · Uluslararası ticaret ve gümrük

- Devlet vatandaşları ana ellerindeki eşyayı belirli bir oyuncuya mevcut para birimleriyle satabilir. Alıcı, gümrük dahil toplamı kabulden önce görür.
- Devlet liderleri ithalat vergisi belirleyebilir ve diğer devletlerle kayıtlı ticarete ambargo koyabilir.
- Uluslararası işlemler, gümrük gelirleri ve ticaret ortakları dünya kaydında saklanır. Satış bedeli mevcut ekonomik faaliyet hesabına eklenir.

## 0.5.0 · Ulusal paralar ve merkez bankaları

- v0.4.0 bakiyeleri, vergileri ve ilanları ortak TRY olarak korunur. Oyuncu, belediye ve devlet hesapları aynı anda birden fazla para tutabilir.
- Liderler kalıcı ulusal para kodu oluşturabilir, günlük limit içinde hazineye para basabilir ve politika faizi belirleyebilir. Dağılan devletlerin paraları tarihsel kayıtta kalır.
- Döviz havuzları oyuncuların aktardığı rezervleri kullanır. Kur, fiyat endeksi ve isteğe bağlı gider endekslemesi günlük hesaplanır.

## 0.4.0 · Ekonomi ve bankacılık

- Oyuncu cüzdanı, belediye bankası, devlet hazinesi, işlem geçmişi ve operatör komutları eklendi. Para birimi, ilk giriş ödeneği ve kurum tasfiyesi sunucu ayarlarından seçilebilir.
- Ücretli plot ve quarter satışlarında bedel önceki sahibine veya belediyeye aktarılır. Sıfır fiyatla ücretsiz tahsis sürer.
- Günlük sabit vergi ve bakım gideri ilk kurulumda kapalıdır. Eksik tahsilat kayıt altına alınır. Son tahsilat günü yeniden başlatmada korunur.
- Sunucu tarafı ekonomi API’si bakiye, ödeme, yetkili para çekme ve para yatırma işlemlerini sunar. Bakiyeler mülklerle aynı SavedData dosyasında saklanır.

## 0.3.0 · Belediye ve parseller

- Başkan yardımcısı atama, vatandaş çıkarma ve şehir açıklaması.
- Doğrulanmış şehir chunk’larında plotlar, ortak izin grupları, tür kuralları, ücretsiz tahsis ve fiyatlı teklifler.
- Birden çok hacim içeren 3D alt parseller, seçim aracı, düzenleme, birleştirme, sınır parçacıkları ve giriş bildirimi.
- OPAC üzerinde konuma göre oyuncu erişimi, piston, patlama, vanilla sıvı ve hopper sınır kontrolleri.
- v0.2.0 dünya yükseltmesi ve diğer modlar için salt okunur mülk sorguları.

## 0.2.0 · Şehir arazileri

- Belediye başkanları `/town claim` ve `/town unclaim` ile şehir adına OPAC chunk'ları yönetebilir. `/town claims` doğrulanmış arazi sayısını, `/town claiminfo` bulunulan chunk'ın şehrini gösterir.
- Vatandaşların şehir arazilerine erişimi güncel üyelik üzerinden OPAC'ye bağlandı. Mevcut kişisel party üyelikleri korunur.
- Varsayılan şehir kotası 16 chunk'tır. OPAC'nin oyuncu izinleri, kişisel kapasitesi ve alt profil sınırı ayrıca uygulanır.
- Aktif şehir claim'leri başkan devrini durdurur. Şehir dağıtılırken yalnızca sahibi ve alt profili doğrulanmış şehir arazileri bırakılır.
- Şehir–claim bağlantıları dünya kaydında boyuta göre tutulur. Eski dünya kayıtları otomatik okunur. OPAC ile uyuşmayan bağlantılar başlangıçta ve işlemler sırasında temizlenir.

## 0.1.0 · İlk prototip

- Şehirler, devletler, vatandaşlık, davetler, belediye başkanlığı devri ve kalıcı dünya kayıtları.
- Salt okunur Java API, Türkçe ve İngilizce komut mesajları ve OPAC sahipliğini gösteren `/town claimstatus`.

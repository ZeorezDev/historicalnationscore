# Sürüm geçmişi

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

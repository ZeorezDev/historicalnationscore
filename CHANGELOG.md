# Sürüm geçmişi

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

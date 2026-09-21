package android.content

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class Context {
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()

    private val notificationManager by lazy { NotificationManager() }
    private val accountManager by lazy { AccountManager.get(this) }
    private val appOpsManager by lazy { AppOpsManager() }
    private val audioManager by lazy { android.media.AudioManager() }
    private val _contentResolver by lazy { ContentResolver() }
    open val contentResolver: ContentResolver get() = _contentResolver
    private val _packageManager by lazy { PackageManager() }
    open val packageManager: PackageManager get() = _packageManager
    private val _resources by lazy { android.content.res.Resources(this) }
    open val resources: android.content.res.Resources get() = _resources
    private val _assets by lazy { android.content.res.AssetManager() }
    open fun getAssets(): android.content.res.AssetManager = _assets
    @get:JvmName("assetsProperty")
    val assets: android.content.res.AssetManager
        get() = getAssets()

    open fun getFilesDir(): File {
        return PlatformPaths.dataDir.toFile().apply { mkdirs() }
    }

    open fun getCacheDir(): File {
        return PlatformPaths.cacheDir.toFile().apply { mkdirs() }
    }

    @get:JvmName("filesDirProperty")
    val filesDir: File
        get() = getFilesDir()

    @get:JvmName("cacheDirProperty")
    val cacheDir: File
        get() = getCacheDir()

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return preferences.computeIfAbsent(name) { LinuxSharedPreferences(it) }
    }

    open fun getPackageName(): String = "com.lagradost.cloudstream3"

    @get:JvmName("packageNameProperty")
    val packageName: String
        get() = getPackageName()

    open fun getApplicationContext(): Context = this

    @get:JvmName("applicationContextProperty")
    val applicationContext: Context
        get() = getApplicationContext()

    open fun getDir(name: String, mode: Int): File {
        return File(filesDir, name).apply { mkdirs() }
    }

    open fun checkSelfPermission(permission: String): Int = 0

    open fun getString(resId: Int): String = when (resId) {
        101 -> if (java.util.Locale.getDefault().language == "tr") "İzleniyor" else "Watching"
        102 -> if (java.util.Locale.getDefault().language == "tr") "Tamamlandı" else "Completed"
        103 -> if (java.util.Locale.getDefault().language == "tr") "Beklemede" else "On-Hold"
        104 -> if (java.util.Locale.getDefault().language == "tr") "Bırakıldı" else "Dropped"
        105 -> if (java.util.Locale.getDefault().language == "tr") "Planlandı" else "Plan to Watch"
        106 -> "@string/none"
        107 -> if (java.util.Locale.getDefault().language == "tr") "Yeniden izleniyor" else "Rewatching"
        108 -> if (java.util.Locale.getDefault().language == "tr") "%s olarak giriş yapıldı" else "Logged in as %s"
        109 -> if (java.util.Locale.getDefault().language == "tr") "Varsayılan" else "Default"
        110 -> if (java.util.Locale.getDefault().language == "tr") "Alfabetik (A'dan Z’ye)" else "Alphabetical (A to Z)"
        111 -> if (java.util.Locale.getDefault().language == "tr") "Alfabetik (Z - A)" else "Alphabetical (Z to A)"
        112 -> if (java.util.Locale.getDefault().language == "tr") "Güncellenme (Yeniden Eskiye)" else "Updated (New to Old)"
        113 -> if (java.util.Locale.getDefault().language == "tr") "Güncellenme (Eskiden Yeniye)" else "Updated (Old to New)"
        114 -> "sort_added_new"
        115 -> "sort_added_old"
        116 -> if (java.util.Locale.getDefault().language == "tr") "Derecelendirme (Yüksekten Düşüğe)" else "Rating (High to Low)"
        117 -> if (java.util.Locale.getDefault().language == "tr") "Derecelendirme (Düşükten Yükseğe)" else "Rating (Low to High)"
        118 -> if (java.util.Locale.getDefault().language == "tr") "Yayınlanma Tarihi (Yeniden Eskiye)" else "Release Date (New to Old)"
        119 -> if (java.util.Locale.getDefault().language == "tr") "Yayınlanma Tarihi (Eskiden Yeniye)" else "Release Date (Old to New)"
        120 -> if (java.util.Locale.getDefault().language == "tr") "İzlendi olarak işaretle" else "Mark as watched"
        121 -> if (java.util.Locale.getDefault().language == "tr") "Bu bölüme kadar izlenmiş olarak işaretle" else "Mark as watched up to this episode"
        122 -> if (java.util.Locale.getDefault().language == "tr") "İzlenenlerden kaldır" else "Remove from watched"
        123 -> if (java.util.Locale.getDefault().language == "tr") "Bu bölümü izlenmemiş olarak işaretle" else "Remove watched up to this episode"
        124 -> "Anime"
        125 -> if (java.util.Locale.getDefault().language == "tr") "Dublajlı" else "Dub"
        126 -> if (java.util.Locale.getDefault().language == "tr") "Altyazılı" else "Sub"
        127 -> if (java.util.Locale.getDefault().language == "tr") "Asya dizisi" else "Asian Drama"
        128 -> if (java.util.Locale.getDefault().language == "tr") "Sesli Kitap" else "Audio Book"
        129 -> if (java.util.Locale.getDefault().language == "tr") "Ses" else "Audio"
        130 -> if (java.util.Locale.getDefault().language == "tr") "Çizgi film" else "Cartoon"
        131 -> if (java.util.Locale.getDefault().language == "tr") "Oyuncular: %s" else "Cast: %s"
        132 -> if (java.util.Locale.getDefault().language == "tr") "Medya" else "Media"
        133 -> if (java.util.Locale.getDefault().language == "tr") "Belgesel" else "Documentary"
        134 -> if (java.util.Locale.getDefault().language == "tr") "İndirme Başladı" else "Download Started"
        135 -> if (java.util.Locale.getDefault().language == "tr") "Ekle" else "Add"
        136 -> if (java.util.Locale.getDefault().language == "tr") "İptal" else "Cancel"
        137 -> if (java.util.Locale.getDefault().language == "tr") "Kütüphanenizde potensiyel kopya ürünler bulundu: \n \n%s \n \nYine de ekleyerek var olanları değiştirmek mi istersiniz, yoksa iptal etmek mi?" else "Potential duplicate items have been found in your library: \n\n%s \n\nWould you like to add this item anyway, replace the existing ones, or cancel the action?"
        138 -> if (java.util.Locale.getDefault().language == "tr") "Görünüşe göre potansiyel bir kopya kütüphanenizde zaten bulunuyor: '%s' \n \nYine de ekleyerek var olanı değiştirmek mi istersiniz, yoksa iptal etmek mi?" else "It appears that a potentially duplicate item already exists in your library: '%s.' \n\nWould you like to add this item anyway, replace the existing one, or cancel the action?"
        139 -> if (java.util.Locale.getDefault().language == "tr") "Değiştir" else "Replace"
        140 -> if (java.util.Locale.getDefault().language == "tr") "Tümünü Değiştir" else "Replace All"
        141 -> if (java.util.Locale.getDefault().language == "tr") "Potensiyel Kopya Bulundu" else "Potential Duplicate Found"
        142 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm" else "Episode"
        143 -> if (java.util.Locale.getDefault().language == "tr") "Otomatik indir" else "Auto download"
        144 -> if (java.util.Locale.getDefault().language == "tr") "Bölümü Chromecast ile yayınla" else "Chromecast episode"
        145 -> if (java.util.Locale.getDefault().language == "tr") "Bağlantıyı Chromecast ile yayınla" else "Chromecast mirror"
        146 -> if (java.util.Locale.getDefault().language == "tr") "Şu kaynaktan indir" else "Download mirror"
        147 -> if (java.util.Locale.getDefault().language == "tr") "Altyazıları indir" else "Download subtitles"
        148 -> if (java.util.Locale.getDefault().language == "tr") "Burada oynat" else "Play in app"
        149 -> if (java.util.Locale.getDefault().language == "tr") "Bağlantıları yenile" else "Reload links"
        150 -> "%1\$d %2\$s"
        151 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm" else ""
        152 -> "%1\$d-%2\$d"
        153 -> if (java.util.Locale.getDefault().language == "tr") "Favoriler" else "Favorites"
        154 -> if (java.util.Locale.getDefault().language == "tr") "Bağlantılar Yeniden Yüklendi" else "Links Reloaded"
        155 -> if (java.util.Locale.getDefault().language == "tr") "Canlı yayın" else "Livestream"
        156 -> if (java.util.Locale.getDefault().language == "tr") "Film" else "Movie"
        157 -> if (java.util.Locale.getDefault().language == "tr") "Müzik" else "Music"
        158 -> if (java.util.Locale.getDefault().language == "tr") "%d. Bölüm şu tarihte yayınlanacak" else "Episode %d will be released in"
        159 -> if (java.util.Locale.getDefault().language == "tr") "%1\$dg %2\$dsa %3\$ddk" else "%1\$dd %2\$dh %3\$dm"
        160 -> if (java.util.Locale.getDefault().language == "tr") "%1\$dsa %2\$ddk" else "%1\$dh %2\$dm"
        161 -> if (java.util.Locale.getDefault().language == "tr") "%ddk" else "%dm"
        162 -> if (java.util.Locale.getDefault().language == "tr") "%1\$d. Sezon %2\$d. Bölüm şu tarihte yayınlanacak" else "Season %1\$d Episode %2\$d will be released in"
        163 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm bulunamadı" else "No Episodes found"
        164 -> if (java.util.Locale.getDefault().language == "tr") "Sezon yok" else "No Season"
        165 -> if (java.util.Locale.getDefault().language == "tr") "Konu Bulunamadı" else "No Plot Found"
        166 -> if (java.util.Locale.getDefault().language == "tr") "+18" else "NSFW"
        167 -> "Video"
        168 -> "OVA"
        169 -> if (java.util.Locale.getDefault().language == "tr") "Bölümü oynat" else "Play Episode"
        170 -> if (java.util.Locale.getDefault().language == "tr") "Tüm Seriyi Oynat" else "Play Full Series"
        171 -> if (java.util.Locale.getDefault().language == "tr") "Canlı Yayını Oynat" else "Play Livestream"
        172 -> if (java.util.Locale.getDefault().language == "tr") "Filmi Oynat" else "Play Movie"
        173 -> if (java.util.Locale.getDefault().language == "tr") "Torrent Oynat" else "Stream Torrent"
        174 -> if (java.util.Locale.getDefault().language == "tr") "Tercih edilen video oynatıcısı" else "Preferred video player"
        175 -> "Podcast"
        176 -> if (java.util.Locale.getDefault().language == "tr") "Metadata site tarafından sağlanmamış, veri site'de bulunmuyorsa video yüklenmesi başarısız olacak." else "Metadata is not provided by site, video loading will fail if it does not exist on site."
        177 -> "%s/10.0"
        178 -> if (java.util.Locale.getDefault().language == "tr") "Özet" else "Synopsis"
        179 -> if (java.util.Locale.getDefault().language == "tr") "%s \nkaldı" else "%s\nremaining"
        180 -> if (java.util.Locale.getDefault().language == "tr") "Sezon" else "Season"
        181 -> "%1\$s %2\$d%3\$s"
        182 -> if (java.util.Locale.getDefault().language == "tr") "Tarih %s" else "Date %s"
        183 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm %s" else "Ep %s"
        184 -> if (java.util.Locale.getDefault().language == "tr") "Puanlama %s" else "Rating %s"
        185 -> if (java.util.Locale.getDefault().language == "tr") "Yayınlanma Tarihi (En Yeni)" else "Air Date (Newest)"
        186 -> if (java.util.Locale.getDefault().language == "tr") "Yayınlanma Tarihi (En Eski)" else "Air Date (Oldest)"
        187 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm (Artan)" else "Episode (Ascending)"
        188 -> if (java.util.Locale.getDefault().language == "tr") "Bölüm (Azalan)" else "Episode (Descending)"
        189 -> if (java.util.Locale.getDefault().language == "tr") "Puanlama (En Yüksek)" else "Rating (Highest)"
        190 -> if (java.util.Locale.getDefault().language == "tr") "Puanlama (En Düşük)" else "Rating (Lowest)"
        191 -> if (java.util.Locale.getDefault().language == "tr") "Tamamlandı" else "Completed"
        192 -> if (java.util.Locale.getDefault().language == "tr") "Devam ediyor" else "Ongoing"
        193 -> if (java.util.Locale.getDefault().language == "tr") "Abone olunan" else "Subscribed"
        194 -> if (java.util.Locale.getDefault().language == "tr") "Açıklama Bulunamadı" else "No Description Found"
        195 -> if (java.util.Locale.getDefault().language == "tr") "Açıklama" else "Description"
        196 -> "Torrent"
        197 -> if (java.util.Locale.getDefault().language == "tr") "Dizi" else "Series"
        198 -> "Video"
        199 -> if (java.util.Locale.getDefault().language == "tr") "Bu sağlayıcının düzgün çalışması için bir VPN gerekebilir" else "A VPN might be needed for this provider to work correctly"
        200 -> if (java.util.Locale.getDefault().language == "tr") "Bu sağlayıcı torrent kullanıyor, bir VPN önerilir" else "This provider is a torrent, a VPN is recommended"
        201 -> "S"
        202 -> if (java.util.Locale.getDefault().language == "tr") "B" else "E"
        203 -> if (java.util.Locale.getDefault().language == "tr") "Her zaman sor" else "Always ask"
        204 -> if (java.util.Locale.getDefault().language == "tr") "Her zaman sor" else "Always ask"
        205 -> "player_pref_key"
        206 -> "display_sub_key"
        207 -> if (java.util.Locale.getDefault().language == "tr") "Uygulama bulunamadı" else "App not found"
        208 -> if (java.util.Locale.getDefault().language == "tr") "Açılış" else "Opening"
        209 -> if (java.util.Locale.getDefault().language == "tr") "Bitiş" else "Ending"
        210 -> if (java.util.Locale.getDefault().language == "tr") "Özet" else "Recap"
        211 -> if (java.util.Locale.getDefault().language == "tr") "Karışık başlangıç" else "Mixed opening"
        212 -> if (java.util.Locale.getDefault().language == "tr") "Karışık son" else "Mixed ending"
        213 -> if (java.util.Locale.getDefault().language == "tr") "Katkıda Bulunanlar" else "Credits"
        214 -> if (java.util.Locale.getDefault().language == "tr") "Giriş" else "Intro"
        215 -> if (java.util.Locale.getDefault().language == "tr") "Ön Gösterim" else "Preview"
        216 -> if (java.util.Locale.getDefault().language == "tr") "Geç %s" else "Skip %s"
        217 -> if (java.util.Locale.getDefault().language == "tr") "Sonraki bölüm" else "Next episode"
        218 -> "episode_sync_enabled_key"
        219 -> if (java.util.Locale.getDefault().language == "tr") "Yok" else "None"
        220 -> "Wi-Fi"
        221 -> if (java.util.Locale.getDefault().language == "tr") "Mobil veri" else "Mobile data"
        222 -> if (java.util.Locale.getDefault().language == "tr") "İndir" else "Download"
        223 -> if (java.util.Locale.getDefault().language == "tr") "Profil %d" else "Profile %d"
        224 -> if (java.util.Locale.getDefault().language == "tr") "Bağlantı bulunamadı" else "No Links Found"
        225 -> "enable_skip_op_from_database"
        226 -> "jsdelivr_proxy_key"
        227 -> if (java.util.Locale.getDefault().language == "tr") "Önce eklentiyi yükleyin" else "Install the extension first"
        228 -> if (java.util.Locale.getDefault().language == "tr") "Zaten oyladınız" else "You have already voted"
        237 -> if (java.util.Locale.getDefault().language == "tr") "Sil" else "Delete"
        238 -> if (java.util.Locale.getDefault().language == "tr") "Dosyayı sil" else "Delete File"
        239 -> if (java.util.Locale.getDefault().language == "tr") "Dosyaları Silin" else "Delete Files"
        240 -> if (java.util.Locale.getDefault().language == "tr") "Sil (%1\$d | %2\$s)" else "Delete (%1\$d | %2\$s)"
        250 -> if (java.util.Locale.getDefault().language == "tr") "%s tamamen silinecek \nEmin misiniz?" else "This will permanently delete %s\nAre you sure?"
        251 -> if (java.util.Locale.getDefault().language == "tr") "Aşağıdaki öğeleri kalıcı olarak silmek istediğinizden emin misiniz? \n \n%s" else "Are you sure you want to permanently delete the following items?\n\n%s"
        252 -> if (java.util.Locale.getDefault().language == "tr") "%1\$s içindeki aşağıdaki bölümleri kalıcı olarak silmek istediğinizden emin misiniz? \n \n%2\$s" else "Are you sure you want to permanently delete the following episodes in %1\$s?\n\n%2\$s"
        253 -> if (java.util.Locale.getDefault().language == "tr") "Ayrıca aşağıdaki dizideki tüm bölümleri kalıcı olarak sileceksiniz: \n \n%s" else "You will also permanently delete all episodes in the following series:\n\n%s"
        254 -> if (java.util.Locale.getDefault().language == "tr") "Aşağıdaki dizideki tüm bölümleri kalıcı olarak silmek istediğinizden emin misiniz? \n \n%s" else "Are you sure you want to permanently delete all episodes in the following series?\n\n%s"
        255 -> if (java.util.Locale.getDefault().language == "tr") "Yedek" else "Backup"
        256 -> if (java.util.Locale.getDefault().language == "tr") "Başarıyla yedeklendi" else "Data stored"
        257 -> if (java.util.Locale.getDefault().language == "tr") "Depolama izinleri eksik. Lütfen tekrar deneyin." else "Storage permissions missing. Please try again."
        258 -> if (java.util.Locale.getDefault().language == "tr") "Abone olunan gösteriler güncelleniyor" else "Updating subscribed shows"
        259 -> if (java.util.Locale.getDefault().language == "tr") "Yeni bölüm %d yayınlandı!" else "Episode %d released!"
        260 -> "subscription_channel_name"
        261 -> "subscription_channel_description"
        262 -> if (java.util.Locale.getDefault().language == "tr") "%s yüklenemedi" else "Could not load %s"
        263 -> if (java.util.Locale.getDefault().language == "tr") "%d eklentiler güncellendi" else "Updated %d plugins"
        264 -> if (java.util.Locale.getDefault().language == "tr") "İndirilen: %d" else "Downloaded: %d"
        265 -> if (java.util.Locale.getDefault().language == "tr") "Eklenti güncellemesi başlıyor!" else "Starting plugin update process!"
        266 -> if (java.util.Locale.getDefault().language == "tr") "%d eklenti başarıyla güncellendi" else "Successfully updated %d plugin(s)"
        267 -> if (java.util.Locale.getDefault().language == "tr") "Hiçbir eklenti güncellenmedi." else "No plugins were updated."
        268 -> "channel_name"
        269 -> "channel_description"
        270 -> "provider_lang_key"
        271 -> if (java.util.Locale.getDefault().language == "tr") "Tüm Diller" else "All Languages"
        272 -> "search_type_list"
        273 -> "show_player_metadata_key"
        274 -> "show_trailers_key"
        275 -> "show_kitsu_posters_key"
        276 -> "app_layout_key"
        277 -> "prefer_media_type_key_2"
        278 -> "pref_filter_search_quality_key"
        279 -> if (java.util.Locale.getDefault().language == "tr") "%s eklendi" else "Loaded %s"
        280 -> if (java.util.Locale.getDefault().language == "tr") "Uyarı: CloudStream 3.taraf uzantıların kullanımı için herhangi bir sorumluluk kabul etmez ve bunlar için herhangi bir destek sağlamaz!" else "Warning: CloudStream does not take any responsibility for using third-party extensions and does not provide any support for them!"
        281 -> if (java.util.Locale.getDefault().language == "tr") "Depoyu aç" else "Open repository"
        282 -> if (java.util.Locale.getDefault().language == "tr") "Yoksay" else "Dismiss"
        283 -> "dns_key"
        284 -> "preview_seekbar_key"
        285 -> "@string/action_default"
        286 -> if (java.util.Locale.getDefault().language == "tr") "İndirilen dosya" else "Downloaded file"
        287 -> if (java.util.Locale.getDefault().language == "tr") "Depoda eklenti bulunamadı" else "No plugins found in repository"
        288 -> if (java.util.Locale.getDefault().language == "tr") "%s'nin tamamı zaten indirildi" else "All %s already downloaded"
        289 -> if (java.util.Locale.getDefault().language == "tr") "eklentiler" else "plugins"
        290 -> if (java.util.Locale.getDefault().language == "tr") "%1\$d %2\$s indirilmeye başlandı…" else "Started downloading %1\$d %2\$s…"
        291 -> if (java.util.Locale.getDefault().language == "tr") "eklenti" else "plugin"
        292 -> if (java.util.Locale.getDefault().language == "tr") "%1\$d %2\$s indirildi" else "Downloaded %1\$d %2\$s"
        293 -> if (java.util.Locale.getDefault().language == "tr") "Eklenti Silindi" else "Plugin Deleted"
        294 -> if (java.util.Locale.getDefault().language == "tr") "Eklenti Yüklendi" else "Plugin Loaded"
        295 -> if (java.util.Locale.getDefault().language == "tr") "Eklenti İndirildi" else "Plugin Downloaded"
        296 -> if (java.util.Locale.getDefault().language == "tr") "Hata" else "Error"
        297 -> "app_locale"
        298 -> "app_theme_key"
        299 -> "primary_color_key"
        301 -> if (java.util.Locale.getDefault().language == "tr") "Kaynak hatası" else "Source error"
        302 -> if (java.util.Locale.getDefault().language == "tr") "Altyazı yok" else "No Subtitles"
        303 -> if (java.util.Locale.getDefault().language == "tr") "Devre dışı: %d" else "Disabled: %d"
        304 -> if (java.util.Locale.getDefault().language == "tr") "İndirilmeyen: %d" else "Not downloaded: %d"
        349 -> "download_path_key_visual"
        350 -> "download_parallel_key"
        351 -> "download_concurrent_key"
        352 -> "download_path_key"
        353 -> "%1\$s - %2\$s"
        354 -> if (java.util.Locale.getDefault().language == "tr") "İndirme Tamamlandı" else "Download Done"
        355 -> if (java.util.Locale.getDefault().language == "tr") "İndirme İptal Edildi" else "Download Canceled"
        356 -> if (java.util.Locale.getDefault().language == "tr") "İndirme Başarısız" else "Download Failed"
        357 -> if (java.util.Locale.getDefault().language == "tr") "Sürdür" else "Resume"
        358 -> if (java.util.Locale.getDefault().language == "tr") "Duraklat" else "Pause"
        359 -> if (java.util.Locale.getDefault().language == "tr") "İptal" else "Cancel"
        360 -> if (java.util.Locale.getDefault().language == "tr") "Yükleniyor…" else "Loading…"
        361 -> if (java.util.Locale.getDefault().language == "tr") "%1\$dsa %2\$ddk %3\$dsn" else "%1\$dh %2\$dm %3\$ds"
        362 -> if (java.util.Locale.getDefault().language == "tr") "%1\$ddk %2\$dsn" else "%1\$dm %2\$ds"
        363 -> if (java.util.Locale.getDefault().language == "tr") "%1\$dsn" else "%1\$ds"
        487 -> if (java.util.Locale.getDefault().language == "tr") "Geçersiz ID" else "Invalid ID"
        1091 -> if (java.util.Locale.getDefault().language == "tr") "%s ile giriş yapılamadı" else "Could not log in at %s"
        14801 -> if (java.util.Locale.getDefault().language == "tr") "%s üzerinden oynat" else "Play in %s"
        14802 -> if (java.util.Locale.getDefault().language == "tr") "Şu kaynaktan oynat" else "Play mirror"
        364 -> "pip_enabled_key"
        365 -> "30"
        366 -> "30"
        370 -> if (java.util.Locale.getDefault().language == "tr") "Yedekleme başarısız oldu: %s" else "Backup failed: %s"
        371 -> if (java.util.Locale.getDefault().language == "tr") "Geri yükleme başarısız oldu: %s" else "Restore failed: %s"
        372 -> "backup_path_key"
        520 -> if (java.util.Locale.getDefault().language == "tr") "Ön sürüm zaten yüklü" else "Pre-release version is already installed"
        521 -> if (java.util.Locale.getDefault().language == "tr") "Ön sürüm yüklemesi başarısız oldu" else "Failed to install pre-release"
        522 -> "auto_update"
        523 -> "skip_update"
        524 -> if (java.util.Locale.getDefault().language == "tr") "Yeni güncelleme: %s -> %s" else "New update: %s -> %s"
        525 -> if (java.util.Locale.getDefault().language == "tr") "Güncelle" else "Update"
        526 -> "apk_installer"
        527 -> if (java.util.Locale.getDefault().language == "tr") "Güncellemeyi Atla" else "Skip update"
        528 -> if (java.util.Locale.getDefault().language == "tr") "Güncelleme daha sonra uygulanacak" else "Update will be installed later"
        529 -> if (java.util.Locale.getDefault().language == "tr") "Güncelleme yükleniyor" else "Installing update"
        530 -> if (java.util.Locale.getDefault().language == "tr") "Güncelleme indiriliyor" else "Downloading update"
        531 -> if (java.util.Locale.getDefault().language == "tr") "Güncelleme başarısız oldu" else "Update failed"
        574 -> if (java.util.Locale.getDefault().language == "tr") "kopyalandı!" else "copied!"
        575 -> if (java.util.Locale.getDefault().language == "tr") "Çok fazla metin. Panoya kaydedilemiyor." else "Too much text. Unable to save to clipboard."
        576 -> if (java.util.Locale.getDefault().language == "tr") "Panoya erişirken hata oluştu, lütfen tekrar deneyin." else "Error accessing Clipboard, Please try again."
        577 -> if (java.util.Locale.getDefault().language == "tr") "Kopyalama hatası, lütfen logcat'i kopyalayıp uygulama desteğiyle iletişime geçin." else "Error copying, Please copy logcat and contact app support."
        578 -> "bottom_title_key"
        601 -> if (java.util.Locale.getDefault().language == "tr") "Varsayılan" else "Default"
        602 -> if (java.util.Locale.getDefault().language == "tr") "Tarayıcıda Aç" else "Browser"
        603 -> if (java.util.Locale.getDefault().language == "tr") "Arama Yap" else "Search"
        4500 -> if (java.util.Locale.getDefault().language == "tr") "Cast Cihazı Seç" else "Select Cast Device"
        else -> "res_$resId"
    }

    open fun getString(resId: Int, vararg formatArgs: Any?): String {
        return String.format(java.util.Locale.US, getString(resId), *formatArgs)
    }

    open fun getSystemService(name: String): Any? {
        return when (name) {
            AUDIO_SERVICE -> audioManager
            NOTIFICATION_SERVICE -> notificationManager
            ACCOUNT_SERVICE -> accountManager
            APP_OPS_SERVICE -> appOpsManager
            else -> null
        }
    }

    open fun startActivity(intent: Intent) {}

    companion object {
        const val MODE_PRIVATE: Int = 0
        const val MODE_WORLD_READABLE: Int = 1
        const val MODE_WORLD_WRITEABLE: Int = 2
        const val MODE_MULTI_PROCESS: Int = 4

        const val AUDIO_SERVICE = "audio"
        const val NOTIFICATION_SERVICE = "notification"
        const val ACCOUNT_SERVICE = "account"
        const val APP_OPS_SERVICE = "appops"
    }
}


// Extension aliases kept for source compatibility with ported upstream code
fun Context.getPackageName(): String = packageName
fun Context.getApplicationContext(): Context = applicationContext

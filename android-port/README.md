# UV Studio pentru Android (nativ)

Aplicație Android pentru radiourile **UV-K1 / UV-K5 V3** (firmware F4HWN), conectate
prin cablu **USB-C OTG**. Toate uneltele sunt **ecrane Android native**: nu există
WebView, nu se încarcă niciun site, iar protocolul radio este implementat direct în
Java, peste USB Host (CDC-ACM).

**Autor:** Claudiu Panga (aka. Smart ReFleX) — https://github.com/SmartReFleX

- Fișier instalabil: **`android-port/apk/UV-Studio-Mobile-1.0.0.apk`** (~115 KB)
- Android 7.0+ (minSdk 24), targetSdk 34
- Funcționează offline; internet doar pentru cataloagele online

## Ce s-a schimbat față de 1.0

Versiunea 1.0 deschidea site-ul UV Studio într-un `WebView` și injecta un polyfill
`navigator.serial`. Versiunea 2.0 nu mai are WebView deloc:

| 1.0 | 2.0+ |
| --- | --- |
| card → site în WebView | card → ecran Android nativ |
| protocol doar în JavaScript | protocol portat în Java (`UvProtocol`, `Radio`) |
| `shim.js` + `mobile.css` injectate | eliminate |
| necesita internet la fiecare pornire | funcționează offline |
| „click" în pagină pe `[data-route]` | navigare nativă cu stivă de ecrane |

## Ecrane

| Ecran | Ce face |
| --- | --- |
| **USB devices** | diagnostic: ce dispozitive vede Android pe USB (VID:PID, interfețe, permisiune) |
| **Device info** | citește `0x0514`, arată firmware-ul, offset-ul de calibrare, suportul pentru flash extern și aplicații |
| **Live view** | ecran 128×64 desenat nativ pe `Canvas`, tastatură completă (K1/K5), LED-uri, deep sleep, restart |
| **Flash firmware** | scrie imaginea în modul bootloader (DFU), apoi activează pachetul de assets; fișier local `.bin` |
| **Install applications** | aplicații overlay: listă, instalare locală sau **din catalogul oficial + comunitate**, ștergere, renumerotare |
| **Calibration** | backup / restore 512 B (`calibration.dat`) |
| **Boot logo** | citire logo → PNG, încărcare imagine cu prag + inversare |
| **RF log** | flux live, tabel, export CSV identic cu clientul web |
| **External flash** | dump 2 MiB (`external-flash.bin`) și restore pe sectoare |

> Ecranul **Factory reset** este ascuns la cerere: codul rămâne în surse
> (`FactoryResetScreen.java`), dar nu mai există card și nu este accesibil din UI.

## Catalog online

Aplicația poate lua aplicațiile overlay direct de pe GitHub, ca clientul web:

- **aplicații:** `.../contents/archive/apps/v<versiune firmware>` — dacă folderul
  exact nu există, se folosește cel mai nou folder publicat (compatibilitatea este
  verificată oricum de ABI-ul FAP1 la instalare);
- **comunitate:** `raw.githubusercontent.com/armel/fusion-community/main/apps.json`
  — aplicațiile publicate de comunitate, fiecare verificată SHA-256 înainte de
  instalare.

Butonul **Install from catalog…** din ecranul de aplicații listează ambele surse
într-o singură listă (cele din comunitate sunt marcate „community”) și le
instalează în primul slot liber.

Limite: GitHub API fără token permite 60 cereri/oră; la depășire apare
„rate limit — try again later”. Descărcarea propriu-zisă nu consumă din această cotă.

## Cum funcționează

```
Ecran Android  →  Radio (comenzi)  →  RadioLink (pachete)  →  UvProtocol  →  UsbSession  →  USB OTG
                                                                              ↑
                                                        cadre AA 55 (ecran, RF log, taste)
```

Două fluxuri diferite împart același port serial:

1. **Comenzi** — pachete `AB CD … DC BA`, cu lungime, tip, date, CRC-16 (0x1021) și
   o obfuscare XOR cu tabelă fixă de 16 octeți. Răspunsurile radio păstrează CRC
   `FFFF` (placeholder), deci verificarea este tolerantă.
2. **Cadre AA 55** — ecranul radio (`0x01` cadru complet de 1024 B, `0x02` diff în
   bucăți de 9 B), tastele (`0x03`/`0x04`, 4 octeți), RF log (`0x05`/`0x06`).
   Prefixul opțional `0xFF` sau `0xF0|flags` transportă starea LED-urilor și deep sleep.

Firmware-ul trimite cadre doar cât timp primește keepalive la fiecare 200 ms
(`55 AA 00 00`, plus `55 AA 05 03` pentru RF log).

Specificațiile complete, extrase din clientul web de referință, sunt în
`android/_ref/spec/` (`liveview.md`, `flash.md`, `apps-rflog.md`).

### Tabele de adrese folosite

| Operație | Comandă | Bucată | Adresă / dimensiune |
| --- | --- | --- | --- |
| Calibrare | `0x051B` / `0x051D` | 16 B | `0xB000` (v5+) sau `0x1E00`, 512 B |
| Boot logo | `0x051B` / `0x051D` | 16 B | `0xC000`, 1040 B (`F4HWNLGO` + 1024 B bitmap) |
| Firmware (DFU) | `0x0519` | 256 B | pagini, `pageIndex/pageCount` |
| Aplicații | `0x0730…0x0737` | 128 B | cod la `+0x1000`, assets la `+0x100`, header ultima dată |
| Flash extern | `0x0738…0x073F` | 128 B / sector 4 KiB | `0x000000…0x1FFFFF`, calibrarea la `0x010000` nu se atinge |

## Dacă radioul nu este detectat

Deschide **USB devices** în aplicație; ecranul arată exact ce enumeră Android.

| Ce vezi | Cauza | Ce faci |
| --- | --- | --- |
| listă goală | telefonul nu vede niciun dispozitiv | cablu de încărcare, adaptor OTG defect, port USB sau radio oprit |
| un dispozitiv, dar fără „serial” | radioul nu se prezintă ca CDC-ACM | pornește radioul normal (nu în bootloader); verifică firmware-ul |
| „UV radio” / „serial”, fără permisiune | Android nu a acordat accesul | apasă **Request access** |
| „serial” cu alt VID:PID | firmware/variantă diferită | apasă **Open**; aplicația acceptă orice CDC-ACM |

Pe Samsung nu există comutator OTG: host-ul pornește automat. Contează doar
cablu de date + adaptor de calitate. La atașare aplicația afișează un toast cu
numele și VID:PID-ul dispozitivului.

## Instalare

1. Copiază `UV-Studio-Mobile-1.0.0.apk` pe telefon.
2. Deschide fișierul și acceptă „Instalează din surse necunoscute".
3. Conectează radioul cu un cablu **USB-C OTG** (telefonul este host).
4. Pornește aplicația și apasă **Connect**; Android cere permisiunea USB o dată
   pentru fiecare cablu conectat.

Pentru flash firmware, radioul trebuie introdus în **bootloader**:
ții PTT apăsat, pornește radioul (ecranul rămâne negru), apoi conectează cablul.

## Construire

Nu e nevoie de Android Studio, Gradle sau Node.

```powershell
# o singură dată: JDK 17 + Android platform 35 + build-tools 34 (~300 MB)
powershell -NoProfile -ExecutionPolicy Bypass -File .\android\tools\get-toolchain.ps1

# build
powershell -NoProfile -ExecutionPolicy Bypass -File .\android\build.ps1
# sau de la zero:
powershell -NoProfile -ExecutionPolicy Bypass -File .\android\build.ps1 -Clean
```

Pașii din `build.ps1`: `aapt2 compile/link`, `javac` (source 8), `d8`,
`jar uf`, `zipalign`, `apksigner` cu `android/keystore.jks`. Scriptul de iconiță
(`tools/gen_icon.py`) este opțional: dacă lipsește Python, se folosește PNG-ul
deja generat.

Cheia de semnare este generată local la primul build (`CN=UV Studio Android`).
Păstreaz-o: actualizările viitoare trebuie semnate cu aceeași cheie.

## Verificări făcute

Protocolul a fost verificat față de vectori cunoscuți din firmware, cu un test
Java pur (fără Android):

- `create(0x05DD, [])` produce exact `AB CD 04 00 CB 69 14 E6 5B EB DC BA`
  (pachetul de reboot hardcodat în clientul web);
- comanda `0x0519` cu 268 B de date produce un cadru de **280 B**;
- CRC-32 pe `"123456789"` → `CBF43926`;
- cadrele `AA 55` (flags, payload, terminator `0A`) și diff-urile de 9 B se
  decodează corect, iar cadrele parțiale cer mai mulți octeți.

`android-port/apk/UV-Studio-Mobile-1.0.0.apk` este semnat și verificat cu `apksigner verify`.

## Structura

```
android/
  AndroidManifest.xml            manifest (INTERNET + usb.host)
  build.ps1                      build APK fără Gradle
  keystore.jks                   cheia de semnare
  apk/UV-Studio-Mobile-1.0.0.apk          rezultatul
  src/ro/f4hwn/uvstudio/
    MainActivity.java            shell: navigare, USB, permisiuni, fișiere
    Ui.java                      fabrica de widgeturi (tema portocalie, LCD alb)
    ToolScreen.java              schelet comun: progres, consolă, worker
    Worker.java                  thread + progres pe UI
    UsbSession.java              CDC-ACM pe USB Host (bulk endpoints)
    RadioLink.java               parser pachete + cadre, request/response
    UvProtocol.java              framing AB CD, CRC-16, obfuscare
    UvFrames.java                framing AA 55, taste, keepalive, diff
    Radio.java                   toate comenzile (calibrare, apps, DFU…)
    Catalog.java                 catalog aplicații overlay (GitHub API)
    FirmwarePackage.java         split imagine / pachet FWA1
    LogoCodec.java               imagine → 128×64 1bpp și înapoi
    Crc32.java                   CRC-32 zlib
    LiveScreen.java              ecran + tastatură native
    FlashScreen.java             DFU + assets
    AppsScreen.java              app manager + catalog aplicații
    CalibrationScreen.java       backup/restore calibrare
    LogoScreen.java              boot logo
    RfLogScreen.java             RF log + CSV
    ExternalFlashScreen.java     dump/restore 2 MiB
    FactoryResetScreen.java      restore fabrică + firmware stock (ascuns din UI)
    DeviceInfoScreen.java        info + probe de capabilități
    UsbDevicesScreen.java        diagnostic USB (ce vede telefonul)
    AboutScreen.java             despre aplicație
  res/drawable/                  iconițele cardurilor
  _ref/                          sursele web de referință + specificațiile
  test/                          machete HTML (preview, icons)
  tools/get-toolchain.ps1        descarcă toolchain-ul
```

## Limitări

- **Netestat pe hardware real** în afara a ceea ce ai verificat deja: backup
  calibrare și live view. Restul ecranelor folosesc exact aceleași comenzi ca
  clientul web, dar nu au fost rulate pe radio.
- Scrierile (`0x0519`, `0x051D`, `0x0722/24`, `0x073A/3C`, `0x0752/54`) sunt
  ireversibile. Nu există „resume": o deconectare la mijloc înseamnă reluarea de
  la început (firmware) sau sectorul curent (flash extern).
- Catalogul online depinde de GitHub (60 cereri/oră fără token).
- PTT nu poate fi simulat prin serial.

## Legal

Aplicația doar citește/scrie radioul tău prin USB. Folosește-o pe echipamente
proprii. Protocolul, firmware-ul și aplicația web originală aparțin lui F4HWN
(armel.github.io/uvstudio); acesta este un client nativ independent.

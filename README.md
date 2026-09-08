# Hooru

Eine kleine Android-Kamera-App mit Live-Filtern und Presets.

## Installieren

Die aktuelle installierbare Android-APK liegt unter
[`android/hooru-1.0.8-aspect-fix-debug.apk`](android/hooru-1.0.8-aspect-fix-debug.apk).
Die APK auf ein Android-Gerät übertragen, in dessen Dateimanager öffnen und die
Installation aus dieser Quelle erlauben. Alternativ steht dieselbe Datei bei den
GitHub-Releases zum Download bereit.

## Play-Store-Cover-Art

Die fertigen PNG-Dateien liegen unter
[`play-store-assets/brand-2026/`](play-store-assets/brand-2026/): das App-Icon,
die Feature Graphic und sechs Telefon-Screenshots. Sie sind direkt für die
Play Console verwendbar; die HTML-Vorschau ist nur eine zusätzliche
Präsentationshilfe.

## Entwicklung

Das Android-Projekt liegt in `android/`.

Die mitgelieferten Filter-LUTs und Vorschaubilder werden beim Build geprüft. Nach einer Änderung an den XMP-Presets lassen sie sich mit Python 3, NumPy und Pillow neu erzeugen:

```bash
python3 tools/prebake_presets.py
```

Die kleine Blue-Noise-Textur für die GPU-Körnung kann bei Bedarf ebenfalls reproduzierbar neu generiert werden:

```bash
python3 tools/generate_blue_noise.py
```

## Motivverfolgung

Antippen im Sucher fokussiert die Stelle und startet die lokale visuelle
Verfolgung des dortigen Bildausschnitts. Der Rahmen folgt dessen Position;
Fokus und Belichtungsmessung werden nachgeführt, solange das Motiv erkannt wird.
Die Verfolgung verwendet Bilddetails, keine semantische Objektklassifizierung.
Bei Verdeckung, schnellen Bewegungen, starken Formänderungen oder fehlenden
Details kann das Motiv verloren gehen. Erneutes Antippen wählt ein neues Ziel.
Zoom, Kamera-/Moduswechsel und Pausieren der Vorschau beenden die Verfolgung.

Die Verarbeitung erfolgt auf einem eigenen Worker mit maximal zwölf kleinen,
ungefilterten Vorschaubildern pro Sekunde und höchstens einem laufenden Auftrag.
Es werden keine Bilder gespeichert oder übertragen und keine Modelle benötigt.

Deterministische Prüfungen für Bewegung, Belichtungs- und Größenänderung,
Verdeckung, mehrdeutige Muster, erneute Auswahl und Bildränder lassen sich nach
dem ersten Kotlin-Build ohne Emulator ausführen:

```bash
python3 tools/test_subject_tracking.py
```

Auf einem Gerät zusätzlich prüfen: ein strukturiertes Motiv antippen und seitlich
sowie in der Tiefe bewegen, verdecken, erneut auswählen, zoomen, Kamera wechseln
und die App pausieren. Der Rahmen muss der Position und die tatsächliche Schärfe
der Entfernung folgen; verlorene Ziele dürfen nicht selbständig ersetzt werden.

## Play-Store-Build

Für einen signierten Release-Build `android/keystore.properties.example` nach
`android/keystore.properties` kopieren, einen lokalen Keystore hinterlegen und
anschließend `./gradlew :app:bundleRelease` im Ordner `android/` ausführen.

## Android-Qualität: Speicher und Gerätewechsel

Die Release-Konfiguration aktiviert R8 mit Optimierung, Shrinking, Obfuscation und
Resource Shrinking. Die tatsächliche DEX-Optimierungsabdeckung muss nach dem
Upload des Release-AAB in der Play Console geprüft werden; aktiviertes R8 allein
beweist die im Blogpost genannten 25 % nicht.

Filtervorschauen werden nur bei mindestens `STARTED` geladen. Beim Verlassen
dieses Zustands geben die Karten ihre Bitmap-Referenzen frei; der gemeinsame
Vorschau-Cache und das Render-Quellbild werden ebenfalls freigegeben. Beim
Zurückkehren werden die Vorschauen aus Assets bzw. dem Disk-Cache nachgeladen.

Vor Veröffentlichung auf einem Gerät prüfen: Filterbibliothek öffnen, eigene
Presets bearbeiten, Home drücken, wieder öffnen und mehrfach wiederholen.
Dabei Speicher im Vordergrund, nach `onStop` und im gecachten Zustand messen;
auch Fotoverarbeitung und Videoaufnahme auf Geräten mit wenig RAM prüfen.
Android vitals nach RAM-Klasse und Prozesszustand auswerten. Der Quellcode allein
kann die Einhaltung der Speichergrenzen nicht belegen.

Hooru bietet keine Benutzerkonten oder Anmeldung. Die im bereitgestellten
Blogpost beschriebene Zero-Tap-Sign-In-Pflicht für Apps mit Anmeldung ist daher
aktuell nicht anwendbar. Bei Einführung von Konten die Restore Credentials API
berücksichtigen: https://developer.android.com/identity/sign-in/restore-credentials

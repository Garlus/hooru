# Hooru

Eine kleine Android-Kamera-App mit Live-Filtern und Presets.

## Entwicklung

Das Android-Projekt liegt in `android/`. Die Web-Anwendung zum Konvertieren von LUTs liegt in `web-converter/`.

## Play-Store-Build

Für einen signierten Release-Build `android/keystore.properties.example` nach
`android/keystore.properties` kopieren, einen lokalen Keystore hinterlegen und
anschließend `./gradlew :app:bundleRelease` im Ordner `android/` ausführen.

# Hooru · Google Play & Designsystem

Start: `preview.html` im Browser öffnen. `overview.png` zeigt die sechs Store-Motive nebeneinander.

## Upload-Dateien
- `exports/app-icon-512.png`: vorhandenes Hooru-Signet, 512 × 512, RGBA, unter 1 MB.
- `exports/feature-graphic-de.png`: Cover, 1024 × 500, RGB ohne Alpha.
- `phone-screenshots/01-live.png` bis `06-import.png`: sechs Motive, jeweils 1080 × 1920, RGB ohne Alpha.
- `store-listing-de.md`: Name, Kurzbeschreibung, vollständige Beschreibung, Alternativtexte und Console-Angaben.

## Designsystem
`design-system.md` erläutert die Regeln. `exports/design-system.png` ist die visuelle Übersicht. `tokens.json` enthält die maschinenlesbaren Werte. `editable/` enthält editierbare SVG-Quellen mit eingebetteten Screenshots. Zum Öffnen in Affinity die beiliegenden App-Schriften installieren. Affinity selbst wurde nicht verbunden und kein .af-Dateiformat erzeugt.

## Nachvollziehbarkeit
Die Original-Screenshots wurden am 7. September 2026 über ADB vom verbundenen Pixel 9 aufgenommen. Die Kamera zeigte den bereits aktivierten eingebauten Demo-Modus. Die Aufnahmen wurden proportional eingebettet; App-UI und Bildinhalt wurden nicht nachgebaut. Keine Filterwerte oder Kamera-Einstellungen wurden gespeichert. Das Telefon wurde zur Kameraansicht zurückgeführt.

## Erneut erzeugen
1. `node build.cjs` erzeugt SVGs und einen schnellen PNG-Entwurf.
2. `node render.cjs` rendert die endgültigen PNGs mit eingebetteten Schriften in einem isolierten Headless-Browser.
3. `node check.cjs` prüft Formate und erzeugt `overview.png` sowie `validation.json`.

Die Skripte verwenden die in dieser Codex-Sitzung verfügbaren Node-Abhängigkeiten und den installierten Helium-Browser über absolute Pfade. Auf einem anderen Rechner diese Pfade anpassen. Die gelieferten Grafiken lassen sich ohne Skripte direkt verwenden.

## Vor Veröffentlichung ergänzen
Öffentliche Datenschutz-URL, tatsächlicher Entwicklername und Console-Pflichtangaben (Inhaltsbewertung, Datensicherheit, Preis, Verfügbarkeit). Die bestehende Datenschutzdatei liegt im Projekt als `privacy-policy.html`; ihre öffentliche URL ist im Projekt nicht belegt. Die Store-Seite wurde nicht veröffentlicht.

Die drei bereitgestellten Bilder dienten ausschließlich als Stilreferenz; fremde Logos und Textinhalte wurden nicht übernommen.

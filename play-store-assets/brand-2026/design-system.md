# Hooru – Licht wird Look

Dieses System übersetzt die drei bereitgestellten Referenzen in eine eigenständige Hooru-Markenwelt. Übernommen werden Blau, Körnung, Lichtbänder, editoriale Flächen und technische Indizes. Fremde Logos, Namen und Texte werden nicht verwendet. Das vorhandene Hooru-Signet bleibt erhalten.

## Farben
| Token | Wert | Verwendung |
|---|---|---|
| brand.deep | #08285C | Ruhiger Hintergrund, Text auf hellen Flächen |
| brand.film | #176AC6 | Markenflächen, primäre dunklere Blaustufe |
| action.primary | #1C85F3 | Bestehender App-Akzent, aktive Bedienelemente |
| brand.light | #ABCFFF | Sekundäre Markierungen auf tiefem Blau |
| paper | #F6F5EF | Redaktionelle Flächen und helle Schrift |
| surface.base | #05070A | Bestehende App-Oberfläche |
| surface.card | #0A0F15 | Karten |
| surface.raised | #101821 | Erhöhte Flächen, Eingaben |
| border.default | #17202A | Trennlinien |

Kleine Schrift bevorzugt auf Deep Blue oder Paper. Keine kleinen weißen Texte auf hellen Lichtverläufen. Aktive Zustände zusätzlich durch Kontur, Text oder Häkchen unterscheiden. Der hellblaue Akzent ist kein universell geeigneter Hintergrund für kleine weiße Texte.

## Typografie
IBM Plex Sans Regular/Bold für große Aussagen und erklärenden Text. Departure Mono Regular für die Wortmarke, nummerierte Bildfolgen und technische Metadaten. Im App-UI die bestehende Zuordnung zu Departure Mono und Plex Sans beibehalten.

Store-Screenshot: Überschrift 76 px, Zeilenabstand 88 px, Unterzeile 29 px, Marke 36 px, Index 24 px. Feature-Grafik: Headline 78 px, Unterzeile 25 px. Maximal zwei Headline-Zeilen. Texte bleiben in SVG editierbar; Schriften vor Bearbeitung installieren.

## Raster und Formen
8-px-Grundraster. Bei 1080 × 1920 px: 68 px Außenrand; Kopfbereich bis 400 px, Screenshot ab 460 px, Fußzeile bei 1870 px. Echte App-Aufnahmen proportional einpassen, niemals dehnen oder Bedienelemente nachzeichnen. Rechteckige Editorial-Flächen; runde Formen bleiben dem echten App-UI vorbehalten. Treppenflächen dürfen Bildecken akzentuieren, aber keine Schrift oder zentrale UI verdecken.

## Bildsprache
Diagonales, diffuses Licht auf Filmblau; feines monochromes Korn nur als Hintergrundtextur. Fokus-Ecken und Fadenkreuze sparsam einsetzen. Keine Glitch-Effekte über produktrelevanter Schrift. Fotografie darf ihre eigenen Farben behalten. Store-Bilder zeigen echte Aufnahmen der laufenden App; das Kamera-Motiv stammt aus ihrem eingebauten Demo-Modus.

## Komponenten und Zustände
- Primäraktion: blauer oder heller Button auf dunklem Panel; mindestens 48 dp Interaktionsfläche im App-UI.
- Filterkarte: Bildvorschau + Name; Auswahl mit blauer Kontur und Häkchen.
- Einstellungszeile: Label, optionaler Hilfstext, rechts Schalter oder Wert.
- Regler: Label und aktueller Wert über der Spur; aktiver Anteil blau, Rest dunkel.
- Bottom Sheet: dunkle Oberfläche, Griff, deutlicher Titel und sichtbare Abbrechen-Aktion.
- Fokusmarkierung: dünne Ecken; Verlust zusätzlich als Text anzeigen.
- Disabled: reduzierte Betonung ohne Information nur durch Farbe zu vermitteln.
- Tastaturfokus: sichtbare Kontur; Schriftvergrößerung unterstützen, Bewegung respektiert reduzierte Animationen.

Dies ist eine Marken- und Komponenten-Spezifikation, keine Änderung am App-Code. Die vorhandenen UI-Komponenten bleiben die funktionale Quelle.

## Sprache
Direkte Du-Ansprache, konkrete fotografische Begriffe, ein Nutzen pro Bild. „Dein Look. Schon live.“ statt Leistungsversprechen ohne Nachweis. Keine Awards, Fantasiebewertungen oder Downloadzahlen. Geräteabhängige Funktionen in der ausführlichen Beschreibung einordnen.

## Assets & Bearbeitung
`editable/`: SVGs mit editierbaren Texten und eingebetteten Originalaufnahmen.
`exports/`: Feature-Grafik, Icon, Designsystem-Board.
`phone-screenshots/`: sechs fertige 1080 × 1920 px Store-Bilder.
`source-screenshots/`: unveränderte Pixel-9-Aufnahmen.
`fonts/`: die bereits im App-Projekt enthaltenen Schriften.

SVGs können in Affinity geöffnet werden. Ein nativer Affinity-Dateiexport wurde nicht ausgeführt, da die MCP-Verbindung in dieser Sitzung nicht verfügbar ist. SVG-Filter für Korn können je nach Importer anders erscheinen; die PNG-Exporte sind die verbindliche Druck-/Uploadansicht. Vor Schriftweitergabe die jeweils mitgelieferten Lizenzen beachten.

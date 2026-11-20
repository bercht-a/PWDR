# PWDR – Projektplan

PWDR (Kurzform von "Powder") ist der Name der App. Schreibweise immer in Großbuchstaben. Gesprochen wird "Powder": In allen Sprachansagen und Sprachkommandos steht daher "Powder" ausgeschrieben, weil TTS "PWDR" sonst buchstabieren würde.

Android-App, nur Schweiz, privates Projekt, ohne Skipass-Integration.

## 1. Scope

**Tracking**
- GPS-Aufzeichnung im Hintergrund (Foreground Service)
- Automatische Erkennung: Abfahrt / Lift / Pause
- Pro Abfahrt: Speed, Höchstgeschwindigkeit, Beschleunigung, Distanz, Höhenmeter, Dauer, Pistenname
- Pro Liftfahrt: Liftname, Dauer, Höhenmeter
- Tagesstatistik und Verlauf (Skitage, Saison)
- G-Kräfte / Belastung (Beschleunigungssensor)

**Sprachausgabe (Kopfhörer)**
- Speed auf Abruf oder in Intervallen (z. B. "Aktuell 54 km/h") oder bei neuem Höchstwert
- Ansage, wenn sich ein Kollege nähert (z. B. "Marco ist 80 Meter entfernt")

**Karte und Orientierung**
- Pistenplan mit Pisten, Liften, Schwierigkeitsgraden (2D, optional 3D-Terrain)
- Eigene Position und Spur
- Kollegen live auf der Karte
- Webcams (nur Links zur offiziellen Seite pro Gebiet, siehe 5e)

**Wetter und Sicherheit**
- Wetter, Schneehöhe, Lawinenwarnstufe (SLF)
- Community-Meldungen (Pistenzustand, Neuschnee, Warnungen)

**Andrang (nur freiwillige Meldung)**
- Kein Liftstatus, keine Wartezeit-Anzeige. Andrang nur über Community-Meldung "Hier ist viel los" und Webcams (siehe 5)

**Später / optional:** AR-Gipfelfinder, Powder-Alerts

## 2. Tech-Stack

| Bereich | Wahl | Begründung |
|---|---|---|
| App | Kotlin + Jetpack Compose (nativ) | Nur Android. Beste Kontrolle über Hintergrund-GPS, Akku, Sensoren, TTS und Audio-Fokus. |
| Karte | MapLibre Native Android | Offline-fähig, 3D-Terrain möglich |
| Kartendaten | swisstopo (Basiskarte, Open Data), OpenStreetMap (Pisten, Lifte) | OSM-Tags: `piste:type`, `piste:name`, `piste:difficulty`, `aerialway` |
| Lokale DB | Room (SQLite) | Offline-first, Tracks lokal |
| Backend | Supabase (Postgres + PostGIS, Auth, Realtime) | Freunde-Tracking und Meldungen ohne eigenen Server |
| Standort | Fused Location Provider, Activity Recognition | |
| TTS | Android TextToSpeech, Audio-Focus "ducking" | Musik wird leiser statt gestoppt |
| Wetter | Open-Meteo, MeteoSchweiz Open Data | |
| Lawinen, Schnee | SLF Bulletin und IMIS-Stationen | |

## 3. Architektur

- **Module:** `app`, `core:tracking`, `core:map`, `core:social`, `core:voice`, `core:data`
- **Pattern:** MVVM, Kotlin Coroutines/Flow, Hilt
- **Tracking-Service:** Foreground Service mit Wakelock-Strategie. GPS 1 Hz, Sensoren je nach Zustand abgesenkt.
- **Offline-first:** Tracking, Statistik und Karte funktionieren ohne Netz. Tracks und Statistik bleiben in Version 1 nur lokal (kein Sync, siehe 5c). Nur Freunde, Live-Standort und Meldungen brauchen Netz. Kartenkacheln pro Skigebiet vorab ladbar.

### Datenmodell (grob)

```
SkiDay      (id, date, resort_id, stats…)
Run         (id, day_id, type: DOWNHILL|LIFT|PAUSE, start, end,
             dist, vert, max_speed, avg_speed, max_accel,
             piste_name, lift_name)
TrackPoint  (run_id, ts, lat, lon, alt, speed, accel, g_force)
Resort      (id, name, bbox)
Piste / Lift (aus OSM, lokal gecacht)
Friend      (user_id, display_name)
LiveLocation(user_id, lat, lon, ts, share_until)       -- Backend
Report      (id, type, lat, lon, text, ts, votes)      -- Backend
```

## 4. Schlüssel-Algorithmen

**Aktivitätserkennung (Lift / Abfahrt / Pause)**
- Regelbasiert zuerst: Lift = Höhe steigt, Speed ~2–6 m/s, glatter Verlauf. Abfahrt = Höhe sinkt, Speed > ~3 m/s. Pause = Speed ~0 für > 30 s.
- Glättung per Kalman- oder gleitendem Filter, Höhe barometrisch (falls Sensor vorhanden), Hysterese gegen Flackern.
- Zusätzlich Map-Matching gegen OSM-Lifte: Start in der Nähe einer Talstation und Verlauf entlang der Liftlinie ergibt den Liftnamen.

**Pistennamen**
- Map-Matching der Abfahrtspunkte auf die nächste OSM-Piste, **lokal auf dem Gerät** (räumlicher Index, z. B. R-Tree), damit es offline funktioniert. Kein PostGIS. Mehrheitsentscheid über den Verlauf.
- Risiko: OSM-Daten sind unvollständig, nicht jede Piste hat einen Namen. Fallback ist "Unbekannte Piste (blau)".

**Beschleunigung / G-Kräfte**
- Linearbeschleunigung aus GPS-Speed-Ableitung, G-Kräfte aus IMU. Die Handyposition (Tasche vs. Brust) beeinflusst die Werte. Ehrlich als "Richtwert" kennzeichnen.

**Annäherung an Kollegen**
- Distanz zu jedem Freund (Haversine). Schwellen 300 m / 150 m / 50 m (Standard, Details in 5b) mit Hysterese und Cooldown, damit nicht ständig angesagt wird. Nur für Freunde mit aktiver Freigabe.

## 4a. Statistik und Grafiken

Zeiträume: **Tag, Woche, Monat, Saison** (plus "Gesamt"). Umschalter oben (Segmented Control), Pfeile zum Blättern (Vorwoche, Vormonat). Die Darstellung orientiert sich an Apps wie Ski Buddy. Ich habe die Store-Seite nicht geöffnet, die Liste unten ist ein Vorschlag nach üblichen Ski-Tracker-Mustern. Bitte Screenshots der Grafiken schicken, die du besonders magst.

### Kennzahlen (Kacheln)
Skitage, Abfahrten, Liftfahrten, Distanz (Piste), Höhenmeter runter / rauf, Höchstgeschwindigkeit, Durchschnittsspeed, Zeit auf Ski (Abfahrt vs. Lift vs. Pause), max. Beschleunigung, max. G-Kraft, steilste Piste.

### Metrik-Umschalter
Alle Diagramme (Tag, Woche, Monat, Saison) lassen sich auf vier Metriken umschalten: **Höhenmeter, Distanz, Speed, G-Force**. Standard ist Höhenmeter, die zuletzt gewählte Metrik wird gemerkt.

| Metrik | Balken (Woche/Monat) | Tagesverlauf | Kennwerte |
|---|---|---|---|
| Höhenmeter | Summe runter pro Tag | Höhenprofil | Summe, Ø pro Abfahrt, Max. pro Tag |
| Distanz | Summe pro Tag | Kumulierte Distanz | Summe, längste Abfahrt |
| Speed | Vmax pro Tag (Ø als zweite Linie) | Speed-Verlauf | Vmax, Ø, Top-5-Abfahrten |
| G-Force | Max. G pro Tag | G-Verlauf | Max. G, Ø, stärkste Kurve (Ort auf Karte) |

Hinweis: G-Force hängt von der Handyposition ab (Hosentasche vs. Brust). Die Werte gelten als Richtwert und sind nur innerhalb der eigenen Daten gut vergleichbar. In den Einstellungen wählt man die Trageposition, damit Filter und Schwellen passen.

### Tag
- Zusammenfassung mit Kacheln
- **Höhenprofil des Tages** (Zeitachse, Abfahrten farbig, Lifte grau, Pausen schraffiert)
- **Speed-Verlauf** über den Tag, Höchstwert markiert
- **Liste aller Abfahrten und Lifte** als Zeitstrahl: Pistenname, Dauer, Distanz, Vmax, Höhenmeter
- Tipp auf eine Abfahrt: Karte mit der Spur, eingefärbt nach Geschwindigkeit, plus Speed- und Höhenkurve
- Replay der Spur auf der Karte (später)

### Woche / Monat
- **Balkendiagramm** pro Tag: Höhenmeter oder Distanz (Metrik umschaltbar)
- **Kalender-Heatmap** der Skitage (Monat)
- **Trend:** Vmax pro Skitag als Linie
- **Zeitverteilung:** Donut Abfahrt / Lift / Pause
- **Speed-Verteilung:** Histogramm (wie viel Zeit bei 0–20, 20–40 … km/h)
- **Pistenfarben:** Anteil blau / rot / schwarz an der Distanz
- **Top-Listen:** meistgefahrene Pisten, meistgenutzte Lifte, Skigebiete

### Saison / Gesamt
- Summen und persönliche Rekorde (Vmax, längster Skitag, meiste Höhenmeter, längste Abfahrt) mit Datum
- Vergleich mit Vorjahr oder Vorsaison (sobald Daten da sind)
- Karte aller gefahrenen Pisten als Heatmap

### Technik
- **Aggregation:** Per Room-Queries (GROUP BY Tag/Woche/Monat) auf `Run`. Pro Skitag zusätzlich eine vorberechnete Zusammenfassung (`SkiDay`), damit Wochen- und Monatsansichten schnell laden.
- **Charts:** Compose-Bibliothek wie Vico, alternativ eigene Canvas-Charts. Vico deckt Balken, Linien und Flächen ab. Donut und Heatmap würde ich selbst zeichnen.
- **Rohpunkte** (`TrackPoint`) werden für die Graphen auf ca. 1 Punkt alle 5 s ausgedünnt (Douglas-Peucker oder Downsampling), sonst werden Tagesgrafiken träge.
- **Einheiten:** km/h, m, km. Umschaltbar auf mph/ft später, falls nötig.
- **Saison-Definition:** 1. Oktober bis 30. September, damit eine Wintersaison nicht über den Jahreswechsel zerfällt.
- **Export:** GPX pro Tag, später Teilen als Bild (Statistik-Karte für Chats).

### Datenmodell-Ergänzung
```
SkiDay  + total_dist, total_vert_down, total_vert_up, max_speed,
          avg_speed, run_count, lift_count, ski_time, lift_time,
          pause_time, piste_color_dist (JSON)
Record  (type, value, run_id, date)       -- persönliche Rekorde
```

## 5. Andrang und Liftstatus (Recherche, nicht Teil von Version 1)

**Entscheidung (14.11.2026):** Weder Wartezeiten noch Liftstatus sind Teil der App. Die App misst den Andrang nicht selbst und liest keine externen Quellen. Dieser Abschnitt dokumentiert nur die Recherche, damit sie nicht wiederholt werden muss. Was in Version 1 gilt, steht unter "Umsetzung" ganz unten.

### Wie bei einer Stau-Meldung? Google Maps und Alternativen

| Quelle | Eignung | Bewertung |
|---|---|---|
| **Google Maps Verkehrsebene** | Gilt nur für Straßen | Zeigt Stau auf Zufahrten und Parkplätzen (Hinweis "Anreise"), aber keine Lift-Schlangen. |
| **Google "Auslastung" (Popular Times, Live)** | Liegt für manche Orte vor, selten für einzelne Lifte | Es gibt keine offizielle API dafür. Es gibt nur Scraper von Drittanbietern. Sie widersprechen den Nutzungsbedingungen von Google Maps (Scraping ist dort verboten, bitte vor Nutzung die aktuellen Bedingungen prüfen). Dazu ist die Datenqualität für Liftstationen unklar. **Nicht verwenden.** |
| **Outdooractive Open Data Hub / API** | Wirbt mit Live-Daten (Wartezeit am Lift, offen/geschlossen) und "API for free use" | **Geprüft, für PWDR nicht nutzbar** (siehe Ergebnis unten). |
| **Skigebiete selbst** | Manche Bergbahnen zeigen Auslastung, Wartezeiten oder Liftstatus in ihrer App oder Website | Es gibt keine einheitliche Schnittstelle. Für die Testgebiete geprüft, keine offizielle Schnittstelle gefunden (siehe unten). |
| **opendata.swiss, LiftRadar** | Statische bzw. selten aktualisierte Geodaten und Status | Gut für Lifte und Offen/Geschlossen. LiftRadar ist keine Quelle für Wartezeiten. |
| **Webcam-Bildanalyse** | Personen an der Talstation per Bildanalyse zählen | Technisch möglich, aber aufwendig (Rechte an den Bildern, Rechenaufwand, Genauigkeit bei Wetter und Perspektive). Nicht für Version 1. |

Weitere Hinweise zum Ergebnis meiner Recherche: Eine Schweizer Open-Data-Quelle mit Live-Wartezeiten an Liften habe ich nicht gefunden. Die Aussagen zum Outdooractive Hub stammen aus der Werbung des Anbieters. Die Prüfung steht im nächsten Abschnitt.

### Ergebnis der Machbarkeitsprüfung Outdooractive (Stand 14.11.2026)

**Fazit: Für ein privates Projekt nicht nutzbar. Nicht einplanen.**

| Frage | Befund | Quelle |
|---|---|---|
| Gibt es Live-Wartezeiten? | Die Produktseite nennt "Waiting time at the lift", Offen/Geschlossen-Status und "API for free use". Länder, Preise und wer zugreifen darf, stehen dort nicht. | [business.outdooractive.com/mountain](https://business.outdooractive.com/mountain) |
| Ist der Hub öffentlich dokumentiert? | In der Entwickler-Dokumentation habe ich keinen Endpunkt für Lifte, Wartezeiten oder den Open Data Hub gefunden. Das Datenmodell erwähnt Lift-Felder nur für Hütten. Der Hub richtet sich nach Beschreibung an Skigebiete und Unternehmen. | [Entwickler-Doku](https://developers.outdooractive.com/API-Reference/) |
| Wer darf die API nutzen? | Nur Unternehmen: "exclusively entrepreneurs", Verbraucher sind ausgeschlossen (§1 Abs. 3). | [API-Bedingungen](https://corporate.outdooractive.com/en/api-terms) |
| Kosten / Zugang | Eine API-Lizenz muss gekauft werden. Preise stehen nicht dort, Schlüssel gibt es nur auf Anfrage. | [Guidelines](https://developers.outdooractive.com/Overview/Guidelines.html) |
| Nutzung in einer App | Apps müssen vor der Veröffentlichung freigegeben werden (§5). Daten dürfen nicht weitergegeben werden und müssen alle 24 h aktualisiert werden (§7, §8). Eine Quellenangabe ist Pflicht. Testschlüssel gibt es nur für kurzes Ausprobieren. | [API-Bedingungen](https://corporate.outdooractive.com/en/api-terms) |

Wichtig: Eine Backend-Funktion, die die Daten holt und für alle Nutzer zwischenspeichert (so war es in einem früheren Entwurf von 5c geplant), wäre nach diesen Bedingungen wahrscheinlich genau die untersagte Weitergabe. Dazu kommt, dass ein privates Projekt ohne Unternehmen gar kein berechtigter API-Nutzer ist. Rechtlich verbindlich ist das keine Auskunft. Wer es trotzdem will, müsste Outdooractive direkt fragen, ob eine private Nutzung möglich ist.

**Was ich nicht sagen kann:** Ob der Hub Schweizer Gebiete abdeckt und wie aktuell die Wartezeiten sind. Das ließ sich ohne Zugang nicht prüfen.

**Weitere Optionen aus der Recherche (für Liftstatus, nicht für Wartezeiten):**
- [opentransportdata.swiss](https://opendata.swiss/en/perma/seilbahnen-netex@oevch): Seilbahnen als NeTEx, täglich aktualisiert, nur teilweise Abdeckung. Das sind Fahrplan- und Stammdaten, kein Live-Status.
- [swisstopo "Bahnen und Skilifte mit Winterbetrieb"](https://opendata.swiss/en/dataset/bahnen-und-skilifte-mit-winterbetrieb): Geodaten, jährlich aktualisiert.
- Skiresort Service bietet XML-Schnittstellen für Schneeberichte und Skigebietsdaten ([Dokumentation](https://www.skiresort-service.com/fileadmin/user_upload/skiresort-service/content/Skiresort-Dokumentation-XML-Schnittstelle.pdf)). Die Bedingungen und Kosten habe ich nicht geprüft.

**Folgerung für den Plan:**
- Der Andrang an Liften ist in Version 1 **nicht** Teil der App.
- **Liftstatus (offen/geschlossen) ist gestrichen** (Entscheidung 14.11.2026). Für die Testgebiete gibt es keine offizielle Schnittstelle. Es wird nichts von Drittseiten ausgelesen. Wer den Status braucht, schaut auf der Website der Bergbahn nach (Link im Skigebiet-Profil).
- Für Andrang bleibt die freiwillige Meldung "Hier ist viel los" und die Anzeige der Webcams (Menschen schauen selbst auf das Bild).
- Eine weitere Prüfung lohnt nur, wenn die Bergbahn der Testgebiete selbst Wartezeiten veröffentlicht.

### Testgebiete: Lauchernalp und Adelboden-Lenk (Recherche 14.11.2026)

| | Lauchernalp (Lötschental) | Adelboden-Lenk |
|---|---|---|
| Größe | Kleines Gebiet, Zugang per Seilbahn ab Wiler | Großes Gebiet, ca. 37 Anlagen laut [Faktenblatt der Bergbahnen](https://adelboden-lenk.ch/_default_upload_bucket/2024_25_Faktenblatt%20Skibetrieb_2024_25.pdf) |
| Offizielle Liftstatus-Schnittstelle | Keine gefunden. Verweis auf lauchernalp.ch bzw. Telefon der Lauchernalp Bergbahnen AG | Keine gefunden. Es gibt eine [Statusseite der Tourismusorganisation](https://lenk-simmental.ch/en/more/interactive-maps-and-hours-of-operation/status-report-mountain-cable-cars-adelboden-lenk) und PDFs mit Betriebszeiten, aber kein dokumentierter Datenfeed |
| Drittanbieter mit Status | PeakVisor, snow-online (Daten teilweise veraltet) | search.ch (Zähler "Anlagen offen x / 38"), PeakVisor (Status teils "unbekannt") |
| Wartezeiten | Nirgends gefunden | Nirgends gefunden |
| OSM-Abdeckung | **Geprüft (15.11.2026):** 21 Pisten (20 mit Namen, alle mit Schwierigkeitsgrad) und 7 Personenlifte, 5 davon mit Namen (Frühere Abfrage schlug mit 406 fehl, weil der User-Agent fehlte) | Grob geprüft: Abfrage mit weitem Rahmen (inkl. Nachbargebiete) ergab ca. 375 Pisten, davon ca. 226 mit Namen und fast alle mit Schwierigkeitsgrad, dazu ca. 83 Lift-Objekte (Sessel, Gondeln, Schlepplifte, Förderbänder). Zahlen manuell ausgezählt, nicht exakt |

**Folgerungen:**
- **Pistenname:** Bei Adelboden-Lenk tragen etwa 60 % der Pisten einen Namen in OSM. Die Pistenerkennung (Abschnitt 4) funktioniert dort also grösstenteils, ein Rest bleibt "Unbekannte Piste". Lauchernalp ist abgedeckt: 20 von 21 Pisten tragen einen Namen.
- **Liftstatus:** Keine offizielle Schnittstelle. **Entscheidung: weglassen.** Eine spätere Anfrage bei den Bergbahnen bleibt möglich, ist aber nicht Teil des Plans.
- **Andrang:** Bleibt bei freiwilligen Meldungen und Webcams.

### Umsetzung (endgültig für Version 1)
1. Machbarkeitsprüfung Outdooractive: erledigt, negativ.
2. Kein Liftstatus, keine Wartezeiten, keine externe Andrang-Quelle, keine Backend-Funktion dafür.
3. Andrang: freiwillige Community-Meldung "Hier ist viel los" (siehe 5c), mit Ablaufzeit, nicht zu einer Zahl verrechnet. Dazu Webcams.
4. Lifte erscheinen auf der Karte nur als Geodaten (OSM), ohne Statusanzeige. Auf der Karte steht kein Hinweis auf "offen" oder "geschlossen".
5. Quellenangabe (OSM, swisstopo) in der App.

## 5a. UI und Design

**Grundsätze:** modern, flach (keine Schatten, keine Verläufe, Flächen und Linien statt Tiefe), viel Weißraum, große Zahlen, wenig Text. Material 3 mit eigenem Theme, kein Dynamic Color. Sprache nur Deutsch (Strings trotzdem in `strings.xml`).

### Theme
- **Standard: Dunkel.** Zusätzlich ein heller Modus mit hohem Kontrast (Einstellung: Dunkel / Hell / System), weil Dunkel bei Sonne auf dem Schnee schlecht ablesbar sein kann.
- Dunkle Flächen: fast schwarzes Blaugrau, nicht reines Schwarz (besser für Diagramme, schont den Akku auf OLED).

| Rolle | Dunkel | Hell |
|---|---|---|
| Hintergrund | `#0E1317` | `#F6F8FA` |
| Fläche (Karten) | `#171E24` | `#FFFFFF` |
| Fläche erhöht | `#1F2830` | `#E9EEF2` |
| Text primär | `#E8EEF2` | `#10181E` |
| Text sekundär | `#8A9AA6` | `#52626E` |
| Akzent (Eisblau) | `#4CC9F0` | `#0A8FB8` |
| Akzent 2 (Türkis) | `#2EC4B6` | `#13917F` |
| Warnung | `#FFB703` | `#B77800` |
| Fehler / Gefahr | `#FF5A5F` | `#C62F34` |

- **Pistenfarben:** blau `#2F80ED`, rot `#E5383B`, schwarz `#1A1A1A` (im dunklen Modus mit hellem Rand), leicht, falls vorhanden.
- **Metrikfarben (in allen Diagrammen gleich):** Höhenmeter = Eisblau, Distanz = Türkis, Speed = Orange `#FF9F1C`, G-Force = Violett `#9B5DE5`. So erkennt man die Metrik ohne Legende.
- Die Farben werden in der Umsetzung vor dem Bau auf Kontrast geprüft (mind. 4.5:1 für Text).

### Logo und App-Icon (Entwurf v1)
**Umgesetzt (15.11.2026), v2 als Vektor:** Adaptives Icon mit getrenntem Vordergrund und Hintergrund (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`) und einfarbiger Variante für Themed Icons (`ic_launcher_monochrome.xml`). Ein einziges Motiv: Berg mit zwei Schneekappen, eine geschwungene Spur im Pulverschnee und etwas Schneestaub, auf einem Nachthimmel von Blau nach Schwarzblau. Das Motiv liegt in der inneren Sicherheitszone. Statt des Skifahrers in der Welle (v1) gibt es also kein Meereswellen-Motiv mehr. Das Benachrichtigungs-Icon ist dieselbe Silhouette, einfarbig weiß, mit ausgesparter Spur. Alles Vektor, keine Pixelbilder. Der Schriftzug PWDR als eigene Grafik ist noch nicht gebaut, in der App steht der Name als Text. Die Entwurfsnotizen darunter bleiben als Begründung stehen.

Der Entwurf passt farblich gut zur Palette (Dunkelblau, Eisblau/Cyan). Für die Umsetzung fällt auf:
- **Name (entschieden):** Der Logo-Schriftzug lautet **PWDR**. Der Entwurf sagt noch "Freeride Powder App", das wird ersetzt. Ein kleiner Zusatz "Powder" ist nicht nötig.
- **Welle:** Die Schneewelle sieht stark nach Meereswelle (Surf) aus. Für Schnee eher weichere, gestäubte Kanten und Schneestaub statt Wasserspritzer.
- **Icon-Größe:** Das Icon hat sehr viele feine Details. Bei 48 dp (Launcher) und in der Statusleiste verschwinden sie. Ein vereinfachtes Icon: ein Motiv (Skifahrer **oder** Welle), wenige Flächen.
- **Android-Format:** Adaptive Icon mit Vordergrund und Hintergrund getrennt (108 × 108 dp, Motiv innerhalb der inneren 66 dp), ohne das eigene abgerundete Quadrat. Dazu ein einfarbiges Icon für "Themed Icons" (Android 13+). Benachrichtigungs-Icon (Tracking-Service) einfarbig weiß, nur Silhouette.
- **Schriftzug:** "APP" in Dunkelblau auf dunklem Hintergrund hat zu wenig Kontrast. Auf Weiß oder helle Variante des Logos ausweichen.
- **Dateien:** Bei der Umsetzung als SVG ablegen (Vektor), nicht als Pixelbild. Die Vorlage v1 ist ein Pixelbild und muss nachgezeichnet oder neu erzeugt werden.

### Typografie
- Schrift: Inter oder Roboto Flex, nur wenige Stufen.
- Große Kennzahlen mit tabellarischen Ziffern (`tnum`), damit Zahlen nicht springen.
- Speed im Aufzeichnen-Screen: sehr groß (ca. 96–120 sp).

### Navigation
Untere Leiste, vier Tabs:

| Tab | Inhalt |
|---|---|
| Karte | Pistenplan, eigene Position, Freunde, Wetter-/Lawinen-Overlay, Webcams, Community-Meldungen |
| Aufzeichnen | Start/Stopp, Live-Werte, Sprachausgabe an/aus |
| Statistik | Tag / Woche / Monat / Saison, Metrik-Umschalter |
| Freunde | Gruppe, Standort teilen, Einladungen |

Einstellungen erreicht man über das Menü oben rechts. Das **Skigebiet-Profil** (Wetter, Schnee, Lawinenbulletin, Webcams, siehe 5d und 5e) öffnet man per Tipp auf das Skigebiet in der Karte oder über das Menü. Es gibt keinen eigenen Tab dafür.

### Screens

**Aufzeichnen (während der Fahrt, handschuhtauglich)**
```
+--------------------------------+
|  Pischa Abfahrt 14   ● REC     |  Pistenname, blauer Punkt = Pistenfarbe
|                                |
|            54                  |  Speed, riesig
|           km/h                 |
|                                |
|  Vmax 71   Höhe 2.340 m        |
|  Distanz 2,1 km   ↓ 412 m      |
|                                |
|  [ 🔊 Sprache ]  [ ■ Stopp ]   |  grosse Tasten, min. 64 dp
+--------------------------------+
```
- Zustand wird oben angezeigt: **Abfahrt / Lift / Pause**.
- Im Lift: Liftname, Restdauer (geschätzt), Höhe.
- Stopp mit Halten (Long-Press), damit man nicht versehentlich beendet.
- Bildschirm bleibt wahlweise an oder wird gedimmt, um Akku zu sparen.

**Karte**
- Vollflächig, schwebende Schalter für Ebenen, Standort-Button, Meldung-Button.
- Freunde als runde Avatare mit Name und Distanz, Tipp öffnet eine Karte unten (Bottom Sheet).
- Lifte als Linien mit Namen, ohne Statusanzeige. Meldungen "Hier ist viel los" als Marker mit Alter ("vor 12 min").
- Bottom Sheet für Piste/Lift: Name, Schwierigkeit, Länge (ohne Betriebsstatus, siehe 5).

**Statistik**
- Oben: Zeitraum-Umschalter, darunter Metrik-Umschalter (4 Chips in Metrikfarben).
- Darunter: Kennzahlen-Kacheln, Hauptdiagramm, Listen. Details siehe 4a.
- Abfahrt antippen: Detail mit eingefärbter Spur und Kurven.

**Freunde**
- Liste mit Status (teilt gerade, zuletzt gesehen, Distanz).
- Schalter "Heute teilen" mit Ablaufzeit. Einladung per Code oder Link.
- Einstellungen für Annäherungs-Ansage pro Freund (an/aus, Distanz).

**Einstellungen**
- Theme, Einheiten, Trageposition des Handys, Sprachausgabe (Intervall, Inhalte, Stimme, Lautstärke), Annäherung (Schwellen), Akku-Modus, Datenexport (GPX), Konto.

### Komponenten und Stil
- Karten mit Radius 16 dp, keine Schatten, 1-dp-Rand in leicht hellerer Fläche.
- Chips für Umschalter, Segmented Control für Zeiträume.
- Icons: Material Symbols (Outlined), einheitliche Strichstärke.
- Diagramme: dünne Linien, Flächen mit geringer Deckkraft, keine Gitterflut, Werte beim Antippen.
- Animationen kurz und dezent (150–250 ms), keine großen Übergänge während der Fahrt.
- Barrierefreiheit: Mindestgröße 48 dp (beim Aufzeichnen 64 dp), TalkBack-Beschriftungen, Farben nie als einziges Signal (Pistenfarben mit Form/Text ergänzen).

## 5b. Sprachausgabe im Detail

Ziel: Informationen im Kopfhörer, ohne zu nerven und ohne Musik oder Telefonate zu stören. Alles ist abschaltbar und pro Typ einstellbar.

### Ansage-Typen

| Typ | Auslöser | Beispieltext | Priorität |
|---|---|---|---|
| Sicherheit | Freund unterschreitet die engste Schwelle (50 m) | "Achtung, Marco ist 50 Meter entfernt." | Hoch |
| Freund nähert sich | Distanz unterschreitet 300 m oder 150 m (siehe unten) | "Marco ist 150 Meter entfernt." | Mittel |
| Speed (Intervall) | Alle X Sekunden während der Abfahrt | "54 Kilometer pro Stunde." | Niedrig |
| Neuer Höchstwert | Vmax des Tages überschritten | "Neuer Rekord: 71 Kilometer pro Stunde." | Mittel |
| Speed auf Abruf | Tipp auf Sprach-Taste, Kopfhörer-Taste oder Sprachbefehl | "Aktuell 54, Höchstwert 71." | Hoch |
| Abfahrt-Ende | Abfahrt beendet | "Abfahrt beendet. 2,1 Kilometer, 412 Höhenmeter, Höchstwert 68." | Niedrig |
| Zustand | Wechsel auf Lift/Abfahrt (optional, standardmäßig aus) | "Lift. Sechserbahn Muot." | Niedrig |
| Meilenstein | z. B. 10.000 Höhenmeter am Tag | "10.000 Höhenmeter geschafft." | Niedrig |

### Einstellungen
- Hauptschalter, Lautstärke, Stimme (Android-TTS deutsche Stimmen), Sprechtempo
- **Speed-Intervall:** Aus / 10 s / 20 s / 30 s / 60 s. Zusätzlich "nur bei Änderung um mehr als 5 km/h" (verhindert gleiche Ansagen).
- Neuer Höchstwert an/aus
- Abfahrt-Ende-Zusammenfassung an/aus (kurz / ausführlich)
- Freunde: global an/aus, pro Freund an/aus, Distanzschwellen
- Nur mit Kopfhörer: Wenn nichts angeschlossen ist, wird nicht über den Lautsprecher gesprochen (Standard an)
- Nur während der Abfahrt: Intervall-Ansagen nur in Abfahrten, nicht im Lift oder in der Pause (Standard an)

### Annäherung an Freunde

**Eingaben:** eigene Position und die letzte bekannte Position jedes Freundes (Backend, Echtzeit). Für Ansagen gilt ein Freund nur als aktuell, wenn sein Standort jünger als 30 s ist (sonst keine Ansage). Ein Freund im Stillstand sendet nur alle 30 s (siehe 5c) und löst deshalb keine Ansage aus. Sobald man ihm näher als 500 m kommt, schaltet er auf 5 s Takt, weil er die eigene Position empfängt, und gilt danach als aktuell.

**Distanz:** Luftlinie (Haversine). Höhenunterschied wird mitgerechnet, damit ein Freund auf der Piste darunter oder in der Gondel darüber nicht als "nah" gilt: Ist der Höhenunterschied über 30 m, gilt ein Freund als "nicht auf gleicher Ebene" und löst nichts aus.

**Schwellen (Standard):** 300 m, 150 m, 50 m. Pro Freund ein Zustand "zuletzt angesagte Stufe".
- Ansage nur beim Überschreiten einer Stufe von außen nach innen.
- Hysterese: Eine Stufe wird erst wieder frei, wenn der Freund mehr als 1,5-fach der Distanz weg ist. So gibt es kein Flackern an der Grenze.
- Cooldown: Pro Freund höchstens eine Ansage alle 60 s.
- Mehrere Freunde gleichzeitig: Eine Ansage wird gebündelt ("Marco und Lena sind in der Nähe.").

**Bewegungsrichtung (optional, später):** Entfernt sich ein Freund, wird nichts angesagt. Nähert er sich, wird die Ansage mit Richtung ergänzt ("Marco, 80 Meter, links vor dir"). Das braucht eine stabile Kursschätzung und kommt erst in einer späteren Version.

**Annäherung im Lift:** Fahren zwei Freunde im selben Lift oder in der Nähe der Station, gibt es keine Ansage (Zustand "Lift" und Position nahe der Liftlinie). Vor allem an Talstationen würde es sonst ständig auslösen.

### Priorisierung und Warteschlange
- Eine zentrale Sprach-Queue. Jede Nachricht hat Priorität, Zeitstempel und Ablaufzeit (z. B. Speed-Ansage nach 3 s veraltet, wird verworfen).
- Hohe Priorität unterbricht laufende niedrige Ansagen. Niedrige Ansagen werden verworfen, wenn etwas Wichtigeres wartet.
- Nie zwei Speed-Ansagen hintereinander. Es zählt nur der aktuelle Wert zum Zeitpunkt des Sprechens, nicht der beim Einreihen.
- Mindestabstand zwischen zwei Ansagen: ca. 2 s.

### Audio-System (Android)
- **TextToSpeech** mit `AudioAttributes` USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH (wie Navigations-Apps).
- **Audio-Focus:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, Musik wird leiser, nicht gestoppt, und kommt danach zurück. Bei Telefonanruf (Focus verloren) werden Ansagen pausiert und verworfen.
- **Kopfhörer-Erkennung:** `AudioManager` und Geräte-Callbacks (Bluetooth A2DP, Kabel, USB). Ohne Kopfhörer gilt die Option "nur mit Kopfhörer".
- **Läuft im Foreground Service** des Trackings, damit Ansagen auch bei gesperrtem Bildschirm funktionieren.
- TTS-Engine beim Start initialisieren, damit die erste Ansage nicht verzögert kommt. Vorab prüfen, ob eine deutsche Stimme installiert ist, sonst Hinweis mit Link zur Installation.

### Texte natürlich halten
- Zahlen runden: Speed ganzzahlig, Distanz auf 10 m unter 200 m, auf 50 m darüber.
- Einheiten ausschreiben ("Kilometer pro Stunde"), kurze Sätze, keine Abkürzungen.
- Deutsche Zahlen und Dezimalkomma ("zwei Komma eins Kilometer").
- Freundesnamen: Anzeigenamen oder frei wählbarer Aussprache-Name im Freund-Eintrag (TTS spricht manche Namen falsch).
- Texte als Vorlagen mit Platzhaltern in `strings.xml`.

### Steuerung ohne Display
- **Kopfhörer-Taste** (Media-Button): einmal drücken = Speed jetzt ansagen (über `MediaSession`).
- Doppelt drücken = Tageszusammenfassung (Höhenmeter, Abfahrten, Vmax).
- Optionales Sprachkommando ("Powder, wie schnell bin ich?") als spätere Option. Es braucht Mikrofon-Berechtigung und einen Offline-Sprachdienst und gehört nicht in die erste Version.
- Der Bildschirm-Button "Sprache" schaltet die Ausgabe an/aus, Long-Press öffnet die Einstellungen.

### Akku und Zuverlässigkeit
- Intervall-Logik läuft im Tracking-Service ohne zusätzlichen Timer-Wakeup (am GPS-Takt orientiert).
- Bei Bluetooth-Verlust (Kopfhörer weg) wird still pausiert und beim Verbinden wieder aufgenommen.
- Stimmen-Wechsel und Sprechtempo mit Test-Taste in den Einstellungen.

### Testplan
1. Unit-Tests für Schwellen, Hysterese, Cooldown und Queue mit simulierten Positionen (kein Gerät nötig).
2. Replay einer aufgezeichneten GPX-Datei, die Ansagen als Textlog statt Audio ausgibt.
3. Praxistest mit Kopfhörern (Bluetooth und Kabel), Musik, Telefonanruf, gesperrtem Display, Kälte (Akku).
4. Praxistest mit zwei Personen: Annäherung zu Fuß und auf der Piste, Schwellen anpassen.

### Offene Punkte
- Wie viel Ansage ist angenehm? Die Standardwerte (Intervall aus, Höchstwert an, Freunde an) sind ein Vorschlag und werden nach dem ersten Praxistest angepasst.
- Qualität der deutschen Android-Stimmen hängt vom Gerät ab. Eventuell später eine bessere Offline-Stimme (z. B. Piper) als Option.

## 5c. Backend: Freunde und Live-Standort

Ziel: Freunde sehen sich live auf der Karte, die Annäherungs-Ansage (5b) bekommt aktuelle Positionen, und Community-Meldungen haben einen gemeinsamen Speicher. Das Backend ist klein gehalten, die privaten Tracks bleiben auf dem Gerät.

### Entscheidung: Supabase
- Postgres mit PostGIS, Auth, Row Level Security (RLS), Realtime und Edge Functions in einem Dienst. Kein eigener Server.
- Gratis-Stufe reicht für einen Freundeskreis (Größenordnung 5–30 Personen). Bei Wachstum ist der Wechsel auf die bezahlte Stufe möglich.
- Region: Frankfurt oder Zürich (nahe an der Schweiz, wichtig für Datenschutz und Latenz).
- Alternative: Firebase. Es ist weniger geeignet, weil Geo-Abfragen und SQL-Auswertungen fehlen und die Zugriffsregeln komplizierter werden.
- Android-Client: `supabase-kt` (Auth, Postgrest, Realtime) mit Ktor-Engine.

### Was liegt im Backend, was bleibt lokal

| Backend | Nur lokal (Room) |
|---|---|
| Profil (Anzeigename, Avatar-Farbe) | Alle Tracks, TrackPoints, Statistik |
| Freundschaften (Gruppen später) | Einstellungen, Sprachausgabe |
| Live-Standort (nur während des Teilens) | Rekorde, Saison-Daten |
| Community-Meldungen | |

Statistiken werden in Version 1 nicht synchronisiert. Ein Geräteverlust bedeutet Datenverlust. Dafür gibt es früh den GPX-Export (4a) und später optional ein verschlüsseltes Backup.

### Konto und Anmeldung
- Supabase Auth, anfangs **anonym oder E-Mail-Magic-Link** (kein Passwort). Später optional Google-Anmeldung.
- Beim ersten Start: Anzeigename wählen (frei, erscheint bei Freunden und in der Sprachausgabe), Avatar-Farbe wählen. Aussprache-Name optional.
- Konto löschen in der App (löscht Profil, Freundschaften, Live-Daten, Meldungen).

### Freunde: Einladung und Verbindung
1. Nutzer A tippt "Freund einladen": Die App erzeugt einen **Einladungscode** (6 Zeichen, 24 h gültig, einmal einlösbar) und einen Link `pwdr://join/<code>`, teilbar per Messenger.
2. Nutzer B öffnet den Link oder gibt den Code ein. Edge Function `redeem_invite` prüft Code, erstellt eine gegenseitige Freundschaft (Status `accepted`).
3. Es gibt keine öffentliche Suche, keine Vorschläge, keine Nutzerliste.
4. Ein Freund kann jederzeit entfernt oder blockiert werden. Dabei werden sofort alle Freigaben beendet.

Gruppen (z. B. "Wochenende Arosa") sind in Abschnitt 5f geplant. Bis dahin gelten Freundschaften mit pro Freund einstellbarer Freigabe.

### Standort teilen: Freigabe-Modell
- **Geändert am 15.11.2026:** Teilen läuft jetzt **automatisch beim Aufzeichnen** mit allen Freunden und endet mit dem Stopp (höchstens 12 Stunden). Eine Freundschaft gilt für beide Seiten: Wer aufzeichnet, ist für seine Freunde auf der Karte sichtbar, und sieht umgekehrt die Freunde, die gerade aufzeichnen. Ein Schalter "Beim Aufzeichnen automatisch teilen" (Standard an) schaltet das ganz aus, ein Schalter pro Freund nimmt einzelne aus. Der sichtbare Hinweis "Du teilst deinen Standort mit N Personen" bleibt. Jeder von beiden kann die Verbindung jederzeit entfernen, dabei enden alle Freigaben sofort. Der frühere Entwurf: "Teilen ist standardmäßig aus, pro Tag aktiv gestartet mit Ablaufzeit 1 h / 4 h / 8 h" gilt nicht mehr.
- Pro Freund wählbar: teilen an/aus (Standard an). Nur wer freigegeben ist, sieht die Position.
- Beim Beenden des Trackings oder bei Ablauf endet die Freigabe automatisch, und die Live-Zeile wird gelöscht.
- Dauerhafte, versteckte Freigabe gibt es nicht. Auf dem Startscreen zeigt ein sichtbarer Hinweis "Du teilst deinen Standort mit 3 Personen".
- Man sieht umgekehrt, wer gerade die eigene Position sieht.

### Datenmodell (Postgres)

```sql
profiles        (id uuid PK = auth.uid, display_name, spoken_name, color, created_at)

friendships     (id, user_a, user_b, status 'accepted'|'blocked', created_at)
                -- eine Zeile pro Paar, user_a < user_b; Index (user_a, user_b) unique

invites         (code PK, created_by, expires_at, used_by, used_at)

share_sessions  (id, user_id, started_at, expires_at, ended_at)
share_targets   (session_id, friend_id)          -- wem die Session zugänglich ist

live_locations  (user_id PK, session_id, geom geography(Point), alt, speed,
                 heading, accuracy, state 'downhill'|'lift'|'pause',
                 updated_at)
                -- genau eine Zeile pro Nutzer, wird überschrieben (UPSERT)

reports         (id, user_id, type 'queue'|'snow'|'piste'|'warning'|'other',
                 geom, text, created_at, expires_at, up_votes, down_votes)
```

- `live_locations` hält nur den **letzten** Stand. Es gibt keine Standortgeschichte auf dem Server.

### Zugriffsregeln (RLS)
- `profiles`: eigenes Profil lesen und ändern. Freunde dürfen `display_name`, `color`, `spoken_name` lesen.
- `friendships`: nur Beteiligte lesen. Erstellen läuft ausschließlich über die Edge Function (nicht direkt per Client).
- `live_locations`: schreiben nur die eigene Zeile. Lesen nur, wenn eine aktive `share_session` des Besitzers existiert (`now() < expires_at`, `ended_at IS NULL`) **und** der Leser in `share_targets` steht **und** die Freundschaft `accepted` ist.
- `reports`: alle angemeldeten Nutzer lesen (innerhalb Reichweite), schreiben nur als sich selbst, bearbeiten/löschen nur eigene.
- Alle Policies mit Tests (pgTAP oder SQL-Skripte), weil Fehler hier Standort-Lecks bedeuten.

### Live-Standort: Übertragung
- **Senden:** Der Tracking-Service schickt die Position, wenn geteilt wird, per Postgrest-UPSERT oder Edge Function. Das ergibt eine einzige Zeile pro Nutzer.
- **Empfangen:** Supabase Realtime (Postgres Changes) auf `live_locations`, gefiltert über RLS auf zugängliche Freunde. Alternative: Realtime Broadcast pro Gruppe, falls Postgres Changes bei vielen Updates zu schwer wird. Für einen Freundeskreis reicht Postgres Changes.
- **Takt (adaptiv):**

| Zustand | Sendeintervall |
|---|---|
| Abfahrt | 3 s |
| Lift / Pause, Freund in der Nähe (< 500 m) | 5 s |
| Lift / Pause, kein Freund nah | 15 s |
| Stillstand > 2 min | 30 s |
| Kein Netz | Nichts senden, letzten Stand merken |

- **Bei Reconnect:** Nur der aktuellste Stand wird gesendet, kein Nachschicken der alten Positionen.
- **Staleness:** Der Client zeigt Freunde ab 60 s ohne Update ausgegraut ("vor 2 min"), ab 5 min ausgeblendet. (60 s statt 30 s, weil ein Freund im Stillstand nur alle 30 s sendet.) Die Annäherungs-Ansage (5b) ignoriert Positionen älter als 30 s.
- **Akku/Datenvolumen:** Eine Nachricht ist ca. 150 Byte. Bei 3 s Takt sind das etwa 3 MB pro Tag und Person, vernachlässigbar. Den Akku belastet vor allem der Funk. Der Takt-Plan oben und das Bündeln mit dem GPS-Takt halten das klein.
- **Funklöcher:** Im Skigebiet ist der Empfang oft schlecht. Das System ist darauf ausgelegt ("zuletzt gesehen") und macht keine falschen Versprechungen.

### Aufräumen
- Geplante Funktion (pg_cron, jede Minute): Setzt abgelaufene Sessions auf `ended_at`, löscht dazugehörige `live_locations`-Zeilen.
- Reports laufen nach 2–6 Stunden ab (je nach Typ, Neuschnee-Meldung länger). Alte Reports werden täglich gelöscht.

### Andrang
- **Keine Messung in der App und keine externe Quelle.** Es gibt keine Wartebeobachtungen, keine Tabelle dafür und keine Funktion, die Daten von außen holt.
- Reports vom Typ `queue` ("Hier ist viel los") sind freiwillige Meldungen mit Ablaufzeit. Sie werden einzeln mit Ort und Alter angezeigt und nicht zu einer Zahl verrechnet.

### Community-Meldungen
- Typen: Andrang, Pistenzustand, Neuschnee, Warnung, Sonstiges. Mit Position, kurzem Text, optional Foto (später).
- Abstimmung: Daumen hoch/runter durch andere. Bei vielen Daumen runter wird die Meldung ausgeblendet.
- Missbrauchsschutz: Limit (z. B. 10 Meldungen pro Stunde pro Nutzer), Melden-Knopf. Im Freundeskreis reicht das, bei öffentlicher Nutzung braucht es mehr Moderation.
- Reichweite: Es werden nur Meldungen im Umkreis des aktuellen Skigebiets geladen (PostGIS `ST_DWithin`).

### API-Überblick

| Funktion | Art | Zweck |
|---|---|---|
| `create_invite()` | Edge Function / RPC | Einladungscode erzeugen |
| `redeem_invite(code)` | Edge Function | Freundschaft anlegen |
| `start_share(duration, friend_ids)` | RPC | Session und Targets anlegen |
| `stop_share()` | RPC | Session beenden, Live-Zeile löschen |
| `upsert_location(...)` | Postgrest | Eigene Live-Position aktualisieren |
| Realtime-Abo `live_locations` | Realtime | Freunde live |
| `post_report(...)`, `vote_report(...)` | RPC | Meldungen |
| `delete_account()` | Edge Function | Alles löschen |

### Client-Architektur (Android)
- Modul `core:social` mit Repository-Schicht: `FriendsRepository`, `LocationShareRepository`, `ReportsRepository`.
- `LiveFriendsFlow` liefert einen `StateFlow<List<FriendPosition>>` für Karte und Sprachausgabe. Die Annäherungslogik (5b) arbeitet nur auf diesem Flow, nicht direkt auf dem Netzwerk, und lässt sich dadurch mit simulierten Daten testen.
- Offline: Freundesliste und Einstellungen werden lokal gecacht. Ohne Netz bleibt die App voll nutzbar (Tracking, Statistik, Karte aus Cache), nur Live-Funktionen pausieren mit klarem Hinweis.
- Verbindungsstatus ist sichtbar (Symbol "Live" / "Keine Verbindung").

### Sicherheit
- Nur HTTPS/WSS. Der Supabase-Anon-Key liegt in der App, darf aber nur das, was die RLS erlaubt. Der Service-Role-Key kommt nie in die App.
- Einladungscodes sind zufällig, kurz gültig, nur einmal einlösbar, mit Rate-Limit gegen Raten.
- Kein Zugriff auf Positionen ohne gültige Freigabe, auch nicht für den Server-Betreiber im Normalbetrieb (keine Standort-Historie).
- Logs enthalten keine Koordinaten.

### Meilensteine für Phase 4 und 5
1. Supabase-Projekt, Schema, RLS, Tests mit zwei Test-Konten.
2. Auth, Profil, Anzeigename.
3. Einladung und Freundschaft (Edge Function).
4. Freigabe-Sessions und UPSERT der Position.
5. Realtime-Abo, Freunde auf der Karte, Staleness-Anzeige.
6. Adaptiver Sendetakt und Offline-Verhalten.
7. Anbindung an die Annäherungs-Ansage (5b).
8. Später (Phase 7): Community-Meldungen (`reports`), inklusive "Hier ist viel los".

### Risiken
| Risiko | Gegenmaßnahme |
|---|---|
| Standort-Leck durch falsche RLS | Policies testen, Zwei-Konten-Test, Review vor dem ersten echten Einsatz |
| Hoher Akkuverbrauch durch Senden | Adaptiver Takt, Funk bündeln, früh messen |
| Realtime-Verbindung bricht im Funkloch ab | Automatischer Reconnect mit Backoff, nur letzten Stand senden |
| Gratis-Limits (Realtime-Verbindungen, Pausierung bei Inaktivität) | Für den Freundeskreis ausreichend, Projekt wird in der Saison regelmäßig benutzt |
| Ein Anbieter, Abhängigkeit | Schema ist Standard-Postgres, Wechsel auf eigenes Postgres bleibt möglich |

## 5d. Wetter, Schnee und Lawinen

Ziel: Auf einen Blick sehen, wie das Wetter und die Schneelage im Skigebiet sind und wie hoch die Lawinengefahr ist. Alle Daten kommen aus offenen Schweizer Quellen und werden mit Quelle und Alter angezeigt.

### Quellen (geprüft am 14.11.2026)

Ich habe die drei Schnittstellen live abgefragt. Alle antworten ohne Anmeldung und ohne API-Schlüssel.

| Daten | Quelle | Bedingungen | Befund |
|---|---|---|---|
| **Lawinenbulletin** | SLF, [`aws.slf.ch/api/bulletin/caaml/{sprache}/json`](https://aws.slf.ch/api/bulletin/caaml) (Sprachen de, fr, it, en) | CC BY 4.0, gut sichtbare Quellenangabe (SLF), kostenlos ([SLF Datenservice](https://slf.ch/en/services-and-products/slf-data-service)). SLF garantiert keinen Bereitschaftsdienst und die Rohdaten sind nicht immer geprüft. | Antwortet mit `{"bulletins":[]}`, weil gerade kein Bulletin ausgegeben wird (Mitte November, die Saison hat noch nicht begonnen). Das Antwortformat mit Daten konnte ich deshalb nicht prüfen. Ein Drittanbieter behauptet, die API brauche eine Anmeldung. Das stimmt nach meinem Test nicht, ist aber ein Hinweis, dass sich das ändern kann. |
| **Schneehöhe und Messwerte** | SLF IMIS-Messnetz, [`measurement-api.slf.ch`](https://slf.ch/en/services-and-products/slf-data-service) | Gleiche Bedingungen wie oben (SLF Datenservice) | Antwortet. Stationen: 207 mit Typ (Schnee flach, Schnee Hang, Wind, Flowcapt), Daten alle 30 Minuten: Schneehöhe `HS`, Lufttemperatur, Wind, Feuchte, Strahlung, Schneetemperaturen. Die Endpunkte habe ich beobachtet, in der offiziellen Beschreibung stehen sie nicht im Detail. Die Einheit von `HS` (vermutlich cm) ist noch zu bestätigen. |
| **Wetterprognose** | [Open-Meteo](https://open-meteo.com/en/docs/meteoswiss-api) mit dem MeteoSchweiz-Modell ICON-CH1/CH2 | Kostenlos für nicht-kommerzielle Nutzung (unter 10'000 Anfragen pro Tag), Quellenangabe (CC BY 4.0), keine Garantie ([Bedingungen](https://open-meteo.com/en/terms)). MeteoSchweiz-Daten: frei nutzbar mit Quelle "MeteoSchweiz" ([Bedingungen](https://opendatadocs.meteoswiss.ch/general/terms-of-use)). | Antwortet für Adelboden mit Temperatur, Schneefall und Nullgradgrenze. Es gilt: wird das Projekt kommerziell, braucht es ein bezahltes Open-Meteo-Abo oder die direkte Anbindung an MeteoSchweiz. |
| **MeteoSchweiz direkt** | STAC-API `data.geo.admin.ch`, ICON-CH1/CH2 als GRIB2 | Frei mit Quelle | Technisch schwer für eine App (GRIB2-Dateien, Python-Bibliothek). Nur als spätere Alternative zu Open-Meteo vorgesehen. |

### Die nächsten Messstationen der Testgebiete (IMIS)

| Gebiet | Stationen (Abstand ca., Höhe, Typ) |
|---|---|
| **Lauchernalp** | Gandegg GAN2 (3 km, 2710 m, Schnee flach), Fisi FIS2 (10 km, 2155 m, Schnee flach), dazu Windstationen Sackhorn GAN1 und Gärsthorn MUN1 |
| **Adelboden-Lenk** | Lavey-Sattligrat ADE3 (2 km, 2355 m, Flowcapt), Färmel FAE2 (6 km, 1971 m, Schnee flach), Engstligenalp ADE2 (6 km, 2304 m, Schnee flach), Ottere OTT2 (8 km, 2017 m, Schnee flach), dazu Windstationen (Albristhorn FAE1, Schwarzhorn TRU1) |

Im Tal gibt es nur wenige IMIS-Stationen. Die Anzeige "Schnee im Tal" ist daher nicht immer möglich, und die App sagt dann "keine Messung im Tal" statt etwas zu schätzen.

### Funktionen und Anzeige

**Wetter**
- Aktuell und Prognose pro Skigebiet: Temperatur auf Berg- und Talhöhe, Wind, Bewölkung, Niederschlag, Schneefall, Nullgradgrenze.
- Stundenverlauf für heute und morgen, Tagesübersicht für 3–5 Tage.
- Berg- und Talhöhe: Zwei Abfragen (Koordinate Talstation und Bergstation) oder eine mit Höhenkorrektur. Ich würde zwei Punkte pro Gebiet fest hinterlegen.

**Schnee**
- Schneehöhe der nächsten Messstationen mit Höhe und Abstand ("Gandegg, 2710 m, 3 km: 56 cm, 08:30").
- Verlauf der letzten 24 h / 7 Tage als kleine Kurve (aus den 30-Minuten-Daten), Neuschnee der letzten 24 h als Differenz der Schneehöhe.
- Prognostizierter Neuschnee aus dem Wettermodell, deutlich getrennt von der Messung beschriftet ("Prognose").
- Hinweis zur Genauigkeit: Der SLF weist darauf hin, dass automatische Schneehöhenmessungen Fehler enthalten können (zum Beispiel bei starkem Schneefall oder wenn im Sommer Gras unter dem Sensor wächst). Diesen Hinweis blende ich einmal ein.

**Lawinen**
- Gefahrenstufe 1–5 pro Region mit Farbe und Ziffer (nach dem europäischen Standard: grün, gelb, orange, rot, schwarz/rot gestreift), Höhenlage und Exposition, Lawinenproblem, Tendenz, Text des Bulletins in der gewählten Sprache (Deutsch).
- Karte: Regionen als eingefärbte Flächen, Tippen öffnet das Bulletin der Region.
- Gültigkeit anzeigen ("gültig bis morgen 17:00"). Ist kein Bulletin vorhanden (wie jetzt im November), steht "Zurzeit gibt der SLF kein Bulletin aus" und **nicht** "keine Gefahr".
- Link zum offiziellen Bulletin auf `slf.ch`.
- Pflichthinweis auf der Anzeige und in den Einstellungen: Das Bulletin ersetzt keine eigene Beurteilung vor Ort. PWDR ist keine Lawinenwarn-App und gibt keine Empfehlung für Touren.

**Powder-Alert (später)**
- Benachrichtigung bei prognostiziertem Neuschnee über einem Schwellenwert (z. B. 20 cm in 24 h) für ein gewähltes Skigebiet.
- Braucht eine geplante Funktion im Backend (Wetter täglich prüfen) und Push (Firebase Cloud Messaging, ein zusätzlicher Dienst neben Supabase). Das ist mehr Aufwand und kommt erst nach Phase 8.

### Technik
- **Wo wird abgefragt?** Direkt von der App an die drei Quellen, ohne eigenen Server. Das reicht für einen Freundeskreis (weit unter dem Open-Meteo-Limit) und hält das Backend klein. Später, falls mehr Nutzer kommen, kann eine Backend-Funktion die Daten zwischenspeichern und für alle liefern. Das bleibt bei CC BY 4.0 für beide Quellen zulässig, sofern die Quellenangabe bleibt.
- **Ablauf:** Repository `WeatherRepository`, `SnowRepository`, `AvalancheRepository` im Modul `core:data`, je mit Ktor-Client (derselbe wie bei `supabase-kt`), Kotlin-Serialization, Room-Cache.
- **Cache und Offline:** Letzte Antwort mit Zeitstempel in Room. Ohne Netz zeigt die App den gespeicherten Stand mit klarer Altersangabe ("Stand vor 3 h"). Das Lawinenbulletin wird beim Start geladen und danach höchstens alle 30 Minuten. (Annahme: Der SLF gibt es nur wenige Male pro Tag aus. Das habe ich nicht geprüft.)
- **Abfrage-Takte:** Wetter alle 60 Minuten, IMIS alle 30 Minuten (entspricht der Messung), Bulletin alle 30 Minuten. Nur abfragen, wenn der Bildschirm das Gebiet zeigt oder die App im Vordergrund ist, und nicht im Hintergrund-Tracking, um den Akku zu schonen.
- **Skigebiets-Profil:** Pro Gebiet eine feste Konfiguration: Name, Koordinaten Berg/Tal, nächste IMIS-Stationen, Lawinenregion(en), Link zur Website der Bergbahn, Webcam-Links. Für die zwei Testgebiete von Hand anlegen. Die Lawinenregion wird für die zwei Testgebiete **von Hand** im Profil hinterlegt (nach Prüfung mit dem ersten Bulletin). Eine automatische Zuordnung aus der Position und den Regionsgrenzen ist erst später vorgesehen.
- **Fehlerverhalten:** Jede Quelle fällt einzeln aus. Fällt eine aus, bleiben die anderen sichtbar, mit Hinweis bei der ausgefallenen ("Schneedaten zurzeit nicht verfügbar"). SLF gibt ausdrücklich keinen Bereitschaftsdienst.
- **Attribution (Pflicht, im Impressum und bei der jeweiligen Anzeige):** "Quelle: SLF" (mit Link), "Quelle: MeteoSchweiz", "Wetterdaten: Open-Meteo.com (CC BY 4.0)", Kartendaten OSM/swisstopo.

### UI
- Tab **Karte**: Schalter "Lawinen" färbt die Regionen ein. Tippen öffnet ein Bottom Sheet mit Stufe und Kurztext.
- **Wetter-Karte** im Skigebiet-Profil (Menü oben rechts oder Tippen auf das Gebiet): Heute, Prognose, Schneehöhen, Lawinenstufe auf einer Seite. Ganz oben die Lawinenstufe, weil sie am wichtigsten ist.
- **Aufzeichnen-Screen:** Optional kleine Zeile mit Temperatur und Lawinenstufe. Standardmäßig aus, um die Anzeige ruhig zu halten.
- Farben der Lawinenstufen folgen dem Standard und sind nicht Teil der PWDR-Farbpalette, damit sie wiedererkannt werden. Immer mit Ziffer, nicht nur als Farbe.
- Sprachausgabe: Optional bei Start der Aufzeichnung die Ansage "Lawinengefahr heute Stufe 3, erheblich" (Standard aus, später).

### Offene Punkte und Risiken
| Punkt | Umgang |
|---|---|
| Bulletin-Format nicht geprüft (Saison noch nicht begonnen) | Mit der ersten Ausgabe im Winter die Antwort ansehen und das Modell anpassen. Bis dahin Mock-Daten aus den Beispielen der [OpenAPI-Beschreibung](https://aws.slf.ch/api/bulletin/caaml) für Entwicklung und Tests |
| SLF-Schnittstelle ohne Garantie, Änderung möglich | Nur gegen eine dünne Schicht im Code lesen, Fehler abfangen, Link zum offiziellen Bulletin immer anbieten |
| Falsche Sicherheit durch "grün" bei fehlendem Bulletin | Fehlendes Bulletin klar als "kein Bulletin" kennzeichnen, nie als niedrige Gefahr |
| Fehler in automatischen Schneemessungen | Hinweis anzeigen, Werte mit Messzeit zeigen, bei auffälligen Sprüngen nicht als Neuschnee werten |
| Open-Meteo nur nicht-kommerziell | Für das private Projekt in Ordnung. Bei Veröffentlichung oder Werbung prüfen, MeteoSchweiz direkt als Alternative |
| Zuordnung Gebiet zu Lawinenregion | Von Hand pro Testgebiet, später aus Regionspolygonen |
| Recht: Lawinenanzeige in der App | Keine eigene Interpretation, nur Wiedergabe des Bulletins mit Quelle und Hinweis. Wer die App weitergibt, sollte den Wortlaut mit dem SLF abstimmen |

### Meilensteine (Phase 6)
1. Skigebiets-Profile (zwei Testgebiete) und Konfiguration.
2. Wetter über Open-Meteo, Anzeige heute und Prognose.
3. IMIS-Stationen, Schneehöhe, 7-Tage-Kurve, Neuschnee aus Differenz.
4. Lawinenbulletin: Modell, Cache, Anzeige. Mit Mock-Daten, dann mit echten Daten ab Dezember.
5. Karten-Overlay Regionen.
6. Attribution, Hinweise, Fehlerzustände, Offline-Anzeige.
7. Später: Powder-Alert (Backend und Push), Ansage bei Aufzeichnungsstart.

## 5e. Webcams

Ziel: Im Skigebiet-Profil und auf der Karte mit einem Tipp sehen, wie es am Berg aussieht (Sicht, Schneelage, Betrieb). Es geht um schnelle Orientierung, nicht um ein eigenes Webcam-Netz.

### Grundentscheidung: verlinken statt kopieren
Webcam-Bilder gehören den Betreibern (Bergbahnen, Tourismusorganisationen, Anbieter wie Feratel). Eine Schweizer Open-Data-Quelle für Webcam-Bilder habe ich nicht gefunden. Deshalb:
- **Version 1:** Eine **kuratierte Liste** pro Skigebiet (Name, Höhe, Blickrichtung, Koordinate, Link). Tippen öffnet die offizielle Seite im **Chrome Custom Tab** (oder im eingebauten Browser). Die App lädt, speichert und zeigt die Bilder nicht selbst.
- Die App liest keine Bilder von Drittseiten aus (kein Scraping von skiresort.info, windfinder.com, snow-online.com).
- Pro Eintrag steht die Quelle ("Quelle: valais.ch") und das Datum der letzten Linkprüfung.
- Vorteil: keine Rechteprobleme, kein Aufwand für Bildabruf, immer das aktuelle Bild der Quelle. Nachteil: Wechsel in den Browser, keine Vorschau in der App und kein Offline.

### Kandidaten für die Testgebiete (Recherche, Links noch nicht einzeln geprüft)

Die Suchergebnisse zeigen Kameras, aber nicht, ob sie gerade senden. Teilweise zeigen die Seiten archivierte Bilder. Vor dem Eintrag ist jede Kamera einzeln zu prüfen (Bild aktuell, Link stabil).

| Gebiet | Kamera (ungefähre Höhe) | Mögliche offizielle Quelle |
|---|---|---|
| Lauchernalp | Bergstation Luftseilbahn Wiler–Lauchernalp (ca. 1970 m) | [valais.ch Webcam](https://www.valais.ch/en/destinations/loetschental-region/loetschental/webcams/bergstation-luftseilbahn-wiler-lauchernalp) |
| Lauchernalp | Gandegg (ca. 2700 m) | [valais.ch Webcam](https://www.valais.ch/en/destinations/loetschental/lotschental/webcams/lauchernalp-gandegg) |
| Lauchernalp | Lauchernalp-Stafel (ca. 2120 m), Hockenhorngrat (ca. 3110 m), Lötschenlücke/Bietschhorn (ca. 1970 m) | [loetschental.ch Webcams](https://www.loetschental.ch/en/general/webcams) und valais.ch |
| Adelboden-Lenk | Metschstand (ca. 2100 m), Sillerenbühl (ca. 1970 m) | Betreiber-Seite (Adelboden-Lenk) noch zu finden, Hinweise auf skiresort.info |
| Adelboden-Lenk | Höchstbahn-Panorama, Skilift Höchst–Metsch, Betelberg/Leiterli (Panorama) | Hinweise auf windfinder.com, offizielle Quelle noch zu klären |

Für Adelboden-Lenk habe ich die offizielle Webcam-Seite der Bergbahnen nicht gefunden. Das ist der nächste Schritt (Website adelboden-lenk.ch bzw. lenk-simmental.ch).

### Spätere Option: Windy Webcams API
Wenn Vorschaubilder in der App gewünscht sind:
- [Windy Webcams API](https://api.windy.com/webcams/docs): kostenloser Schlüssel, Kameras nach Ort/Radius abfragbar.
- **Einschränkungen** laut [Bedingungen](https://api.windy.com:443/webcams/terms): Bilder nur in Originalgröße oder kleiner, nur die von der API gelieferten URLs, "as is" und jederzeit beendbar. Bild-URLs laufen in der kostenlosen Stufe nach 10 Minuten ab und müssen bei jedem Laden neu geholt werden.
- Offen: Wie genau die Quellenangabe in der kostenlosen Stufe aussehen muss, ist in den Bedingungen unklar (laut Windy-Mitarbeiter sollen sie aktualisiert werden). Vor Einsatz Windy fragen oder die aktuellen Bedingungen lesen. Weitergabe an Dritte ist laut Windy-Lizenz nicht erlaubt, ein Zwischenspeichern für alle Nutzer im Backend fällt wahrscheinlich darunter.
- Alternative: OpenWebcamDB (kostenlos, nur nicht-kommerziell, Quellenangabe). Nicht geprüft.
- Entscheidung dazu fällt erst, wenn Version 1 läuft.

### Datenmodell
Die Webcams liegen als **statische Konfiguration** im Skigebiet-Profil (JSON in der App, kein Backend):
```
Webcam (id, resort_id, name, elevation_m, direction, lat, lon,
        url, source_name, last_checked)
```
Änderungen kommen mit einem App-Update. Später, falls Kameras häufig wechseln, per Remote-Konfiguration (Datei im Backend).

### UI
- **Skigebiet-Profil:** Abschnitt "Webcams" mit einer Liste, sortiert nach Höhe. Jede Zeile: Name, Höhe, Quelle, Pfeil nach außen (öffnet Browser).
- **Karte:** Kamera-Symbol an der Position, Tippen öffnet ein Bottom Sheet mit Name, Höhe, Blickrichtung (Pfeil) und der Schaltfläche "Webcam öffnen". Ebene "Webcams" in der Ebenenauswahl, standardmäßig an.
- **Wetter-Seite (5d):** Kamera mit der höchsten Lage als Verknüpfung am Seitenende ("Wie sieht es oben aus?").
- Kein Auto-Laden von Bildern in der App, also kein Mobilfunk-Verbrauch ohne Absicht des Nutzers.
- Fehlerfall: Lässt sich ein Link nicht öffnen, zeigt der Browser seine eigene Fehlerseite. Die App kann das nicht erkennen. Deshalb Datum der Prüfung in der Liste und ein Melden-Knopf ("Link funktioniert nicht") als Community-Meldung (Typ `other`).

### Rechtliches und Datenschutz
- Webcam-Bilder werden nur über die Seite der Quelle angezeigt, nicht kopiert. Rechte und Bildnachweise liegen bei der Quelle.
- In den Einstellungen unter "Quellen": Liste der Quellen (valais.ch, loetschental.ch, adelboden-lenk.ch, …).
- Webcams zeigen teilweise Personen. Die App macht daran nichts (keine Bildanalyse, kein Speichern).
- Eigene Kamera-Standorte der Nutzer oder Foto-Upload gibt es nicht (Foto bei Meldungen später, getrennt geplant).

### Meilensteine (Teil von Phase 6/7)
1. Die Kameras der Testgebiete einzeln prüfen und die offiziellen Seiten finden (insbesondere Adelboden-Lenk).
2. Konfigurationsdatei und Modell `Webcam`.
3. Liste im Skigebiet-Profil, Öffnen im Custom Tab.
4. Karten-Ebene mit Symbolen und Bottom Sheet.
5. Quellenliste in den Einstellungen.
6. Später: Windy-API oder Vorschaubilder, nach Klärung der Bedingungen.

### Risiken
| Risiko | Umgang |
|---|---|
| Links ändern oder Kameras fallen aus | Prüfdatum anzeigen, Meldung "Link funktioniert nicht", Liste per App-Update pflegen |
| Kameras senden im Sommer oder bei Schlechtwetter archivierte Bilder | Quelle sagt es meist selbst an. Nicht in der App bewerten |
| Rechte an Bildern | Nur verlinken, nicht kopieren, nicht scrapen |
| Windy-Bedingungen unklar | Erst nach Klärung einsetzen |

## 5f. Gruppen

Stand: **Plan vom 20.11.2026. Schritt 1 (Migration `0006_groups.sql` mit Backend-Tests) umgesetzt am 20.11.2026, die App nutzt Gruppen noch nicht.** Die Docker-Suite ist grün (`12_groups_tests.sql`, 284 geprüfte Aussagen über alle drei Dateien). Zur Kontrolle wurden vier Lücken absichtlich eingebaut (Blockieren ignoriert, Teilen-Schalter ignoriert, Ablauf ignoriert, entfernte Mitglieder dürfen per Code zurück), und die Tests haben jede gefunden. **Abweichungen vom Plan:** `profiles_select` bleibt unverändert, Namen von Nicht-Freunden kommen nur über `list_group_members` und `my_viewers` (weniger Angriffsfläche). Neu ist `code_kind(code)`, damit die App ein einziges Eingabefeld für Freundes- und Gruppen-Codes braucht, ohne Fehlversuche auszulösen. Die Anzahl der Mitgliedschaften ist auf 10 begrenzt (als Mitglied, nicht als Ersteller). **Schritt 2 (20.11.2026, 185 Unit-Tests grün, nur ohne Anmeldung im Emulator gesehen):** `social/Groups.kt` mit den Zeilenmodellen und `VisiblePeople.merge` (Freunde und Mitglieder aktiver Gruppen ohne Doppelte, ohne mich selbst, Blockierte nie, Ansagen pro Gruppe). `Social` lädt Gruppen, Mitglieder, Einladungen und `my_viewers` (alle 60 s und bei unbekannter Person in den Live-Daten, höchstens alle 30 s), stellt alle Gruppen-Aktionen als Funktionen bereit und zeigt den Zustand als `people`. Karte, Sprachausgabe und Status zählen jetzt Personen statt nur Freunde. `startAutoShare` startet auch ohne Freunde, wenn es eine aktive Gruppe mit Teilen gibt, und beim Beitritt während der Aufzeichnung wird das Teilen nachgestartet. Fehlt `0006` im Backend, bleibt alles leer und die Statuskarte meldet "Gruppen: im Backend nicht eingespielt (0006)". **Schritt 3 (20.11.2026, 194 Unit-Tests grün):** Der Tab "Freunde" hat oben einen Umschalter Freunde und Gruppen ("Gruppen (1 neu)" bei Einladungen, dazu ein Zähler am Tab). Gruppenliste mit Einladungen (Ansehen, Ablehnen), eigenen Gruppen (Name, Mitglieder, Laufzeit, abgelaufen in Rot), "Gruppe erstellen" (Name, Laufzeit mit 7 Tagen als Vorschlag, Freunde gleich auswählen) und einem Code-Feld, das Freundes- und Gruppen-Codes erkennt. Gruppenseite: eigene Schalter (Standort teilen, Ansagen), Mitglieder mit Farbe, Rolle, "neu" (24 h) und Status, offene Einladungen mit Zurückziehen, Freunde hinzufügen, Code erzeugen und teilen (Link `pwdr://group/CODE`), Codes widerrufen, Umbenennen, Verlängern, Verlassen, Löschen. Vor jedem Beitritt (Code, Link oder Einladung) steht ein Dialog mit Gruppenname, Ersteller, Mitgliederzahl und dem Satz zur Sichtbarkeit. Der Hinweis "Du teilst deinen Standort mit N Personen" nennt jetzt die Namen und worüber sie dich sehen. Oberfläche im Emulator mit Beispieldaten gesehen (die Beispieldaten sind wieder entfernt). **Noch nicht gesehen:** alles gegen das echte Backend (braucht `0006` im Supabase-Projekt und mehrere Konten), Gruppenmitglieder als Punkte auf der Karte mit echten Daten, Link `pwdr://group/CODE` vom Messenger, Teilen-Intent. Auslöser: Eine Gruppe (zum Beispiel "Wochenende Arosa") soll sich gegenseitig auf der Karte sehen, auch wenn nicht alle untereinander befreundet sind. Der Ersteller lädt per Code oder Link ein, wer beitritt, sieht alle in der Gruppe und wird von allen gesehen.

### Ziel und Abgrenzung
- Eine Gruppe ist eine benannte Menge von Personen mit **gegenseitiger Sicht auf den Live-Standort**, solange jemand aufzeichnet.
- Mitglieder einer Gruppe werden dadurch **keine Freunde**. Sie sehen voneinander nur Anzeigename, Farbe und Live-Standort, nichts sonst (keine Freundesliste, keine anderen Gruppen).
- Freunde und Gruppen laufen nebeneinander. Wer Freund und Gruppenmitglied ist, erscheint auf der Karte nur einmal.
- **Zwei Wege in eine Gruppe:** (a) bestehende Freunde direkt einladen, (b) Code oder Link für Personen, die noch keine Freunde sind. Beides endet damit, dass die Person Mitglied ist.
- Nicht Teil von Version 1: Chat, Gruppen-Rangliste, gemeinsame Treffpunkte, Fotos.

### Das Datenschutz-Modell (wichtigste Entscheidung)
Die Gruppe ist der heikelste Teil der App, denn ein weitergeleiteter Einladungslink kann einer fremden Person den Standort aller zeigen. Deshalb:
1. **Beitritt mit Bestätigung des Beitretenden.** Vor dem Beitritt zeigt die App: Gruppenname, Ersteller, Anzahl Mitglieder und den Satz "Alle Mitglieder sehen deinen Standort, solange du aufzeichnest." Erst ein Tipp auf "Beitreten" tritt bei.
2. **Freunde werden nie still hinzugefügt.** Auch ein bestehender Freund erhält eine Einladung und muss sie annehmen. Eine Freundschaft heisst, dass er dich sehen darf, nicht dass er damit einverstanden ist, mit allen Mitgliedern einer Gruppe gegenseitig sichtbar zu sein, auch mit Fremden. Bis zur Annahme sieht niemand etwas. Die Einladung läuft nach 7 Tagen ab, der Freund kann ablehnen, der Ersteller kann sie zurückziehen. Wer später nicht mehr befreundet ist, bleibt Mitglied (die Mitgliedschaft hängt nicht an der Freundschaft).
3. **Einladungscodes sind kurzlebig und begrenzt:** gültig 24 Stunden, höchstens 10 Beitritte (vom Ersteller beim Erzeugen wählbar), vom Ersteller jederzeit widerrufbar. Mehrfach einlösbar, aber nie mehrfach von derselben Person.
4. **Nur der Ersteller lädt ein** (Version 1), per Code und an Freunde. Mitglieder können weder Codes erzeugen noch Freunde einladen. Das verhindert, dass sich eine Gruppe unbemerkt vergrössert.
5. **Die Mitgliederliste ist für alle Mitglieder sichtbar**, mit Beitrittszeit. Neue Mitglieder tragen 24 Stunden ein "neu". Der Ersteller kann Mitglieder entfernen, jeder kann jederzeit austreten.
6. **Schalter "Standort in dieser Gruppe teilen"** pro Gruppe und Mitglied (Standard an, weil der Beitritt die Zustimmung ist). Aus heisst: die anderen sehen dich nicht, du siehst sie weiterhin (wie beim Schalter pro Freund heute).
7. **Optionale Laufzeit der Gruppe:** 1 Tag, 3 Tage, 7 Tage, bis Saisonende (30. April) oder unbegrenzt. Nach Ablauf endet die Sicht sofort, die Gruppe wird nach 7 Tagen gelöscht. Vorschlag für den Standard: **7 Tage**, damit keine vergessene Gruppe ewig Standorte zeigt. Verlängern ist möglich.
8. **Blockieren gewinnt.** Hat A den Freund B blockiert, sehen sich A und B auch in einer gemeinsamen Gruppe nicht.
9. Sichtbarer Hinweis auf dem Startscreen bleibt: "Du teilst deinen Standort mit N Personen" (N zählt Freunde und Gruppenmitglieder ohne Doppelte). Tipp darauf zeigt die Namen und die Gruppen.
10. Das Konto löschen entfernt alle Mitgliedschaften. Löscht der Ersteller sein Konto, geht die Gruppe an das am längsten beigetretene Mitglied, ohne Mitglieder wird sie gelöscht.

### Datenmodell (Migration `0006_groups.sql`)

```sql
groups          (id uuid PK, name 1..30, owner_id, created_at, expires_at null = unbegrenzt)
group_members   (group_id, user_id, role 'owner'|'member', share_location bool default true,
                 announce bool default true, joined_at)       -- PK (group_id, user_id)
group_invites   (code PK 6 Zeichen, group_id, created_by, expires_at, max_uses, uses, revoked_at)
group_friend_invites (group_id, friend_id, invited_by, created_at, expires_at)   -- offene Einladungen an Freunde
group_removed   (group_id, user_id, removed_at)               -- entfernte Mitglieder kommen nicht per Code zurück
```

- Alle Tabellen mit Row Level Security. Mitglieder dürfen ihre Gruppen und deren Mitglieder lesen, geschrieben wird **nur über Funktionen** (wie bei Freunden und Meldungen). Einladungen und die Liste der Entfernten sind für Clients nicht direkt lesbar.
- Code-Format wie bei Freundes-Einladungen (6 Zeichen ohne 0/O/1/I). Codes sind über beide Tabellen eindeutig, damit ein einziges Eingabefeld "Code eingeben" beides erkennt.
- Offene Einladungen an Freunde zählen für die Grenze mit (Mitglieder plus offene Einladungen höchstens 20). Der Ersteller darf einen entfernten Freund bewusst wieder einladen, das hebt die Sperre für diese Person auf.
- Grenzen: höchstens **20 Mitglieder** je Gruppe, höchstens 5 selbst erstellte und 10 beigetretene Gruppen, höchstens 5 offene Codes je Gruppe. Fehlversuche beim Einlösen zählen in `redeem_failures` mit (gleiche Sperre wie bei Freunden: 10 pro Stunde).

### Funktionen für den Client
`create_group(name, days)`, `list_groups()`, `list_group_members(group)` (liefert Name, Farbe, Aussprache-Name, Rolle, Beitrittszeit, ob gerade sichtbar), `rename_group`, `extend_group(group, days)`, `delete_group`, `invite_friends_to_group(group, friends[])` (nur der Ersteller, nur akzeptierte, nicht blockierte Freunde), `list_group_invitations()` (meine offenen Einladungen mit Gruppenname, Einladendem, Mitgliederzahl), `accept_group_invitation(group)`, `decline_group_invitation(group)`, `cancel_group_invitation(group, friend)`, `create_group_invite(group, max_uses)`, `revoke_group_invites(group)`, `preview_group_invite(code)` (Gruppenname, Ersteller, Anzahl Mitglieder, ohne Beitritt), `join_group(code)`, `leave_group`, `remove_member(group, user)`, `set_group_sharing(group, on)`, `set_group_announce(group, on)`, `my_viewers()` (wer sieht mich gerade, für den Hinweis).

### Änderung an der Sichtbarkeit
`can_view_location(owner, session)` bekommt einen zweiten Weg: Der Leser sieht den Besitzer auch, wenn beide **Mitglied derselben aktiven Gruppe** sind, die Mitgliedschaft des Besitzers `share_location = true` hat, die Gruppe nicht abgelaufen ist und keine Blockierung zwischen den beiden besteht. Die Realtime-Filterung läuft wie bisher über diese Policy, es braucht keinen neuen Kanal. Austreten, Entfernen, Ablauf und Blockieren wirken sofort, weil die Policy bei jeder Abfrage neu auswertet.

Zusätzlich: `profiles_select` erlaubt das Lesen von Name und Farbe für Mitglieder gemeinsamer Gruppen, sonst kennt der Client die Namen der Nicht-Freunde nicht.

### Änderungen in der App
- **Daten:** `Social` bekommt `groups`, `groupMembers` und `viewers`. Die Karte arbeitet mit "sichtbaren Personen" = Freunde ∪ Gruppenmitglieder (ohne Doppelte). `FriendsOnMap.markers` nimmt diese Vereinigung statt nur der Freunde.
- **Teilen starten:** `startAutoShare` bricht heute mit "keine Freunde ausgewählt" ab. Das muss entfallen, wenn der Nutzer Gruppen hat, sonst teilt jemand ohne Freunde nie. `start_share` darf mit leerer Freundesliste laufen, wenn mindestens eine Gruppe mit `share_location = true` besteht.
- **Oberfläche:** Der Tab "Freunde" wird "Freunde & Gruppen" mit Umschalter oben. Gruppenliste (Name, Mitglieder, Ablauf), "Gruppe erstellen" (Name, Laufzeit), Gruppenseite mit Mitgliedern (Farbpunkt, Name, "zeichnet auf"), Schalter "Standort teilen" und "Ansagen", "Einladen" (Code und Link `pwdr://group/<code>` zum Teilen), "Austreten", für den Ersteller "Entfernen", "Umbenennen", "Verlängern", "Löschen". Beitritt über denselben Dialog "Code eingeben" wie bei Freunden, oder per Link.
- **Freunde hinzufügen:** Auf der Gruppenseite der Knopf "Freunde hinzufügen" öffnet die Freundesliste mit Häkchen. Wer schon Mitglied oder eingeladen ist, ist ausgegraut mit "Mitglied" bzw. "Eingeladen". Beim Erstellen einer Gruppe lassen sich Freunde gleich mit auswählen. Der Ersteller sieht offene Einladungen in der Mitgliederliste ("Eingeladen", mit "Zurückziehen").
- **Einladung annehmen:** Beim eingeladenen Freund erscheint im Tab "Freunde & Gruppen" oben eine Karte "Einladung von Anna: Wochenende Arosa, 4 Mitglieder" mit "Ansehen", "Beitreten" und "Ablehnen" (derselbe Bestätigungsdialog wie beim Code: Name, Ersteller, Anzahl, Satz zur Sichtbarkeit). Die App fragt die Einladungen wie die Freundesliste ab (alle 60 Sekunden und beim Öffnen), ein Zähler am Tab zeigt neue Einladungen. Push-Benachrichtigungen gibt es noch nicht, deshalb sagt der Ersteller dem Freund am besten kurz Bescheid.
- **Karte:** Gruppenmitglieder erscheinen wie Freunde als farbige Punkte mit Namen. Auf Wunsch ein Filter "nur diese Gruppe". Die Statuskarte zeigt Gruppen mit Mitgliederzahl und wie viele gerade sichtbar sind.
- **Ansagen:** Annäherung und Sprachausgabe gelten auch für Gruppenmitglieder, pro Gruppe abschaltbar (`announce`).
- **Deep Link:** `pwdr://group/<code>` zusätzlich zu `pwdr://join/<code>` im Manifest.

### Tests
- **Backend (Docker-Suite, neue Datei `12_groups_tests.sql`):** Mitglied sieht Mitglied nur bei aktiver Aufzeichnung und `share_location`; Nicht-Mitglied sieht nichts; Austreten, Entfernen, Ablauf, Blockieren und "Teilen aus" schneiden die Sicht sofort ab; nur der Ersteller erzeugt Codes; Code abgelaufen, widerrufen, aufgebraucht, doppelt eingelöst; entfernte Mitglieder kommen nicht zurück; Grenzen (20 Mitglieder, 5 Gruppen, 5 Codes); Sperre nach 10 Fehlversuchen; Einladung an Freunde nur durch den Ersteller, nur an akzeptierte und nicht blockierte Freunde, ohne Annahme keine Sicht in beide Richtungen, Annehmen, Ablehnen, Zurückziehen, Ablauf nach 7 Tagen, Zählung gegen die Grenze von 20, Wiedereinladen eines Entfernten; Konto löschen und Eigentümerwechsel; kein direkter Schreibzugriff auf die Tabellen; Profile von Mitgliedern lesbar, andere nicht.
- **Unit-Tests (Kotlin):** Vereinigung Freunde und Mitglieder ohne Doppelte, Zählung für den Hinweis "Du teilst mit N", Code-Normalisierung, Link-Erkennung, Texte für Ablauf und Laufzeit.
- **Emulator:** Ablauf mit zwei Konten ist im Emulator nur beschränkt möglich. Der echte Test braucht **drei Handys** (Ersteller, Mitglied, Aussenstehender), siehe unten.

### Reihenfolge
1. **Migration und Backend-Tests** (zuerst, sicherheitskritisch). Erst wenn die Docker-Suite grün ist, geht es weiter.
2. Datenschicht in `Social` und Vereinigung "sichtbare Personen" auf der Karte (Gruppenmitglieder erscheinen, noch ohne Oberfläche).
3. Oberfläche: erstellen, Mitglieder, Freunde hinzufügen und Einladungen annehmen, per Code einladen und beitreten, austreten, Einstellungen.
4. Feinheiten: Hinweis "Du teilst mit N" mit Namen, Statuskarte, Ansagen pro Gruppe, "neu"-Markierung, Verlängern.
5. Doku (README, backend/README) und Zwei- bis Drei-Handy-Test.

### Offene Entscheidungen (Vorschläge in Klammern)
| Frage | Vorschlag |
|---|---|
| Wer darf Codes erzeugen? | Nur der Ersteller. Später optional "Mitglieder dürfen einladen". |
| Standard-Laufzeit einer Gruppe | 7 Tage, verlängerbar, "unbegrenzt" wählbar. |
| Genehmigung neuer Mitglieder durch den Ersteller | Nicht in Version 1. Ersatz: kurzlebige, begrenzte Codes und sichtbare Mitgliederliste. Später als Option "Beitritt bestätigen". |
| Maximale Gruppengrösse | 20 (Mitglieder plus offene Einladungen). |
| Freunde direkt hinzufügen ohne Annahme? | Nein, immer mit Annahme. Eine Freundschaft ersetzt nicht die Zustimmung zur Gruppen-Sichtbarkeit. |
| Dürfen Mitglieder ihre eigenen Freunde einladen? | Version 1 nein (nur der Ersteller). Später optional "Mitglieder dürfen einladen", dann mit Benachrichtigung der Gruppe. |
| Dürfen Mitglieder sich auch ohne Aufzeichnung auf der Karte sehen? | Nein, wie bei Freunden nur während der Aufzeichnung. |
| Sehen Mitglieder die letzte bekannte Position nach dem Stopp? | Nein, die Live-Zeile wird gelöscht (keine Standortgeschichte). |

### Risiken
- **Weitergeleiteter Code:** wirkt stärker als bei Freunden. Gegenmittel sind Ablauf, Beitrittsgrenze, Widerruf, sichtbare Mitgliederliste und die Bestätigung vor dem Beitritt.
- **Unbemerkt geteilt:** Wer in mehreren Gruppen ist, vergisst, wer ihn sieht. Gegenmittel sind der Hinweis mit Namen und die Laufzeit.
- **Last:** 20 Mitglieder erzeugen bis zu 19 Empfänger je Positionsupdate. Das ist für die Gratis-Stufe von Supabase unkritisch, solange Gruppen klein bleiben. Beobachten, sobald mehr als eine Gruppe aktiv ist.
- **Umgehen der Freundschaft:** Gruppen schaffen einen zweiten Weg zur Sicht. Jede neue Prüfung in `can_view_location` braucht deshalb eigene Tests (siehe oben), sonst entstehen Standort-Lecks.

## 6. Datenschutz und Sicherheit

- Standort-Freigabe nur per Opt-in, pro Freund, mit Ablaufzeit ("Heute teilen")
- Freunde per Einladungscode, keine öffentliche Suche
- Backend: Row Level Security, Live-Positionen werden nach kurzer Zeit gelöscht
- Standort-Berechtigung im Hintergrund (Android 10+) braucht einen sauberen Hinweis. Für ein privates Projekt ohne Play-Store-Veröffentlichung ist das einfach, bei Veröffentlichung ist ein Review nötig.
- Hinweis in der App: Lawinendaten sind Information, kein Ersatz für eine eigene Beurteilung
- Wetter-, Schnee- und Lawinenabfragen verwenden feste Koordinaten des Skigebiets, **nicht** den Standort des Nutzers. Es geht kein Nutzerstandort an Open-Meteo oder den SLF.
- Webcams werden nur als Link geöffnet, die App überträgt dabei nichts

## 7. Roadmap

| Phase | Inhalt | Ergebnis |
|---|---|---|
| 0 | Projekt-Setup, Karte mit swisstopo, Berechtigungen | Leere App mit Karte und Standort. **Erledigt (14.11.2026):** Ein Modul `app` (weitere Module kommen mit Bedarf), Package `app.pwdr`, minSdk 29, Kotlin 2.2 (seit 15.11.2026 siehe Phase 0a), Compose, MapLibre 11.8.2 (inzwischen 13.7.1), swisstopo-Pixelkarte als Raster. Im Emulator geprüft: Karte lädt, Berechtigungsdialog, Standortpunkt. Noch nicht auf einem echten Gerät getestet. |
| 0a | Build-Werkzeug nachgezogen | **Erledigt (15.11.2026):** Gradle 9.6.0, Android-Gradle-Plugin 9.5.1 (mit eingebautem Kotlin, das Plugin `kotlin-android` entfällt), Kotlin 2.4.21, KSP 2.3.14, compileSdk 37 (targetSdk bleibt 36, damit sich das Laufzeitverhalten nicht ändert), Compose BOM 2026.09.00, Room 2.8.5, Coroutines 1.11.0, Lifecycle 2.11.0, MapLibre 13.7.1, Supabase-Client 3.9.0 mit Ktor 3.6.0. Alle 88 Unit-Tests grün, Build ohne Warnungen. Im Emulator geprüft: Karte mit Pisten und Lifte, Spur-Ansicht, Freunde-Tab. Nicht erneut geprüft: Aufzeichnung im Dienst, Sprachausgabe, Anmeldung und Realtime gegen das Projekt. |
| 1 | Tracking-Service, Room, Aktivitätserkennung, Tagesstatistik | **MVP**: ein Skitag aufzeichnen und auswerten. **Umgesetzt (14.11.2026), noch nicht im Feld getestet:** Vordergrund-Dienst (Typ Standort, braucht nur "Beim Verwenden der App", kein Hintergrund-Standort), Room (Aufzeichnung, Rohpunkte, Abschnitte), regelbasierte Erkennung `ActivityDetector` mit 11 Unit-Tests auf simulierten Strecken, Nachanalyse nach dem Stopp und Wiederherstellung nach Absturz (letzteres nicht getestet), Tabs Karte / Aufzeichnen / Statistik. Im Emulator läuft die Kette Start, Erkennen, Stopp, Speichern, Anzeige durch. Die Genauigkeit der Zahlen lässt sich im Emulator nicht prüfen (siehe Anmerkung unter der Tabelle). |
| 1a | Barometer und GPX-Export (Hilfen für die Testfahrt) | **Umgesetzt (14.11.2026), 15 Unit-Tests grün, nicht im Feld getestet:** `BarometerAltitude` fusioniert Luftdruck mit GPS-Höhe (Druck für kurzfristige Änderungen, GPS als langsamer Anker mit 300 s Zeitkonstante, kein Nachführen bei Auf- oder Abwärtsfahrt über 0,5 m/s oder bei GPS-Genauigkeit über 20 m). Ohne Drucksensor bleibt es bei GPS-Höhe. Room v2 speichert zusätzlich die rohe GPS-Höhe (Migration 1 auf 2). In der Statistik gibt es pro Tag "GPX teilen" (GPX 1.1 mit Speed, Genauigkeit und roher GPS-Höhe als Erweiterung). |
| 1b | Statistik-Screens: Tag-Ansicht mit Höhen- und Speed-Profil, Abfahrtsliste | Skitag auswertbar. **Umgesetzt (15.11.2026), 20 Unit-Tests grün:** Tagesliste, Tipp öffnet die Tagesansicht mit Kacheln, Metrik-Umschalter (Höhenmeter, Distanz, Speed, Auswahl wird gemerkt; G-Force fehlt, weil noch kein Beschleunigungssensor aufgezeichnet wird), Verlaufsgrafik als eigener Canvas-Chart (Abfahrt farbig, Lift grau, Pause gedimmt, Lücken gestaucht, Speed-Maximum markiert) und Liste mit Balken je Abfahrt. Im Emulator rendert die Ansicht, die Daten dort sind aber unbrauchbar (Höhe nur steigend, Speed springt zwischen 0 und 55 km/h), die Farbe der Abfahrten und die Distanzkurve sind deshalb nicht an realen Daten gesehen. |
| 2 | OSM-Import Pisten und Lifte, Map-Matching, Pistenname, Liftname | Detail-Statistiken. **Teilweise umgesetzt (15.11.2026), 30 Unit-Tests grün:** Download der OSM-Daten per Overpass für Lauchernalp und Adelboden-Lenk beim App-Start (nur wenn fehlend oder älter als 90 Tage, ohne Netz bleibt alles beim Alten), Speicherung in Room (Migration 2 auf 3), `RunMatcher` ordnet jede Abfahrt der Piste und jeden Lift der Bahn per Mehrheitsentscheid der Messpunkte zu (Toleranz 35 m bzw. 30 m, mindestens 40 % Treffer, gleichnamige Wege werden zusammengezählt), Namen und Schwierigkeitspunkt in der Tagesansicht, OSM-Quellenangabe. Das Matching ist gegen die echten Lauchernalp-Daten getestet (Pisten finden sich aus ihrer eigenen Geometrie wieder), nicht aber mit einer echten Fahrt. **Karte (15.11.2026):** Pisten (blau, rot, schwarz nach Schwierigkeit, grau ohne Angabe) und Lifte (gestrichelt) als GeoJSON-Ebenen ab Zoom 11, lokal aus Room. Die Ebenen aktualisieren sich, sobald ein Gebiet nachgeladen wird (im Emulator geprüft: leere Karte, nach dem Download erscheinen die Pisten ohne Neustart). Quellenangabe in der Karte ergänzt. **Gefahrene Spur (15.11.2026):** In der Tagesansicht öffnet "Spur auf Karte" den ganzen Tag, ein Tipp auf eine Abfahrt oder einen Lift nur diesen Abschnitt. Abfahrten sind nach Speed gefärbt (0 bis 50+ km/h, dunkelviolett über türkis zu gelb), Lifte grau, Pausen fehlen, dazu Legende und passender Kartenausschnitt. Die Spur wird in Stücke von mindestens 30 m gebündelt, weil MapLibre bei kleinem Zoom kürzere Stücke verwirft (Lift mit 4,5 m pro Sekunde verschwand sonst). Im Emulator an eingespielten Testdaten entlang echter Lauchernalp-Linien gesehen, nicht an einer echten Fahrt. **Noch offen:** Namen auf der Karte (braucht Schriften im Kartenstil), Skigebiete frei wählbar statt fest eingebaut, Nachberechnung alter Aufzeichnungen, Abgleich mit Richtung (Lift hoch, Piste runter). Ein Linear-Scan statt R-Tree reicht bei diesen Datenmengen (ca. 400 Linien je Gebiet). |
| 2b | Woche / Monat / Saison, Rekorde, Top-Listen, Pistenfarben-Anteil | Vollständige Statistik |
| 3 | Sprachausgabe: TTS, Audio-Focus, Queue, Speed (Intervall, Höchstwert, Abruf per Kopfhörer-Taste), Abfahrt-Ende | Speed im Kopfhörer. **Umgesetzt (15.11.2026), 52 Unit-Tests grün, nicht mit echten Kopfhörern getestet:** Warteschlange mit Priorität, Ablaufzeit, Mindestabstand 2 s und Unterbrechung durch Abruf (`SpeechQueue`); Auslöser im GPS-Takt (`AnnouncementPlanner`): Speed-Intervall (aus, 10, 20, 30, 60 s, nur in Abfahrten, nur bei Änderung um 5 km/h), neuer Tages-Höchstwert (einmal pro Abfahrt, nur mit früherem Vergleichswert), Zusammenfassung am Ende jeder Abfahrt; deutsche Texte mit Dezimalkomma; Android-TextToSpeech mit Navigations-Audio, Audio-Focus (Musik wird leiser, bei Anruf Abbruch), Kopfhörer-Erkennung (Kabel, USB, Bluetooth), Hinweis bei fehlender deutscher Stimme; Kopfhörer-Taste: einmal = aktuelle Geschwindigkeit, zweimal = Tageszusammenfassung; Einstellungsdialog über "Sprache" im Aufzeichnen-Tab mit Test-Taste. Im Emulator geprüft: TTS erzeugt die deutsche Stimme mit Audio-Focus, die Taste löst die Ansagen aus (per Tastenbefehl), Aufräumen beim Stopp. **Erkenntnis:** Android wählt eine Medien-Sitzung nur als Ziel der Kopfhörer-Taste, wenn die App Ton ausgibt. Deshalb läuft während der Aufzeichnung ein stiller Audio-Strom (abschaltbar, kostet etwas Akku, hält bei Bluetooth den Audiokanal offen). Spielt eine Musik-App, kann sie die Taste für sich beanspruchen. **Noch offen:** Freunde-Ansagen (Phase 5), Meilensteine, Zustandsansage Lift/Abfahrt, Lautstärke und Stimme wählbar, Sprechtempo, Praxistest (Kopfhörer, Anruf, gesperrtes Display, Kälte). |
| 4 | Backend (Supabase, siehe 5c): Schema und RLS, Accounts, Einladung, Freigabe-Sessions, Live-Karte | Kollegen auf der Karte. **Teilweise umgesetzt (15.11.2026):** Entschieden: Anmeldung per E-Mail-Magic-Link, Projekt legt der Nutzer selbst an (Anleitung in `backend/README.md`). Fertig und getestet: Datenbankschema mit Row Level Security und allen Funktionen (`backend/supabase/migrations/0001_social.sql`: Profile, Freundschaften, Einladungscodes mit Raten-Bremse, Freigabe-Sitzungen mit Ablauf, Live-Position als eine Zeile pro Nutzer, Blockieren, Konto löschen, Aufräumen). Die Zugriffsregeln sind in einer lokalen Postgres-Datenbank mit PostGIS (Docker) mit über 60 Prüfungen getestet (`backend/test/run-tests.sh`), vor allem, was Fremde, Blockierte und abgelaufene oder beendete Freigaben **nicht** sehen. Einladung und Einlösen sind Datenbankfunktionen statt Edge Functions (testbar, gleiche Schutzwirkung). Client-Logik fertig: Sende-Takt (3, 5, 15, 30 s) und Aktualität von Freunden (60 s grau, 5 min aus, 30 s für Ansagen) mit Unit-Tests. **App-Seite (15.11.2026) gebaut, noch nicht mit einer echten Anmeldung geprüft:** Supabase-Client 3.3.0 (neuere Versionen verlangen ein neueres Android-Build-Werkzeug), Projekt `yhvsssvenhrrumugoxke` eingetragen (der öffentliche Schlüssel darf im APK stehen). Migration 0002 ergänzt Breite und Länge als einfache Spalten in `live_locations`, lokal getestet, im Projekt noch auszuführen. Neuer Tab "Freunde": Anmeldung per E-Mail-Link (Rückkehr über `pwdr://auth`), Profil mit Name, Aussprache und Farbe, Einladung per Code oder Link `pwdr://join/CODE`, Freundesliste mit Entfernen und Blockieren, Teilen mit Dauer (1, 4, 8 h) und Schalter je Freund, Hinweis "Du teilst deinen Standort mit N Personen", Konto löschen. Tracking-Dienst sendet die Position im adaptiven Takt und beendet die Freigabe beim Stopp. Realtime-Empfang, Freunde als Punkte in Profilfarbe auf der Karte (ausgegraut nach 60 s, weg nach 5 min) mit kleiner Liste. 78 Unit-Tests grün (Takt, Aktualität, Karten-Auswahl, Sende-Entscheidung). Im Emulator gesehen: Tab und Anmeldeseite, kein Absturz. **Nicht geprüft, weil dafür ein Postfach nötig ist:** Anmeldung, Einladung zwischen zwei Konten, Teilen, Realtime auf dem Handy des Freundes, Live-Punkt auf der Karte. **Geändert (15.11.2026):** automatisches Teilen beim Aufzeichnen, Statistik-Tage löschbar. **Noch offen:** Annäherungs-Ansage (Phase 5), Hinweis auf dem Startscreen (Teilen sichtbar im Aufzeichnen-Tab), Wer sieht mich gerade (nur die Zahl, nicht die Namen), Gruppen |
| 5 | Annäherungs-Ansage (Schwellen, Hysterese, Cooldown, Höhenfilter) | Sprachausgabe bei Kollegen in der Nähe. **Umgesetzt (15.11.2026), 88 Unit-Tests grün, nicht mit echten Freunden getestet:** `ProximityAnnouncer` mit den Stufen 300, 150 und 50 m (Ansage nur beim Überschreiten von außen nach innen, engste Stufe als "Achtung" mit hoher Priorität), Hysterese (Stufe wird erst über dem 1,5-Fachen wieder frei), 60 s Wartezeit pro Freund, Höhenfilter 30 m (rohe GPS-Höhe auf beiden Seiten), Positionen über 30 s alt zählen nicht, beide im Lift gibt keine Ansage, mehrere Freunde werden gebündelt ("Marco und Lena sind in der Nähe."), Freund ohne Freigabe wird vergessen. Aussprache-Name aus dem Profil wird bevorzugt. Einstellungen: Schalter "Freunde in der Nähe" im Sprach-Dialog und "Ansage in der Nähe" pro Freund. Die Prüfung läuft bei jedem eigenen GPS-Fix im Tracking-Dienst und nutzt die per Realtime empfangenen Positionen. Getestet mit simulierten Annäherungen (auch Schwanken an der Grenze, Rückkehr nach Entfernen, Bündelung). **Noch offen:** Bewegungsrichtung ("links vor dir"), Station-Erkennung bei Zustand Lift, Schwellen einstellbar, Praxistest mit zwei Personen zu Fuss und auf der Piste (Schwellen anpassen), ob die rohe GPS-Höhe für den 30-m-Filter genau genug ist. |
| 6 | Wetter (Open-Meteo/MeteoSchweiz), Schnee (SLF IMIS), Lawinenbulletin (SLF), siehe 5d. Webcams als Linkliste, siehe 5e. Skigebiet-Profil als Seite | Skigebiet-Profil mit Wetter, Schnee, Lawinen, Webcams. **Umgesetzt (15.11.2026), 105 Unit-Tests grün:** Gebietsseite über den Knopf "Gebiet" auf der Karte, für Lauchernalp und Adelboden-Lenk. Reihenfolge: Lawinen, Wetter, Schnee, Webcams, Quellen. **Wetter:** Open-Meteo mit MeteoSchweiz-Modell, Berg und Tal als zwei Höhen, jetzt, nächste Stunden (alle 3 h), 5 Tage mit Schnee und Böen, Nullgradgrenze. **Schnee:** IMIS-Stationen mit Schneehöhe, Neuschnee 24 h (Median-Glättung, Sprünge über 30 cm gelten als Messfehler und ergeben keinen Wert, Rückgang ergibt 0), Kurve der letzten 24 h, Prognose-Neuschnee getrennt als "Modell, keine Messung". **Lawinen:** Modell nach der offiziellen Beschreibung (Version 4), Stufe mit Ziffer und Farbe nach Standard, Höhenlage, Exposition, Probleme, Tendenz, ausführliche Texte. Ohne Bulletin steht "Zurzeit gibt der SLF kein Bulletin aus" mit dem Hinweis, dass das nicht Entwarnung heisst. Pflichthinweis und Link zum offiziellen Bulletin immer sichtbar. **Webcams:** kuratierte Links im Custom Tab, mit Prüfdatum. Offizielle Seite für Adelboden-Lenk gefunden (`adelboden-lenk.ch/de/Service/Webcams`). Zwischenspeicher mit Altersangabe ("Stand vor 3 min", bei fehlendem Netz "gespeichert"), Abfrage-Takt Wetter 60 min, Schnee und Bulletin 30 min. Mit echten Live-Daten im Emulator gesehen. **Erkenntnisse:** Die IMIS-Schnittstelle liefert nur die letzten 24 Stunden (47 Halbstundenwerte) und ignoriert Zeitparameter, eine 7-Tage-Kurve müsste die App selbst sammeln. Die Einheit von HS ist cm (46 cm bei Gandegg im November plausibel). **Nicht geprüft:** das Bulletin mit echten Daten (im November leer), die Zuordnung zur Region (läuft über Namensteile "Lötschental", "Adelboden", "Lenk", Kennungen folgen mit dem ersten Bulletin), Gandegg-Einzelkamera (Link der Plan-Recherche führte auf die Übersicht, deshalb Übersicht verlinkt). **Umgesetzt (16.11.2026): Kartenebene Lawinenregionen.** Das GeoJSON des SLF-Bulletins (`aws.slf.ch/api/bulletin/caaml/de/geojson`, 149 Regionen, rund 150 KB gzip) enthält Polygone und die Farbe der Gefahrenstufe je Region. Schalter "Lawinenregionen (SLF)" unter "Ansicht", standardmässig aus, lädt erst beim Einschalten. Tipp auf eine Region zeigt Name, höchste Stufe, Gültigkeit und den Link zum offiziellen Bulletin. Regionen ohne Bulletin bleiben ohne Fläche. Mit echten Daten im Emulator gesehen (Leukerbad - Lötschental Stufe 3). Nur Übersicht, ersetzt das Bulletin nicht. **Umgesetzt (16.11.2026): Offline-Karte.** Knopf "Offline" auf der Karte: speichert den sichtbaren Ausschnitt in der gewählten Ansicht (Karte, Luftbild oder grau, optional mit Hangneigung) über die Offline-Regionen von MapLibre, in drei Stufen (Übersicht bis Zoom 14, Detail 15, Sehr genau 16), mit Grössenschätzung, Fortschritt, Fortsetzen und Löschen. Höchstens 6000 Kacheln je Quelle und Download. Pisten und Lifte liegen schon lokal. **Erkenntnisse:** Der Offline-Download holt den Stil nur über http(s), `asset://` und `file://` starten ihn nicht (blieb bei 0 %). Gelöst mit einem winzigen Server auf 127.0.0.1:38641 in der App (nur die sechs Stil-Dateien aus `assets/offline`, Klartext nur für 127.0.0.1 erlaubt). Die Region-Objekte müssen festgehalten werden, sonst bricht der Download ab. MapLibre lädt für 256-px-Kacheln eine Stufe feiner als die Kartenstufe, die Schätzung rechnet damit (132 geschätzte Kacheln = 132 geladene). Gemessen: ca. 23 KB je Luftbild-Kachel. Mit abgeschaltetem Netz im Emulator geprüft (nach Entfernen aller anderen Kacheln erscheint der geladene Ausschnitt). Nicht geprüft: Karte und grau (Grösse nur geschätzt), grosse Downloads, Verhalten bei Verbindungsabbruch, Fair-Use-Grenzen der swisstopo bei vielen Nutzern. **Noch offen:** Webcam-Symbole auf der Karte, "Link funktioniert nicht"-Meldung, 7-Tage-Schneekurve, Powder-Alert, Ansage bei Aufzeichnungsstart. |
| 7 | Community-Meldungen (inkl. "Hier ist viel los") | Meldungen. **Umgesetzt (15.11.2026), Tests grün, nicht mit echten Nutzern getestet:** Migration `0004_reports.sql` (Tabellen `reports`, `report_votes` ohne Direktzugriff, Funktionen `post_report`, `list_reports`, `vote_report`, `delete_report`). Typen Andrang (2 h), Pistenzustand (4 h), Neuschnee (6 h), Warnung (4 h), Sonstiges (2 h). Höchstens 10 Meldungen pro Stunde, Text bis 140 Zeichen, Umkreis 30 km, Verfasser wird nicht angezeigt. Daumen hoch/runter (nicht für die eigene Meldung), ab 3 Widersprüchen und mehr als Bestätigungen ausgeblendet, eigene Meldungen löschbar, Aufräumen einen Tag nach Ablauf. In der App: Knopf "Melden" auf der Karte (am eigenen Standort, nur angemeldet), Punkte in Typfarbe mit dunklem Rand, Tipp öffnet Details. Foto und Melden-Knopf gegen Missbrauch fehlen (Widerspruch ersetzt ihn). **Erweitert (15.11.2026):** Typ Après-Ski (6 h, Migration `0005_report_apres.sql`) mit eigenem Kartensymbol "Kaffee mit Sahne" statt Punkt. Kartenansicht über den Knopf "Ansicht": Karte, Luftbild (SWISSIMAGE), Karte grau und die Ebene Hangneigung ab 30° (swisstopo, ausdrücklich keine Lawinenbeurteilung), Auswahl wird gemerkt. Umgeschaltet wird nur die Sichtbarkeit der Ebenen, Pisten, Freunde und Meldungen bleiben. **Datenbank:** `0004` im SQL Editor ausführen. |
| 8 | G-Kräfte, Feinschliff, Akku-Optimierung, optional AR | **G-Force Grundlage umgesetzt (15.11.2026), 141 Unit-Tests grün, nur im Emulator mit simuliertem Sensor geprüft:** Der Beschleunigungssensor läuft während der Aufzeichnung (`SENSOR_DELAY_GAME`, ca. 50 Hz). Pro GPS-Punkt (ca. 1 s) werden nur Höchstwert und Mittel gespeichert, keine Rohdaten (`GForceTracker`): Betrag der gesamten Beschleunigung inklusive Schwerkraft durch 9,80665, über 5 Werte geglättet, damit Stösse nicht als Höchstwert zählen, über 10 g gelten als Messfehler. Datenbank Version 4 (Migration 3 auf 4): `gMax`, `gMean` je Punkt, `maxG`, `avgG` je Abschnitt. Anzeige: vierte Metrik "G-Force" in der Tagesansicht (Verlauf mit Höchstwert, Wert pro Abfahrt, Kachel "Max. G"), mit Hinweis "Richtwert". **Ergänzt (15.11.2026), 156 Unit-Tests grün, im Emulator mit Testdaten geprüft:** Einstellung "G-Force" (Knopf auf dem Aufzeichnen-Tab und in der Tagesansicht): Sensor ein oder aus und Trageposition Hosentasche, Brust oder Rucksack. Die Position wählt die Glättung (7, 5 oder 9 Sensorwerte), sie rechnet die Werte nicht um. Stärkste Kurve: violetter Punkt auf der Spurkarte, Zeile mit Piste und Uhrzeit in der Tagesansicht, nur ab 1,15 g und nur auf Abfahrten. G im Live-Bildschirm (jetzt und Maximum). Statistik: Max. G pro Tag, Karte mit Woche, Monat und Saison (ab 1. Oktober) mit Tagen, Abfahrten, Höhenmetern, Distanz, Vmax, Max. G und Ø G. Metrikfarben in allen Diagrammen: Eisblau, Türkis, Orange, Violett. Akku: Stand bei Start und Stopp wird je Aufzeichnung gespeichert und mit G-Force-Einstellung angezeigt (Datenbank Version 5), damit sich Fahrten mit und ohne Sensor vergleichen lassen. **Noch offen:** Filter und Schwellen mit echten Fahrten abstimmen, Akkuverbrauch auf einem echten Handy auswerten (im Emulator nicht aussagekräftig), Wochen- und Monatsdiagramme (nur die Zusammenfassung gibt es), Feinschliff, Akku-Optimierung und AR. |
| 9 | Gruppen: erstellen, Freunde direkt einladen (mit Annahme) oder per Code oder Link, alle Mitglieder sehen sich (siehe 5f) | **Geplant (20.11.2026), Schritte 1 bis 3 (Backend, Datenschicht, Oberfläche) umgesetzt, nicht mit echten Konten getestet.** Reihenfolge: Migration `0006_groups.sql` mit Backend-Tests, Datenschicht und Karte, Oberfläche, Feinheiten, Test mit drei Handys. |
| später | Powder-Alert (Push), Wechsel auf Vorschaubilder bei Webcams | |

**Anmerkung zur Emulator-Genauigkeit (Phase 1):** Der Emulator liefert unregelmäßige Fixes (im Test 182 von 245 Punkten) und eine geglättete, verzögerte Höhe (252 m Abstieg kamen als etwa 60 m an). Die angezeigten Zahlen (22 m Abstieg, 412 m Distanz) sind deshalb kein Mass für die Erkennung. Belastbar wird das erst mit einer Testfahrt mit echtem GPS. Die Rohdaten lassen sich über den GPX-Export holen und danach als Replay oder in Unit-Tests wiederverwenden. Offen: die Barometer-Fusion wurde nur mit simulierten Druckwerten getestet. Ob der Drucksensor des Testgeräts brauchbar ist (Offset, Rauschen), zeigt erst die Testfahrt (rohe GPS-Höhe und fusionierte Höhe stehen beide im GPX).

Phase 1 und 3 sind der schnellste Weg zu etwas Nützlichem. Die Aktivitätserkennung ist der Teil mit dem größten Risiko und braucht echte Testfahrten.

## 8. Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Akkuverbrauch bei GPS im Hintergrund | Adaptive Abtastrate, früh messen |
| Erkennung fehleranfällig (Gondel hält an, Pause im Lift) | Hysterese und Testdaten sammeln, Rohdaten als GPX exportierbar |
| OSM-Pistenqualität schwankt je nach Gebiet | Mit 1–2 Heimat-Skigebieten starten und prüfen |
| Kein Mobilnetz am Berg | Offline-first, Sync später |
| Kein Andrang- und Liftstatus-Feature | Bewusst gestrichen, nur freiwillige Meldungen |
| Bluetooth-Kopfhörer, Audio-Konflikte | Audio-Focus testen |

## 9. Offene Fragen

- Testgebiete sind entschieden: Lauchernalp und Adelboden-Lenk. Offen: die offizielle Webcam-Quelle für Adelboden-Lenk.
- Wie viele Personen nutzen es (Backend-Größe, Freundesgruppe)?
- Wie kommt die App zu den Freunden (APK direkt, interner Test-Track im Play Store)? Das beeinflusst die Hintergrund-Standort-Berechtigung (siehe 6).
- Soll die Karte komplett offline nutzbar sein oder nur gecachte Gebiete?
- Soll Sprachausgabe Deutsch und/oder Schweizerdeutsch-Dialekt können? (Android-TTS: Hochdeutsch; App-Sprache ist nur Deutsch)
- Logo: Entwurf v1 liegt vor (Skifahrer in einer Schneewelle vor Berggipfeln, Dunkelblau und Cyan, App-Icon als abgerundetes Quadrat). Der Schriftzug heißt künftig PWDR. Anpassungen siehe 5a, Abschnitt "Logo und App-Icon".
- Technischer Name: Package-ID z. B. `ch.pwdr.app` (ohne eigene Domain besser `app.pwdr`) und Projektordner, der noch `Ski360` heißt.

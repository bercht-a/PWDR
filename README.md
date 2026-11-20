# PWDR

Private Ski-App für die Schweiz (Android). Der Name ist die Kurzform von "Powder".

PWDR zeichnet einen Skitag auf, erkennt Lift, Abfahrt und Pause, wertet jede Abfahrt aus und zeigt sie mit Pistenname, Speed und
Höhenmetern. Dazu kommen Sprachausgabe im Kopfhörer, Freunde live auf der Karte sowie Wetter, Schnee, Lawinenbulletin und Webcams
für das Skigebiet. Es ist ein Privatprojekt ohne Skipass-Anbindung. Fahrten bleiben auf dem Handy.

**Stand:** Alpha `0.1.0-alpha.8`. Getestet im Emulator und mit simulierten Strecken, **noch nicht auf einer echten Skifahrt**.
Die Erkennung von Lift und Abfahrt ist der Teil mit dem größten Risiko und braucht echte Testfahrten.

## Funktionen

| Bereich | Stand |
|---|---|
| Aufzeichnen | Vordergrund-Dienst, Erkennung Lift, Abfahrt, Pause, Barometer-Höhe, große Anzeige für Handschuhe, Stopp per langem Druck |
| Statistik | Zusammenfassung für Woche, Monat und Saison mit Balkengrafik (je Tag bzw. Monat, umschaltbar auf Höhenmeter, Distanz, Speed, G-Force) und Blättern zu früheren Zeiträumen, Tagesliste, Tagesansicht mit Verlaufsgrafik (Höhenmeter, Distanz, Speed, G-Force als Richtwert), Abfahrtsliste, stärkste Kurve auf der Karte, GPX-Export, Tag löschen, einzelne Abfahrt löschen (lange drücken) |
| Karte | swisstopo-Karte (umschaltbar auf Luftbild und grau, dazu Hangneigung ab 30° und die Lawinenregionen des SLF mit Gefahrenstufe), Offline-Karte (aktuellen Ausschnitt in der gewählten Ansicht laden, Pisten sind ohnehin lokal), Pisten und Lifte aus OpenStreetMap, gefahrene Spur nach Speed gefärbt, Freunde als Punkte |
| Pisten und Lifte | Zuordnung der Abfahrten zu Piste und Lift (Lauchernalp und Adelboden-Lenk) |
| Sprachausgabe | Speed im Intervall, neuer Höchstwert, Ende der Abfahrt, Kopfhörer-Taste, Ansage bei sich nähernden Freunden |
| Freunde und Gruppen | Anmeldung per E-Mail-Link, Einladung per Code oder Link. **Gruppen:** benannte Gruppen mit Laufzeit, in denen sich alle Mitglieder auf der Karte sehen, solange sie aufzeichnen. Einladen per Code oder Link, oder bestehende Freunde direkt (sie müssen annehmen). Vor dem Beitritt steht ein Hinweis zur Sichtbarkeit. Braucht die Backend-Datei `0006_groups.sql`. Freunde: Die Verbindung gilt für beide, jeder kann sie jederzeit entfernen. Beim Aufzeichnen wird der Standort automatisch mit Freunden geteilt (abschaltbar, pro Freund einstellbar) und sie erscheinen auf der Karte |
| Meldungen | Knopf "Melden" auf der Karte: Andrang, Pistenzustand, Neuschnee, Warnung, Après-Ski (Kaffee-Symbol) mit Ablaufzeit. Andere bestätigen oder widersprechen, ohne Namen |
| Gebiet | Lawinenbulletin (SLF), Wetter (MeteoSchweiz über Open-Meteo), Schneehöhen (SLF IMIS), Webcam-Links |

Noch nicht gebaut: Webcam-Symbole auf der Karte,
weitere Skigebiete. Alle Entscheidungen, Recherchen und die Roadmap stehen in [PLAN.md](PLAN.md).

## Alpha-Version installieren

1. Die Datei `pwdr-0.1.0-alpha.8.apk` aufs Handy bringen (Messenger, Cloud-Speicher oder USB).
2. Öffnen und die Installation erlauben. Android fragt einmal, ob Apps aus dieser Quelle installiert werden dürfen.
3. Beim ersten Aufzeichnen **Standort** ("Beim Verwenden der App") und **Benachrichtigungen** erlauben.
4. Für lange Aufzeichnungen die Akku-Optimierung für PWDR ausschalten. Manche Hersteller (Xiaomi, Samsung, Huawei) beenden sonst
   Hintergrunddienste.
5. Beim ersten Start lädt die App einmalig die Pisten und Lifte (ca. eine Minute, danach offline). Dafür braucht es Netz.

Die Version steht unten im Tab "Freunde". Bitte bei Rückmeldungen angeben. Hilfreich ist der GPX-Export (Statistik > Tag > GPX teilen):
Mit der Datei lassen sich Fehler bei der Erkennung nachvollziehen.

Updates lassen sich über die bestehende Installation einspielen, solange sie mit demselben Schlüssel signiert sind.

## Entwickeln

Voraussetzungen: JDK 17 oder neuer, Android SDK mit Plattform 37 (Gradle lädt sie bei akzeptierter Lizenz selbst), Internet.

```
./gradlew testDebugUnitTest     # Unit-Tests
./gradlew assembleDebug         # Debug-APK: app/build/outputs/apk/debug/
./gradlew assembleRelease       # signierte Alpha-APK: app/build/outputs/apk/release/
```

Aktuell: Gradle 9.6.0, Android-Gradle-Plugin 9.5.1, Kotlin 2.4, Jetpack Compose, minSdk 29, compileSdk 37, targetSdk 36.

### Signieren

Die Release-APK wird mit einem eigenen Schlüssel signiert. Der Schlüssel liegt **nicht** im Repository. Zum Einrichten:

1. Schlüssel erzeugen: `keytool -genkeypair -keystore <Pfad>/pwdr-alpha.jks -alias pwdr-alpha -keyalg RSA -keysize 4096 -validity 10000`
2. Im Projektordner eine Datei `keystore.properties` anlegen (ist in `.gitignore`):

   ```
   storeFile=C:/Pfad/zu/pwdr-alpha.jks
   storePassword=...
   keyAlias=pwdr-alpha
   keyPassword=...
   ```

Ohne diese Datei baut `assembleRelease` eine unsignierte APK. **Den Schlüssel sichern:** Geht er verloren, lässt sich eine installierte
Alpha nicht mehr aktualisieren, man müsste sie deinstallieren (die Fahrten gehen dabei verloren).

## Automatisch bauen und veröffentlichen (CI/CD)

GitHub Actions baut und veröffentlicht das Projekt. Die Workflows liegen in `.github/workflows/`.

| Workflow | Wann | Was |
|---|---|---|
| `ci.yml` | Push auf `main`, Pull Request | Unit-Tests, Debug-APK (als Artefakt, 14 Tage), Backend-Zugriffsregeln in Postgres |
| `release.yml` | Tag `v*`, z. B. `v0.1.0-alpha.2` | Tests, signierte Release-APK, GitHub-Release mit APK und Prüfsumme |

**Release veröffentlichen:**

```
git tag -a v0.1.0-alpha.2 -m "PWDR 0.1.0-alpha.2"
git push origin main
git push origin v0.1.0-alpha.2
```

Der Workflow nimmt Version und Versionscode aus dem Tag und dem Lauf (`100 + Laufnummer`, damit Updates immer eine höhere Nummer haben).
Tags mit `-alpha`, `-beta` oder `-rc` werden als Vorabversion markiert. Die APK hängt am Release (Reiter "Releases" des Repositories),
daneben liegt die Datei `.sha256` zur Prüfung.

**Einmalig einrichten:** Im Repository unter *Settings > Secrets and variables > Actions* vier Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_BASE64` | Die Schlüsseldatei als Text. In PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.pwdr\pwdr-alpha.jks")) \| Set-Clipboard`, dann einfügen |
| `KEYSTORE_PASSWORD` | `storePassword` aus `keystore.properties` |
| `KEY_ALIAS` | `keyAlias` aus `keystore.properties` (`pwdr-alpha`) |
| `KEY_PASSWORD` | `keyPassword` aus `keystore.properties` |

Die Secrets sind für das Repository verschlüsselt und erscheinen nicht in den Protokollen. Fehlt eines, bricht der Release-Workflow mit einer
klaren Meldung ab. Der Schlüssel wird nach dem Lauf vom Runner gelöscht. Wer Schreibrechte auf das Repository hat, kann Workflows ändern und
so an den Schlüssel kommen: das Repository privat halten und nur Vertrauten Schreibzugriff geben.

## Backend (Freunde und Live-Standort)

Supabase (Postgres, Anmeldung, Realtime). Schema, Zugriffsregeln und Tests liegen in [backend/](backend/), die Einrichtung steht in
[backend/README.md](backend/README.md). Die Zugriffsregeln lassen sich lokal ohne Supabase-Konto testen (Docker nötig):

```
backend/test/run-tests.sh
```

Die Adresse des Projekts und der öffentliche Schlüssel stehen in `app/src/main/java/app/pwdr/social/Backend.kt`. Der öffentliche
Schlüssel ist für Apps gedacht, den Schutz übernehmen die Zugriffsregeln. Der geheime Schlüssel (`service_role`) gehört nie in die App.

## Aufbau

```
app/src/main/java/app/pwdr/
  tracking/   Aufzeichnung, Erkennung, Auswertung, Barometer, GPX
  data/       Datenbank (Room), Repositories
  osm/        Pisten und Lifte aus OpenStreetMap (Overpass)
  map/        Karte (MapLibre), Ebenen für Pisten, Lifte, Spur, Freunde
  voice/      Sprachausgabe, Warteschlange, Annäherung an Freunde
  social/     Konto, Freunde, Standort teilen (Supabase)
  resort/     Gebiet: Lawinen, Wetter, Schnee, Webcams
  ui/         Bildschirme
backend/      Supabase-Schema und Tests
PLAN.md       Plan, Entscheidungen, Recherchen
```

## Datenquellen und Lizenzen

- Karten: swisstopo (Pixelkarte). Pisten und Lifte: © OpenStreetMap-Mitwirkende (ODbL).
- Lawinenbulletin und Schneemessungen: SLF (CC BY 4.0).
- Wetter: MeteoSchweiz über Open-Meteo.com (CC BY 4.0, nur nicht-kommerzielle Nutzung).
- Webcams gehören den Betreibern und werden nur verlinkt.

## Hinweise

- **Lawinen:** Das Bulletin ersetzt keine eigene Beurteilung vor Ort. PWDR ist keine Lawinenwarn-App und gibt keine Empfehlung für Touren.
  Ohne Bulletin steht in der App "kein Bulletin", nie "keine Gefahr".
- **Datenschutz:** Fahrten, Punkte und Statistik bleiben auf dem Gerät. Nur wer aktiv teilt, sendet seine aktuelle Position an
  ausgewählte Freunde. Es gibt keine Standortgeschichte auf dem Server, die Freigabe endet mit der Aufzeichnung oder der Ablaufzeit.
- **Zulassung:** Privatprojekt, nicht im Play Store.

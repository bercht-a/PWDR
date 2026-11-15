# PWDR Backend (Supabase)

Backend für Freunde und Live-Standort, siehe PLAN.md Abschnitt 5c. Die privaten Tracks bleiben auf dem Gerät.

## Projekt anlegen (einmalig, ca. 10 Minuten)

1. Auf supabase.com ein Konto anlegen und ein neues Projekt erstellen.
   - Region: **Frankfurt (eu-central-1)** oder Zürich, falls angeboten.
   - Datenbank-Passwort sicher aufbewahren, die App braucht es nicht.
2. **Datenbank einrichten:** Im Projekt unter *SQL Editor* den Inhalt von
   `backend/supabase/migrations/0001_social.sql` einfügen und ausführen. Es darf kein Fehler erscheinen.
   Danach unter *Database > Extensions* prüfen, dass `postgis`, `pgcrypto` und `pg_cron` aktiv sind
   (`pg_cron` ggf. einschalten und das Skript ein zweites Mal ausführen lassen, oder die letzte Anweisung
   `cron.schedule(...)` einzeln ausführen).
3. **Anmeldung per E-Mail-Link:** *Authentication > Providers > Email* einschalten, "Confirm email" an lassen.
   Unter *Authentication > URL Configuration* bei "Redirect URLs" eintragen: `pwdr://auth`
   (Site URL kann dieselbe Adresse sein). Das ist die Adresse, über die der Link aus der E-Mail in die App zurückführt.
4. **Realtime:** *Database > Replication* prüfen, dass die Tabelle `live_locations` in der Publikation
   `supabase_realtime` steht (das Skript trägt sie ein).
5. Unter *Project Settings > API* zwei Werte kopieren und mir geben:
   - **Project URL** (`https://xxxx.supabase.co`)
   - **anon public key** (der öffentliche Schlüssel). **Nicht** den `service_role`-Schlüssel weitergeben.

Der anon-Schlüssel ist für die App gedacht und darf im APK stehen. Den Schutz übernehmen die Zugriffsregeln.

Hinweis zum E-Mail-Versand: Der eingebaute Versand von Supabase ist stark begrenzt (wenige Mails pro Stunde),
für einen kleinen Freundeskreis reicht das zum Testen. Für den Alltag später einen eigenen SMTP-Dienst eintragen.

## Zugriffsregeln testen (lokal, ohne Projekt)

Voraussetzung: Docker läuft.

```
backend/test/run-tests.sh
```

Das Skript startet eine Postgres-Datenbank mit PostGIS, bildet die Supabase-Umgebung nach (Rollen, `auth.users`,
`auth.uid()`), spielt die Migration ein und führt `10_rls_tests.sql` aus. Am Ende steht `ALLE TESTS BESTANDEN`.
Die Tests prüfen vor allem, was **nicht** sichtbar sein darf: Fremde, Blockierte, abgelaufene und beendete Freigaben.

## Aufbau

| Datei | Inhalt |
|---|---|
| `supabase/migrations/0001_social.sql` | Tabellen, Zugriffsregeln (RLS), Funktionen, Aufräumen |
| `test/00_supabase_stubs.sql` | Nachbau von Rollen und `auth.*` für den lokalen Test |
| `test/10_rls_tests.sql` | Tests der Zugriffsregeln und Funktionen |
| `test/run-tests.sh` | Startet Docker-Postgres und führt alles aus |

## Funktionen (Aufruf aus der App per RPC)

| Funktion | Zweck |
|---|---|
| `create_invite()` | Einladungscode (6 Zeichen, 24 h gültig, einmal einlösbar) |
| `redeem_invite(code)` | Code einlösen, gegenseitige Freundschaft. Ungültig oder gesperrt: `null`. Nach 10 Fehlversuchen pro Stunde gesperrt |
| `list_friends()` | Freunde und selbst Blockierte mit Profil |
| `remove_friend(id)`, `block_friend(id)`, `unblock_friend(id)` | Beenden dabei sofort alle Freigaben |
| `start_share(stunden, freunde[])` | Teilen starten (1 bis 12 h), nur akzeptierte Freunde werden freigegeben |
| `set_share_target(id, an)` | Einzelnen Freund während des Teilens ein- oder ausschalten |
| `stop_share()` | Teilen beenden, Live-Zeile löschen |
| `update_location(...)` | Eigene Live-Zeile überschreiben (nur mit aktiver Sitzung) |
| `delete_account()` | Konto mit allen Daten löschen |

Lesen läuft direkt über die Tabellen mit Row Level Security (`profiles`, `friendships`, `share_sessions`,
`share_targets`, `live_locations`). Die Positionen der Freunde kommen per Realtime (Postgres Changes) auf `live_locations`.

## Bewusste Abweichung vom Plan

Der Plan nannte Edge Functions für Einladung und Einlösen. Umgesetzt sind sie als Datenbankfunktionen (RPC).
Sie sind damit in einer lokalen Postgres-Datenbank testbar, brauchen keine zusätzliche Laufzeit und haben
dieselbe Schutzwirkung, weil Clients nicht direkt in `friendships` oder `invites` schreiben dürfen.

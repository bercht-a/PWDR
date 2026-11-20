# PWDR Backend (Supabase)

Backend für Freunde und Live-Standort, siehe PLAN.md Abschnitt 5c. Die privaten Tracks bleiben auf dem Gerät.

## Projekt anlegen (einmalig, ca. 10 Minuten)

1. Auf supabase.com ein Konto anlegen und ein neues Projekt erstellen.
   - Region: **Frankfurt (eu-central-1)** oder Zürich, falls angeboten.
   - Datenbank-Passwort sicher aufbewahren, die App braucht es nicht.
2. **Datenbank einrichten:** Im Projekt unter *SQL Editor* nacheinander die drei Dateien aus `backend/supabase/migrations/`
   einfügen und je mit *Run* ausführen. Es darf kein Fehler erscheinen.
   - `0001_social.sql`: Tabellen, Zugriffsregeln, Funktionen
   - `0002_live_latlon.sql`: Breite und Länge als einfache Spalten
   - `0003_search_path_extensions.sql`: behebt den Fehler "function gen_random_bytes(integer) does not exist" beim
     Erzeugen eines Einladungscodes. **Wer 0001 und 0002 schon eingespielt hat, führt nur 0003 aus** (wiederholbar).
   - `0004_reports.sql`: Community-Meldungen (Phase 7)
   - `0005_report_apres.sql`: Meldungstyp Après-Ski. **Wer 0004 schon eingespielt hat, führt nur 0005 aus** (wiederholbar).
   - `0006_groups.sql`: Gruppen (Tabellen, Funktionen, erweiterte Sichtbarkeit des Live-Standorts). Die App nutzt sie noch nicht,
     die Migration lässt sich aber schon einspielen. Sie ändert `can_view_location`, `delete_account` und `cleanup_expired`.

   Danach unter *Database > Extensions* prüfen, dass `postgis`, `pgcrypto` und `pg_cron` aktiv sind
   (`pg_cron` ggf. einschalten und die letzte Anweisung von 0001 mit `cron.schedule(...)` einzeln ausführen).
3. **Anmeldung per E-Mail-Link:** *Authentication > Providers > Email* einschalten, "Confirm email" an lassen.
   Unter *Authentication > URL Configuration* bei "Redirect URLs" eintragen: `pwdr://auth`
   (Site URL kann dieselbe Adresse sein). Das ist die Adresse, über die der Link aus der E-Mail in die App zurückführt.
4. **Realtime:** *Database > Replication* prüfen, dass die Tabelle `live_locations` in der Publikation
   `supabase_realtime` steht (das Skript trägt sie ein).
5. Unter *Project Settings > API* zwei Werte kopieren und mir geben:
   - **Project URL** (`https://xxxx.supabase.co`)
   - **anon public key** (der öffentliche Schlüssel). **Nicht** den `service_role`-Schlüssel weitergeben.

Der anon-Schlüssel ist für die App gedacht und darf im APK stehen. Den Schutz übernehmen die Zugriffsregeln.

**E-Mail-Vorlagen (optional):** In `backend/supabase/email/` liegen zwei Vorlagen im PWDR-Stil (dunkel, Wortmarke, grosser Knopf):
`magic_link.html` (Anmelden) und `confirm_signup.html` (erste Anmeldung, weil "Confirm email" an ist). Einfügen unter
*Authentication > Emails* beim jeweiligen Eintrag, Betreff steht im Kommentar am Dateianfang. Der Platzhalter
`{{ .ConfirmationURL }}` muss bleiben, sonst führt der Link nicht in die App zurück. Der Absender bleibt "Supabase Auth",
bis unter *Authentication > SMTP Settings* ein eigener Mail-Dienst eingetragen ist.

Hinweis zum E-Mail-Versand: Der eingebaute Versand von Supabase ist stark begrenzt (wenige Mails pro Stunde),
für einen kleinen Freundeskreis reicht das zum Testen. Für den Alltag später einen eigenen SMTP-Dienst eintragen.

## Zugriffsregeln testen (lokal, ohne Projekt)

Voraussetzung: Docker läuft.

```
backend/test/run-tests.sh
```

Das Skript startet eine Postgres-Datenbank mit PostGIS, bildet die Supabase-Umgebung nach (Rollen, `auth.users`,
`auth.uid()`, Erweiterungen im Schema `extensions`), spielt die Migration ein und führt `10_rls_tests.sql`, `11_reports_tests.sql` und `12_groups_tests.sql` aus. Am Ende steht `ALLE TESTS BESTANDEN`.
Die Tests prüfen vor allem, was **nicht** sichtbar sein darf: Fremde, Blockierte, abgelaufene und beendete Freigaben.

## Aufbau

| Datei | Inhalt |
|---|---|
| `supabase/migrations/0001_social.sql` | Tabellen, Zugriffsregeln (RLS), Funktionen, Aufräumen |
| `test/00_supabase_stubs.sql` | Nachbau von Rollen und `auth.*` für den lokalen Test |
| `test/10_rls_tests.sql` | Tests der Zugriffsregeln und Funktionen |
| `test/11_reports_tests.sql` | Tests der Community-Meldungen |
| `test/12_groups_tests.sql` | Tests der Gruppen (Sichtbarkeit, Einladungen, Grenzen, Eigentümerwechsel) |
| `supabase/migrations/0006_groups.sql` | Gruppen: Tabellen, Funktionen, Sichtbarkeit über gemeinsame Gruppen |
| `supabase/migrations/0004_reports.sql` | Meldungen: Tabellen, Funktionen, Aufräumen |
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
| `post_report(typ, lat, lon, text)` | Meldung erstellen (höchstens 10 pro Stunde, Ablaufzeit nach Typ) |
| `list_reports(lat, lon, radius_m)` | Meldungen im Umkreis, ohne abgelaufene und ohne stark widersprochene |
| `vote_report(id, stimme)` | 1, -1 oder 0 (zurücknehmen), nicht für die eigene Meldung |
| `delete_report(id)` | Eigene Meldung löschen |
| `delete_account()` | Konto mit allen Daten löschen (auch Meldungen und Stimmen). Gruppen gehen an das nächste Mitglied |
| `create_group(name, tage)` | Gruppe erstellen (Name 1 bis 30 Zeichen, Laufzeit 1 bis 366 Tage, `null` = unbegrenzt, Standard 7). Höchstens 5 eigene |
| `list_groups()`, `list_group_members(gruppe)`, `list_group_pending(gruppe)` | Meine Gruppen, deren Mitglieder (nur für Mitglieder), offene Einladungen (nur Ersteller) |
| `rename_group`, `extend_group(gruppe, tage)`, `delete_group`, `remove_member`, `leave_group` | Verwalten (Ersteller) und Austreten. Der Ersteller kann nicht allein austreten, die Gruppe geht dann an das nächste Mitglied |
| `set_group_sharing(gruppe, an)`, `set_group_announce(gruppe, an)` | Eigenen Standort in der Gruppe teilen, Ansagen für die Gruppe |
| `create_group_invite(gruppe, max)`, `revoke_group_invites(gruppe)` | Code (6 Zeichen, 24 h, 1 bis 20 Beitritte, höchstens 5 offene), nur Ersteller |
| `code_kind(code)`, `preview_group_invite(code)`, `join_group(code)` | Code erkennen (`friend`, `group`, `null`), Vorschau ohne Beitritt, beitreten. Fehlversuche zählen gemeinsam mit den Freundes-Codes |
| `invite_friends_to_group(gruppe, freunde[])` | Bestehende Freunde einladen (nur Ersteller). Der Freund muss annehmen, 7 Tage gültig |
| `list_group_invitations()`, `accept_group_invitation`, `decline_group_invitation`, `cancel_group_invitation` | Einladungen an mich, annehmen, ablehnen, zurückziehen |
| `my_viewers()` | Wer sieht mich gerade (Freunde und Gruppenmitglieder, ohne Doppelte) |

Lesen läuft direkt über die Tabellen mit Row Level Security (`profiles`, `friendships`, `share_sessions`,
`share_targets`, `live_locations`). Die Positionen der Freunde kommen per Realtime (Postgres Changes) auf `live_locations`.

## Bewusste Abweichung vom Plan

Der Plan nannte Edge Functions für Einladung und Einlösen. Umgesetzt sind sie als Datenbankfunktionen (RPC).
Sie sind damit in einer lokalen Postgres-Datenbank testbar, brauchen keine zusätzliche Laufzeit und haben
dieselbe Schutzwirkung, weil Clients nicht direkt in `friendships` oder `invites` schreiben dürfen.

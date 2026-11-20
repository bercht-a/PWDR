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
   - `0007_approval.sql`: Freigabe neuer Konten durch einen Admin (Tabellen `approvals` und `admins`, Sperre vor jeder Anfrage,
     Admin-Funktionen). **Nur einmal ausführen** und danach die Schritte unter "Freigabe neuer Konten und Captcha" erledigen.

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

### Eigener Mail-Versand ohne eigene Domain (Gmail, SMTP)

Nötig, sobald andere als du selbst Anmelde-Mails bekommen sollen: Der eingebaute Versand von Supabase ist knapp begrenzt
und liefert nach bisherigem Wissen nur an Adressen aus dem Supabase-Team. Mit einem Gmail-Konto geht es ohne eigene Domain.
Der Absender ist dann die Gmail-Adresse (der Anzeigename kann "PWDR" sein). Am besten ein eigenes Konto nur dafür anlegen,
zum Beispiel `pwdr.app.xyz@gmail.com`, nicht das private Hauptkonto.

1. **Zwei-Faktor-Anmeldung** im Google-Konto einschalten (*Google-Konto > Sicherheit > Bestätigung in zwei Schritten*).
   Ohne sie gibt es keine App-Passwörter.
2. **App-Passwort erzeugen:** Im Google-Konto nach "App-Passwörter" suchen (oder `myaccount.google.com/apppasswords`),
   einen Namen vergeben, zum Beispiel "PWDR Supabase", und das 16-stellige Passwort kopieren. Es wird nur einmal angezeigt.
   Es gehört nicht ins Repository und nicht in die App. Das normale Google-Passwort funktioniert hier nicht.
3. **In Supabase eintragen:** *Authentication > Emails > SMTP Settings*, "Enable custom SMTP" einschalten:

   | Feld | Wert |
   |---|---|
   | Sender email | die Gmail-Adresse |
   | Sender name | `PWDR` |
   | Host | `smtp.gmail.com` |
   | Port | `465` |
   | Username | die Gmail-Adresse |
   | Password | das App-Passwort aus Schritt 2 (ohne Leerzeichen) |

   Speichern. Ab dann laufen alle Auth-Mails (Anmelden, Bestätigen) über Gmail.
4. **Testen:** In der App mit einer fremden Adresse anmelden. Beim ersten Mal den Spam-Ordner prüfen. Kommt nichts an,
   in Supabase unter *Logs > Auth* nach SMTP-Fehlern suchen (falsches Passwort, 2FA nicht aktiv).
5. **Grenzen:** Gmail erlaubt etwa 500 Mails pro Tag, Supabase begrenzt Auth-Mails zusätzlich (*Authentication > Rate Limits*,
   Standard ist für wenige Nutzer genug). Für einen Freundeskreis reicht das. Wird PWDR grösser, lohnt sich eine eigene
   Domain mit einem Mail-Dienst wie Resend.

### Freigabe neuer Konten und Captcha (empfohlen, Migration 0007)

Die Registrierung bleibt offen, aber ein neues Konto darf erst nach deiner Freigabe in der App etwas tun. Der Server sperrt alles
ausser eigenem Profil, Statusabfrage und Konto löschen. Ein Captcha beim Anmelde-Link hält Bots ab.

1. `0007_approval.sql` im SQL Editor ausführen (nach 0006). Alle Konten, die es schon gibt, gelten als freigegeben.
   **Nur einmal ausführen**, sonst würden wartende Konten mit freigegeben.
2. **Dich zum Admin machen** (E-Mail-Adresse deines Kontos einsetzen, nicht ins Repository schreiben):
   `insert into public.admins (user_id) select id from auth.users where email = 'deine@adresse';`
3. *Authentication > Sign In / Providers*: **"Allow new users to sign up" einschalten** (die Freigabe ersetzt das Sperren).
4. *Database > Extensions*: **`pg_graphql` ausschalten.** PWDR nutzt es nicht, und GraphQL umgeht die Sperre vor jeder Anfrage
   (die Migration entzieht den Rollen dort zusätzlich den Zugang, falls das Schema vorhanden ist).
5. **Prüfen, dass die Sperre greift:** Mit einer zweiten Adresse ein neues Konto anlegen. Die App muss "Dein Konto wartet auf
   Freigabe" zeigen. Du siehst als Admin im Freunde-Tab "1 Konto wartet auf Freigabe" und kannst es freigeben oder ablehnen.
   Zeigt das neue Konto stattdessen die normale Freunde-Seite, ist die Sperre nicht aktiv: Im SQL Editor
   `select setconfig from pg_db_role_setting where setrole = 'authenticator'::regrole;` muss `pgrst.db_pre_request=public.pwdr_gate`
   enthalten, sonst `alter role authenticator set pgrst.db_pre_request = 'public.pwdr_gate'; notify pgrst, 'reload config';`.
6. **Notausstieg** (falls die Sperre alle aussperrt):
   `alter role authenticator reset pgrst.db_pre_request; notify pgrst, 'reload config';`

Wer wartet: Nur Konten, die ihre E-Mail-Adresse per Link bestätigt haben, erscheinen in deiner Liste (mit Name und Adresse).
"Ablehnen" löscht das Konto. Die Person kann sich neu anmelden. Freigaben passieren nur in der App, die Prüfung liegt im Server.

**Captcha (hCaptcha)**, damit niemand Anmelde-Mails massenhaft auslöst:

1. Konto auf hcaptcha.com anlegen und unter *Sites* einen Website-Schlüssel (sitekey) erzeugen. Den geheimen Schlüssel (secret) brauchst du
   in Supabase. Welcher Host dort für eine App ohne Website einzutragen ist, steht in der hCaptcha-Dokumentation nicht klar,
   im Zweifel einen frei gewählten Namen eintragen.
2. Den sitekey in `SupabaseConfig.HCAPTCHA_SITE_KEY` (`app/src/main/java/app/pwdr/social/Backend.kt`) eintragen, App bauen und
   an alle verteilen (neuer Tag). Der sitekey ist öffentlich und darf im Code stehen.
3. **Erst danach** in Supabase unter *Authentication > Attack Protection* (je nach Version *Bot and Abuse Protection*) "Enable CAPTCHA
   protection" einschalten, hCaptcha wählen und den secret eintragen. Alte App-Versionen ohne Captcha können sich dann nicht mehr
   per Link anmelden, bereits angemeldete Konten bleiben angemeldet.
4. Zum Ausprobieren gibt es Testschlüssel von hCaptcha (sitekey `10000000-ffff-ffff-ffff-000000000001`, secret
   `0x0000000000000000000000000000000000000000`). Sie schützen nicht vor Bots und gehören nicht in den Alltag.

Ob Supabase das Captcha auch beim Anmelde-Link (OTP) verlangt, steht in der Dokumentation nicht ausdrücklich. Nach dem Einschalten
einen Link anfordern: Mit Captcha muss er ankommen, ein Aufruf ohne Token muss abgelehnt werden.

### Alternative: Registrierung sperren und Leute einladen

Der Supabase-Schlüssel steht im App-Code, also kann jeder, der die Adresse kennt, über die API ein Konto anlegen.
Wer keine Freigabe in der App will, schaltet die freie Registrierung ab und lässt nur Eingeladene zu:

1. *Authentication > Sign In / Providers* (je nach Version *Providers > Email*): **"Allow new users to sign up"** ausschalten.
2. Neue Leute unter *Authentication > Users > Invite user* einladen. Sie bekommen eine Mail mit der Vorlage "Invite user"
   (`backend/supabase/email/invite_user.html`, unter *Authentication > Emails > Invite user* einfügen). Nach dem Tippen auf
   den Link ist das Konto aktiv, danach melden sie sich wie gewohnt per Magic Link an.
3. Wer sich ohne Einladung anmeldet, sieht in der App: "Neue Anmeldungen sind gesperrt. Frag den Entwickler nach einer
   Einladung." Bereits vorhandene Konten (wie deines) sind nicht betroffen.

Eine Einladung braucht den eigenen SMTP-Dienst (siehe oben), sonst gilt für sie das enge Limit des eingebauten Versands.

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
| `supabase/migrations/0007_approval.sql` | Freigabe neuer Konten: Sperre vor jeder Anfrage, Admin-Funktionen |
| `test/13_approval_tests.sql` | Tests der Freigabe (Sperre, Admin, Standort nur für Freigegebene) |
| `supabase/email/` | E-Mail-Vorlagen (Magic link, Confirm signup, Invite user) |
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

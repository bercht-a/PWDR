-- Tests der Zugriffsregeln und Funktionen. Fehler bedeuten Standort-Lecks, deshalb prüfen sie vor allem,
-- was NICHT sichtbar sein darf. Jeder Fehlschlag bricht mit einer Ausnahme ab (psql ON_ERROR_STOP).

create schema tests;
grant usage on schema tests to anon, authenticated;

create function tests.as_user(u uuid) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claim.sub', u::text, true);
    perform set_config('role', 'authenticated', true);
end $$;

create function tests.as_anon() returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claim.sub', '', true);
    perform set_config('role', 'anon', true);
end $$;

create function tests.as_admin() returns void language plpgsql as $$
begin
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
end $$;

create function tests.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
    if cond is not true then raise exception 'FEHLGESCHLAGEN: %', msg; end if;
    raise notice 'ok: %', msg;
end $$;

-- Erwartet, dass die SQL-Anweisung scheitert, optional mit einem Teil der Fehlermeldung.
create function tests.throws(stmt text, expected text default null) returns void language plpgsql as $$
begin
    begin
        execute stmt;
    exception when others then
        if expected is not null and sqlerrm not like '%' || expected || '%' then
            raise exception 'FEHLGESCHLAGEN: falsche Meldung "%" statt "%" bei: %', sqlerrm, expected, stmt;
        end if;
        raise notice 'ok (Fehler erwartet): % -> %', stmt, sqlerrm;
        return;
    end;
    raise exception 'FEHLGESCHLAGEN: keine Ausnahme bei: %', stmt;
end $$;

grant execute on all functions in schema tests to anon, authenticated;

begin;

-- Nutzer: A (teilt), B (Freund von A), C (Fremder), D (wird blockiert)
insert into auth.users (id, email) values
    ('a0000000-0000-0000-0000-00000000000a', 'a@test'),
    ('b0000000-0000-0000-0000-00000000000b', 'b@test'),
    ('c0000000-0000-0000-0000-00000000000c', 'c@test'),
    ('d0000000-0000-0000-0000-00000000000d', 'd@test');

select tests.as_user('a0000000-0000-0000-0000-00000000000a');
insert into public.profiles (id, display_name, color) values (auth.uid(), 'Anna', '#FF5A5F');
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
insert into public.profiles (id, display_name) values (auth.uid(), 'Ben');
select tests.as_user('c0000000-0000-0000-0000-00000000000c');
insert into public.profiles (id, display_name) values (auth.uid(), 'Chris');
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
insert into public.profiles (id, display_name) values (auth.uid(), 'Dana');

-- ---- Profile
select tests.as_user('c0000000-0000-0000-0000-00000000000c');
select tests.throws($$insert into public.profiles (id, display_name) values ('a0000000-0000-0000-0000-00000000000a', 'Fake')$$);
select tests.throws($$select 1 from public.profiles where false; delete from public.profiles$$, 'permission denied');
do $$ begin
    perform tests.ok((select count(*) from public.profiles) = 1, 'Fremder sieht nur das eigene Profil');
    update public.profiles set display_name = 'Chris2' where id = 'a0000000-0000-0000-0000-00000000000a';
    perform tests.ok(true, 'Update fremdes Profil läuft ins Leere');
end $$;
select tests.as_admin();
do $$ begin
    perform tests.ok((select display_name from public.profiles where id = 'a0000000-0000-0000-0000-00000000000a') = 'Anna',
                     'Fremdes Profil blieb unverändert');
end $$;

-- ---- Einladung: Fremde ohne Zugriff, anon sowieso nicht
select tests.as_anon();
select tests.throws($$select public.create_invite()$$, 'permission denied');
select tests.throws($$select * from public.invites$$, 'permission denied');

select tests.as_user('a0000000-0000-0000-0000-00000000000a');
create temp table t_invite as select public.create_invite() as code;
grant select on t_invite to authenticated;
do $$ begin
    perform tests.ok((select code from t_invite) ~ '^[A-Z2-9]{6}$', 'Code hat 6 Zeichen ohne verwechselbare Zeichen');
    perform tests.ok(public.redeem_invite((select code from t_invite)) is null, 'Eigener Code ist nicht einlösbar');
end $$;
select tests.throws($$select * from public.invites$$, 'permission denied');

-- B löst ein
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin
    perform tests.ok(public.redeem_invite(lower((select code from t_invite))) = 'a0000000-0000-0000-0000-00000000000a',
                     'B löst den Code ein (Kleinschreibung egal) und bekommt Annas ID');
    perform tests.ok(public.redeem_invite((select code from t_invite)) is null, 'Code ist nur einmal einlösbar');
    perform tests.ok(public.is_friend('a0000000-0000-0000-0000-00000000000a', 'b0000000-0000-0000-0000-00000000000b'), 'A und B sind Freunde');
    perform tests.ok((select count(*) from public.profiles) = 2, 'B sieht eigenes Profil und Annas');
    perform tests.ok((select count(*) from public.list_friends()) = 1, 'B hat einen Freund in der Liste');
end $$;
select tests.throws($$insert into public.friendships (user_a, user_b, status) values ('b0000000-0000-0000-0000-00000000000b','c0000000-0000-0000-0000-00000000000c','accepted')$$, 'permission denied');

-- C ist fremd
select tests.as_user('c0000000-0000-0000-0000-00000000000c');
do $$ begin
    perform tests.ok((select count(*) from public.profiles where id = 'a0000000-0000-0000-0000-00000000000a') = 0, 'C sieht Annas Profil nicht');
    perform tests.ok((select count(*) from public.friendships) = 0, 'C sieht keine fremden Freundschaften');
    perform tests.ok(public.redeem_invite('ZZZZZZ') is null, 'Falscher Code liefert null');
end $$;

-- Abgelaufener Code
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
create temp table t_old as select public.create_invite() as code;
grant select on t_old to authenticated;
select tests.as_admin();
update public.invites set expires_at = now() - interval '1 minute' where code = (select code from t_old);
select tests.as_user('c0000000-0000-0000-0000-00000000000c');
do $$ begin
    perform tests.ok(public.redeem_invite((select code from t_old)) is null, 'Abgelaufener Code ist ungültig');
end $$;

-- Raten wird gebremst: C hatte bisher 2 Fehlversuche (falscher und abgelaufener Code), nach insgesamt 10 ist Schluss
do $$ begin
    for i in 1..8 loop perform public.redeem_invite('AAAAA' || i); end loop;
end $$;
select tests.throws($$select public.redeem_invite('BBBBBB')$$, 'too_many_attempts');

-- ---- Standort teilen
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
-- Ohne Sitzung ist kein Senden möglich
select tests.throws($$select public.update_location(46.4, 7.7, 2000, 10, 90, 5, 'downhill')$$, 'no_active_share');
-- Direktes Schreiben in live_locations ist verboten
select tests.throws($$insert into public.live_locations (user_id, session_id, geom) values (auth.uid(), gen_random_uuid(), 'POINT(7 46)')$$, 'permission denied');
select tests.throws($$insert into public.share_sessions (user_id, expires_at) values (auth.uid(), now() + interval '1 hour')$$, 'permission denied');

-- A teilt mit B und C (C ist kein Freund und wird ignoriert)
create temp table t_session as
    select public.start_share(4, array['b0000000-0000-0000-0000-00000000000b'::uuid, 'c0000000-0000-0000-0000-00000000000c'::uuid]) as id;
grant select on t_session to authenticated;
do $$ begin
    perform tests.ok((select count(*) from public.share_targets where session_id = (select id from t_session)) = 1,
                     'Nur der echte Freund B ist freigegeben');
    perform public.update_location(46.4, 7.7, 2000, 10, 90, 5, 'downhill');
    perform tests.ok((select count(*) from public.live_locations) = 1, 'A sieht die eigene Live-Zeile');
    perform public.update_location(46.5, 7.8, 1900, 12, 91, 4, 'downhill');
    perform tests.ok((select count(*) from public.live_locations) = 1, 'Zweites Update überschreibt, es bleibt eine Zeile');
end $$;
select tests.throws($$select public.update_location(95, 7.7, 0, 0, 0, 0, 'downhill')$$, 'invalid_position');
select tests.throws($$select public.update_location(46.4, 7.7, 0, 0, 0, 0, 'fliegen')$$, 'invalid_state');
select tests.throws($$select public.start_share(13, null)$$, 'invalid_duration');

select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 1, 'B (freigegeben) sieht Annas Position');
    perform tests.ok((select st_y(geom::geometry) from public.live_locations) = 46.5, 'B sieht die neueste Position');
    perform tests.ok((select lat from public.live_locations) = 46.5 and (select lon from public.live_locations) = 7.8,
                     'B sieht Breite und Länge als einfache Zahlen');
end $$;
select tests.throws($$select public.update_location(46.4, 7.7, 0, 0, 0, 0, 'pause')$$, 'no_active_share');

select tests.as_user('c0000000-0000-0000-0000-00000000000c');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'C (Fremder, ignoriert) sieht nichts');
    perform tests.ok((select count(*) from public.share_sessions) = 0, 'C sieht keine fremden Sitzungen');
end $$;
select tests.as_anon();
select tests.throws($$select * from public.live_locations$$, 'permission denied');

-- Freund wird erst später freigegeben, nur Freunde
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
select tests.throws($$select public.set_share_target('c0000000-0000-0000-0000-00000000000c', true)$$, 'not_a_friend');

-- Freigabe pro Freund zurücknehmen
do $$ begin perform public.set_share_target('b0000000-0000-0000-0000-00000000000b', false); end $$;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin perform tests.ok((select count(*) from public.live_locations) = 0, 'Nach Rücknahme der Freigabe sieht B nichts mehr'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin perform public.set_share_target('b0000000-0000-0000-0000-00000000000b', true); end $$;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin perform tests.ok((select count(*) from public.live_locations) = 1, 'Wieder freigegeben, B sieht die Position'); end $$;

-- Ablauf: abgelaufene Sitzung ist sofort unsichtbar, auch ohne Aufräumen
select tests.as_admin();
update public.share_sessions set expires_at = now() - interval '1 second' where id = (select id from t_session);
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin perform tests.ok((select count(*) from public.live_locations) = 0, 'Abgelaufene Freigabe: B sieht nichts, noch vor dem Aufräumen'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
select tests.throws($$select public.update_location(46.4, 7.7, 0, 0, 0, 0, 'pause')$$, 'no_active_share');
select tests.throws($$select public.cleanup_expired()$$, 'permission denied');
select tests.as_admin();
select public.cleanup_expired();
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Aufräumen löscht die Live-Zeile');
    perform tests.ok((select ended_at is not null from public.share_sessions where id = (select id from t_session)), 'Aufräumen beendet die Sitzung');
end $$;

-- Neue Sitzung, dann Stopp löscht die Live-Zeile
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform public.start_share(1, array['b0000000-0000-0000-0000-00000000000b'::uuid]);
    perform public.update_location(46.4, 7.7, 2000, 5, 0, 5, 'lift');
    perform tests.ok((select count(*) from public.live_locations) = 1, 'Neue Sitzung, Position gesendet');
    perform public.stop_share();
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Stopp löscht die Live-Zeile sofort');
    perform tests.ok((select count(*) from public.share_sessions where ended_at is null) = 0, 'Keine aktive Sitzung mehr');
end $$;

-- Eine neue Sitzung beendet die vorige
do $$ declare s1 uuid; s2 uuid; begin
    s1 := public.start_share(1, '{}');
    s2 := public.start_share(1, '{}');
    perform tests.ok((select count(*) from public.share_sessions where ended_at is null) = 1, 'Höchstens eine aktive Sitzung');
    perform tests.ok((select ended_at is not null from public.share_sessions where id = s1), 'Die alte Sitzung wurde beendet');
    perform public.stop_share();
end $$;

-- ---- Blockieren
-- D wird per Einladung Freund von A, dann blockiert A ihn
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
create temp table t_inv_d as select public.create_invite() as code;
grant select on t_inv_d to authenticated;
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin perform tests.ok(public.redeem_invite((select code from t_inv_d)) is not null, 'D wird Freund von A'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform public.start_share(8, array['d0000000-0000-0000-0000-00000000000d'::uuid, 'b0000000-0000-0000-0000-00000000000b'::uuid]);
    perform public.update_location(46.4, 7.7, 2000, 5, 0, 5, 'pause');
end $$;
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin perform tests.ok((select count(*) from public.live_locations) = 1, 'D sieht Anna vor dem Blockieren'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin perform public.block_friend('d0000000-0000-0000-0000-00000000000d'); end $$;
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Nach dem Blockieren sieht D nichts mehr, sofort');
    perform tests.ok((select count(*) from public.profiles where id = 'a0000000-0000-0000-0000-00000000000a') = 0, 'D sieht Annas Profil nicht mehr');
end $$;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin perform tests.ok((select count(*) from public.live_locations) = 1, 'B ist vom Blockieren von D nicht betroffen'); end $$;

-- Blockierung lässt sich nicht per neuer Einladung umgehen und nicht per Freigabe-Liste
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
create temp table t_inv_d2 as select public.create_invite() as code;
grant select on t_inv_d2 to authenticated;
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin perform tests.ok(public.redeem_invite((select code from t_inv_d2)) is null, 'Blockierter kann sich nicht per Einladung zurückholen'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform public.start_share(8, array['d0000000-0000-0000-0000-00000000000d'::uuid]);
    perform tests.ok((select count(*) from public.share_targets t join public.share_sessions s on s.id = t.session_id
                      where s.ended_at is null and t.friend_id = 'd0000000-0000-0000-0000-00000000000d') = 0,
                     'Blockierter wird bei neuer Freigabe nicht aufgenommen');
    perform public.stop_share();
    perform tests.ok((select count(*) from public.list_friends() where status = 'blocked') = 1, 'A sieht D in der Blockierliste');
end $$;
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin perform tests.ok((select count(*) from public.list_friends()) = 0, 'D sieht A nicht in seiner Liste'); end $$;
-- Nur wer blockiert hat, kann aufheben
select tests.as_user('d0000000-0000-0000-0000-00000000000d');
do $$ begin perform public.unblock_friend('a0000000-0000-0000-0000-00000000000a'); end $$;
select tests.as_admin();
do $$ begin perform tests.ok((select count(*) from public.friendships where status = 'blocked') = 1, 'D kann Annas Blockierung nicht aufheben'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform public.unblock_friend('d0000000-0000-0000-0000-00000000000d');
    perform tests.ok((select count(*) from public.list_friends() where status = 'blocked') = 0, 'A hebt die Blockierung auf');
    perform tests.ok(not public.is_friend('a0000000-0000-0000-0000-00000000000a', 'd0000000-0000-0000-0000-00000000000d'), 'Danach keine Freundschaft, nur neue Einladung hilft');
end $$;

-- ---- Freund entfernen beendet Freigaben
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform public.start_share(8, array['b0000000-0000-0000-0000-00000000000b'::uuid]);
    perform public.update_location(46.4, 7.7, 2000, 5, 0, 5, 'pause');
    perform public.remove_friend('b0000000-0000-0000-0000-00000000000b');
end $$;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Nach dem Entfernen sieht B nichts mehr');
    perform tests.ok((select count(*) from public.list_friends()) = 0, 'B hat keinen Freund mehr');
end $$;

-- ---- Beidseitig: auch der Eingeladene kann die Verbindung jederzeit beenden
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
create temp table t_inv_b2 as select public.create_invite() as code;
grant select on t_inv_b2 to authenticated;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin perform tests.ok(public.redeem_invite((select code from t_inv_b2)) = 'a0000000-0000-0000-0000-00000000000a', 'B wird wieder Freund von A'); end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform tests.ok((select count(*) from public.list_friends() where friend_id = 'b0000000-0000-0000-0000-00000000000b') = 1, 'A sieht B als Freund (beidseitig)');
    perform public.start_share(8, array['b0000000-0000-0000-0000-00000000000b'::uuid]);
    perform public.update_location(46.4, 7.7, 2000, 5, 0, 5, 'downhill');
end $$;
select tests.as_user('b0000000-0000-0000-0000-00000000000b');
do $$ begin
    perform tests.ok((select count(*) from public.list_friends() where friend_id = 'a0000000-0000-0000-0000-00000000000a') = 1, 'B sieht A als Freund (beidseitig)');
    perform tests.ok((select count(*) from public.live_locations) = 1, 'B sieht A beim Skifahren');
    perform public.remove_friend('a0000000-0000-0000-0000-00000000000a');
    perform tests.ok((select count(*) from public.list_friends()) = 0, 'B hat A entfernt');
    perform tests.ok((select count(*) from public.live_locations) = 0, 'B sieht A nach dem Entfernen nicht mehr');
end $$;
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin
    perform tests.ok((select count(*) from public.list_friends()) = 0, 'Auch bei A ist die Verbindung weg (sie gilt für beide)');
    perform tests.ok((select count(*) from public.share_targets t join public.share_sessions s on s.id = t.session_id where s.ended_at is null) = 0, 'Die Freigabe von A an B ist beendet');
    perform public.stop_share();
end $$;

-- ---- Konto löschen
select tests.as_user('a0000000-0000-0000-0000-00000000000a');
do $$ begin perform public.start_share(1, '{}'); perform public.update_location(46.4, 7.7, 2000, 5, 0, 5, 'pause'); end $$;
select public.delete_account();
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from auth.users where id = 'a0000000-0000-0000-0000-00000000000a') = 0, 'Konto gelöscht');
    perform tests.ok((select count(*) from public.profiles where id = 'a0000000-0000-0000-0000-00000000000a') = 0, 'Profil gelöscht');
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Live-Daten gelöscht');
    perform tests.ok((select count(*) from public.share_sessions where user_id = 'a0000000-0000-0000-0000-00000000000a') = 0, 'Sitzungen gelöscht');
    perform tests.ok((select count(*) from public.friendships where 'a0000000-0000-0000-0000-00000000000a' in (user_a, user_b)) = 0, 'Freundschaften gelöscht');
end $$;

rollback;

select 'ALLE TESTS BESTANDEN' as ergebnis;

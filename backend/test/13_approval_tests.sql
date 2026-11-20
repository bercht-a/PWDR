-- Tests der Freigabe neuer Konten (0007_approval.sql). Läuft nach 12_groups_tests.sql.
-- Nutzer: A Admin, B und F freigegebene Freunde, C und E wartend (E-Mail bestätigt), D wartend (E-Mail nicht bestätigt).
-- Der Nutzer aus seed_pre_0007.sql existierte vor der Migration und muss freigegeben sein.

begin;

insert into auth.users (id, email, email_confirmed_at) values
    ('a1000000-0000-0000-0000-00000000000a', 'a@test', now()),
    ('b1000000-0000-0000-0000-00000000000b', 'b@test', now()),
    ('f1000000-0000-0000-0000-00000000000f', 'f@test', now()),
    ('c1000000-0000-0000-0000-00000000000c', 'c@test', now()),
    ('d1000000-0000-0000-0000-00000000000d', 'd@test', null),
    ('e1000000-0000-0000-0000-00000000000e', 'e@test', now());
-- Der Auto-Trigger der Tests hat alle freigegeben. Wartende wieder entfernen.
delete from public.approvals where user_id in
    ('c1000000-0000-0000-0000-00000000000c', 'd1000000-0000-0000-0000-00000000000d', 'e1000000-0000-0000-0000-00000000000e');
insert into public.admins (user_id) values ('a1000000-0000-0000-0000-00000000000a');

select tests.as_user('a1000000-0000-0000-0000-00000000000a');
insert into public.profiles (id, display_name) values (auth.uid(), 'Admin');
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
insert into public.profiles (id, display_name) values (auth.uid(), 'Bea');
select tests.as_user('f1000000-0000-0000-0000-00000000000f');
insert into public.profiles (id, display_name) values (auth.uid(), 'Finn');
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
insert into public.profiles (id, display_name) values (auth.uid(), 'Carla');
select tests.as_user('e1000000-0000-0000-0000-00000000000e');
insert into public.profiles (id, display_name) values (auth.uid(), 'Emil');

select tests.as_admin();
insert into public.friendships (user_a, user_b, status) values
    ('b1000000-0000-0000-0000-00000000000b', 'f1000000-0000-0000-0000-00000000000f', 'accepted');

-- ---------------------------------------------------------------- Bestehende Konten sind freigegeben (Migration)
do $$ begin
    perform tests.ok(public.is_approved('99000000-0000-0000-0000-000000000099'), 'Konten aus der Zeit vor der Migration sind freigegeben');
    perform tests.ok(not public.is_approved('c1000000-0000-0000-0000-00000000000c'), 'Neues Konto ist nicht freigegeben');
end $$;

-- ---------------------------------------------------------------- Zugriff nur über Funktionen
select tests.as_anon();
select tests.throws($$select * from public.my_status()$$, 'permission denied');
select tests.throws($$select * from public.admin_list_pending()$$, 'permission denied');
select tests.throws($$select public.admin_approve(gen_random_uuid())$$, 'permission denied');
select tests.as_user('a1000000-0000-0000-0000-00000000000a');
select tests.throws($$select * from public.approvals$$, 'permission denied');
select tests.throws($$select * from public.admins$$, 'permission denied');
select tests.throws($$insert into public.approvals (user_id) values (auth.uid())$$, 'permission denied');
select tests.throws($$insert into public.admins (user_id) values (auth.uid())$$, 'permission denied');
select tests.throws($$select public.is_approved(auth.uid())$$, 'permission denied');
select tests.throws($$select public.is_admin(auth.uid())$$, 'permission denied');
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
select tests.throws($$insert into public.approvals (user_id) values (auth.uid())$$, 'permission denied');
select tests.throws($$insert into public.admins (user_id) values (auth.uid())$$, 'permission denied');
select tests.throws($$update public.approvals set approved_at = now()$$, 'permission denied');

-- ---------------------------------------------------------------- Die Sperre vor jeder Anfrage
-- Wartendes Konto: nur Profil, Status und Konto löschen
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
-- Pfad der Anfrage noch nie gesetzt (neue Sitzung): gesperrt, nicht durchgelassen
select tests.throws($$select public.pwdr_gate()$$, 'not_approved');
do $$
declare p text;
begin
    foreach p in array array['/rpc/list_friends', '/rpc/create_group', '/rpc/start_share', '/rpc/update_location', '/rpc/create_invite',
                             '/rpc/redeem_invite', '/rpc/join_group', '/rpc/post_report', '/rpc/list_reports', '/rpc/admin_approve',
                             '/friendships', '/live_locations', '/share_sessions', '/reports', '/groups', '/',
                             '/profiles/x', '/rpc/my_status/x', '/rpc/my_status2', '/xrpc/my_status', '/rpc/delete_accounts', '']
    loop
        perform set_config('request.path', p, true);
        perform tests.throws('select public.pwdr_gate()', 'not_approved');
    end loop;
    foreach p in array array['/profiles', '/rpc/my_status', '/rpc/delete_account', '/rest/v1/profiles', '/rest/v1/rpc/my_status']
    loop
        perform set_config('request.path', p, true);
        perform public.pwdr_gate();
    end loop;
    -- Ohne gesetzten Pfad: gesperrt
    perform set_config('request.path', '', true);
    perform tests.throws('select public.pwdr_gate()', 'not_approved');
end $$;
-- Der Fehler trägt den Code PT403 (PostgREST macht daraus HTTP 403)
do $$ begin
    perform set_config('request.path', '/rpc/list_friends', true);
    begin
        perform public.pwdr_gate();
        raise exception 'FEHLGESCHLAGEN: Sperre hat nicht geworfen';
    exception when sqlstate 'PT403' then
        raise notice 'ok: Sperre liefert PT403';
    end;
end $$;

-- Freigegebenes Konto: alles durch, auch unbekannte Pfade
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
do $$
declare p text;
begin
    foreach p in array array['/rpc/list_friends', '/rpc/create_group', '/friendships', '/unbekannt', '/', '']
    loop
        perform set_config('request.path', p, true);
        perform public.pwdr_gate();
    end loop;
end $$;
-- Ohne Anmeldung (anon): die Sperre lässt durch, die Rechte der Funktionen entscheiden
select tests.as_anon();
do $$ begin
    perform set_config('request.path', '/rpc/list_friends', true);
    perform public.pwdr_gate();
    perform tests.ok(true, 'Sperre lässt anon durch (Rechte entscheiden)');
end $$;
-- Die Sperre ist für beide Rollen aufrufbar (PostgREST ruft sie mit der Rolle der Anfrage)
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
select set_config('request.path', '/profiles', true);
select public.pwdr_gate();

-- Die Sperre ist als Vorab-Funktion von PostgREST eingetragen
select tests.as_admin();
do $$ begin
    perform tests.ok(exists (
        select 1 from pg_db_role_setting s join pg_roles r on r.oid = s.setrole
        where r.rolname = 'authenticator' and 'pgrst.db_pre_request=public.pwdr_gate' = any (s.setconfig)),
        'authenticator ruft pwdr_gate vor jeder Anfrage auf');
end $$;

-- ---------------------------------------------------------------- Status
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
do $$
declare r record;
begin
    select * into r from public.my_status();
    perform tests.ok(r.approved = false and r.is_admin = false and r.pending_count = 0, 'Wartendes Konto sieht: nicht freigegeben, kein Admin, keine Zahl');
end $$;
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
do $$
declare r record;
begin
    select * into r from public.my_status();
    perform tests.ok(r.approved and not r.is_admin and r.pending_count = 0, 'Freigegebenes Konto: freigegeben, kein Admin, keine Zahl');
end $$;
select tests.as_user('a1000000-0000-0000-0000-00000000000a');
do $$
declare r record;
begin
    select * into r from public.my_status();
    perform tests.ok(r.approved and r.is_admin, 'Admin ist freigegeben und Admin');
    perform tests.ok(r.pending_count = 2, 'Admin sieht 2 wartende Konten (ohne die mit unbestätigter E-Mail)');
end $$;

-- ---------------------------------------------------------------- Liste der Wartenden
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
select tests.throws($$select * from public.admin_list_pending()$$, 'not_admin');
select tests.throws($$select public.admin_approve('c1000000-0000-0000-0000-00000000000c')$$, 'not_admin');
select tests.throws($$select public.admin_reject('c1000000-0000-0000-0000-00000000000c')$$, 'not_admin');
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
select tests.throws($$select * from public.admin_list_pending()$$, 'not_admin');
select tests.throws($$select public.admin_approve(auth.uid())$$, 'not_admin');
select tests.as_user('a1000000-0000-0000-0000-00000000000a');
do $$
declare names text;
begin
    select string_agg(coalesce(display_name, '-'), ',' order by display_name) into names from public.admin_list_pending();
    perform tests.ok(names = 'Carla,Emil', 'Liste enthält nur bestätigte, wartende Konten mit Namen: ' || coalesce(names, ''));
    perform tests.ok((select count(*) from public.admin_list_pending() where email = 'd@test') = 0, 'Unbestätigte E-Mail steht nicht in der Liste');
end $$;

-- ---------------------------------------------------------------- Freigeben und Ablehnen
select tests.throws($$select public.admin_approve(gen_random_uuid())$$, 'no_such_user');
select public.admin_approve('c1000000-0000-0000-0000-00000000000c');
select public.admin_approve('c1000000-0000-0000-0000-00000000000c');
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.approvals where user_id = 'c1000000-0000-0000-0000-00000000000c') = 1, 'Doppelte Freigabe ändert nichts');
    perform tests.ok((select approved_by from public.approvals where user_id = 'c1000000-0000-0000-0000-00000000000c') = 'a1000000-0000-0000-0000-00000000000a', 'Freigabe merkt sich den Admin');
end $$;
select tests.as_user('c1000000-0000-0000-0000-00000000000c');
do $$
declare r record;
begin
    select * into r from public.my_status();
    perform tests.ok(r.approved, 'Nach der Freigabe ist das Konto freigegeben');
    perform set_config('request.path', '/rpc/list_friends', true);
    perform public.pwdr_gate();
    perform tests.ok(true, 'Nach der Freigabe lässt die Sperre durch');
end $$;

select tests.as_user('a1000000-0000-0000-0000-00000000000a');
select tests.throws($$select public.admin_reject('b1000000-0000-0000-0000-00000000000b')$$, 'already_approved');
select tests.throws($$select public.admin_reject('a1000000-0000-0000-0000-00000000000a')$$, 'already_approved');
select tests.throws($$select public.admin_reject('c1000000-0000-0000-0000-00000000000c')$$, 'already_approved');
select public.admin_reject('e1000000-0000-0000-0000-00000000000e');
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from auth.users where id = 'e1000000-0000-0000-0000-00000000000e') = 0, 'Abgelehntes Konto ist gelöscht');
    perform tests.ok((select count(*) from public.profiles where id = 'e1000000-0000-0000-0000-00000000000e') = 0, 'Sein Profil ist mitgelöscht');
end $$;

-- ---------------------------------------------------------------- Standort: nur freigegebene Konten sehen
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
select public.start_share(2, array['f1000000-0000-0000-0000-00000000000f'::uuid]);
select public.update_location(46.4, 7.7, 2000, 10, 90, 5, 'downhill');
select tests.as_user('f1000000-0000-0000-0000-00000000000f');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 1, 'Freigegebener Freund sieht die Position');
end $$;
-- Freigabe von F wird entzogen: gleiche Freundschaft, gleiche Freigabe, trotzdem nichts
select tests.as_admin();
delete from public.approvals where user_id = 'f1000000-0000-0000-0000-00000000000f';
select tests.as_user('f1000000-0000-0000-0000-00000000000f');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Ohne Freigabe sieht auch ein Freund nichts (Realtime-Schicht)');
end $$;
-- Auch die eigene Position nicht
select tests.as_admin();
delete from public.approvals where user_id = 'b1000000-0000-0000-0000-00000000000b';
select tests.as_user('b1000000-0000-0000-0000-00000000000b');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Ohne Freigabe sieht man nicht einmal die eigene Position');
end $$;

rollback;

select 'ALLE FREIGABE-TESTS BESTANDEN' as ergebnis;

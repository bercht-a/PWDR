-- Tests der Gruppen (0006_groups.sql). Läuft nach 10_rls_tests.sql und 11_reports_tests.sql, nutzt die Hilfsfunktionen im Schema tests.
-- Wie bei den anderen Tests zählt vor allem, was NICHT sichtbar sein darf (Standort-Lecks).
-- Nutzer: U1 Greta (Ersteller), U2 Hugo und U5 Kai (Freunde von Greta), U3 Iris, U6 Lena (Fremde, treten per Code bei),
--         U4 Jonas (Aussenstehender), U7 (Fehlversuche).

begin;

insert into auth.users (id, email) values
    ('21000000-0000-0000-0000-000000000001', 'u1@test'),
    ('22000000-0000-0000-0000-000000000002', 'u2@test'),
    ('23000000-0000-0000-0000-000000000003', 'u3@test'),
    ('24000000-0000-0000-0000-000000000004', 'u4@test'),
    ('25000000-0000-0000-0000-000000000005', 'u5@test'),
    ('26000000-0000-0000-0000-000000000006', 'u6@test'),
    ('27000000-0000-0000-0000-000000000007', 'u7@test');
-- Viele weitere Nutzer für die Grenzen (Gruppengrösse, Mitgliedschaften)
insert into auth.users (id, email)
    select ('30000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid, 'g' || i || '@test' from generate_series(1, 40) as i;

select tests.as_user('21000000-0000-0000-0000-000000000001');
insert into public.profiles (id, display_name, color) values (auth.uid(), 'Greta', '#FF5A5F');
select tests.as_user('22000000-0000-0000-0000-000000000002');
insert into public.profiles (id, display_name) values (auth.uid(), 'Hugo');
select tests.as_user('23000000-0000-0000-0000-000000000003');
insert into public.profiles (id, display_name) values (auth.uid(), 'Iris');
select tests.as_user('24000000-0000-0000-0000-000000000004');
insert into public.profiles (id, display_name) values (auth.uid(), 'Jonas');
select tests.as_user('25000000-0000-0000-0000-000000000005');
insert into public.profiles (id, display_name) values (auth.uid(), 'Kai');
select tests.as_user('26000000-0000-0000-0000-000000000006');
insert into public.profiles (id, display_name) values (auth.uid(), 'Lena');

select tests.as_admin();
insert into public.friendships (user_a, user_b, status) values
    ('21000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000002', 'accepted'),
    ('21000000-0000-0000-0000-000000000001', '25000000-0000-0000-0000-000000000005', 'accepted');

-- ---------------------------------------------------------------- Zugriff nur über Funktionen
select tests.as_anon();
select tests.throws($$select public.create_group('X', 7)$$, 'permission denied');
select tests.throws($$select * from public.list_groups()$$, 'permission denied');
select tests.throws($$select * from public.my_viewers()$$, 'permission denied');
select tests.as_user('21000000-0000-0000-0000-000000000001');
select tests.throws($$select * from public.groups$$, 'permission denied');
select tests.throws($$select * from public.group_members$$, 'permission denied');
select tests.throws($$select * from public.group_invites$$, 'permission denied');
select tests.throws($$select * from public.group_friend_invites$$, 'permission denied');
select tests.throws($$select * from public.group_removed$$, 'permission denied');
select tests.throws($$insert into public.groups (name, owner_id) values ('X', auth.uid())$$, 'permission denied');
select tests.throws($$select public.require_group_owner(gen_random_uuid(), auth.uid())$$, 'permission denied');
select tests.throws($$select public.group_hand_over(gen_random_uuid(), auth.uid())$$, 'permission denied');

-- ---------------------------------------------------------------- Gruppe erstellen
create temp table t_g as select public.create_group('  Wochenende Arosa ', 7) as id;
grant select on t_g to authenticated;
do $$ begin
    perform tests.ok((select name from public.list_groups() where group_id = (select id from t_g)) = 'Wochenende Arosa', 'Name wird getrimmt gespeichert');
    perform tests.ok((select role from public.list_groups() where group_id = (select id from t_g)) = 'owner', 'Ersteller hat die Rolle owner');
    perform tests.ok((select member_count from public.list_groups() where group_id = (select id from t_g)) = 1, 'Neue Gruppe hat ein Mitglied');
    perform tests.ok((select owner_name from public.list_groups() where group_id = (select id from t_g)) = 'Greta', 'Ersteller-Name wird angezeigt');
    perform tests.ok((select expires_at from public.list_groups() where group_id = (select id from t_g)) between now() + interval '6 days' and now() + interval '8 days', 'Laufzeit 7 Tage');
    perform tests.ok((select share_location from public.list_groups() where group_id = (select id from t_g)), 'Teilen ist standardmässig an');
end $$;
select tests.throws($$select public.create_group('', 7)$$, 'invalid_name');
select tests.throws($$select public.create_group('   ', 7)$$, 'invalid_name');
select tests.throws($$select public.create_group(repeat('x', 31), 7)$$, 'invalid_name');
select tests.throws($$select public.create_group('X', 0)$$, 'invalid_duration');
select tests.throws($$select public.create_group('X', 367)$$, 'invalid_duration');
create temp table t_g2 as select public.create_group('Saison', null) as id;
grant select on t_g2 to authenticated;
do $$ begin
    perform tests.ok((select expires_at from public.list_groups() where group_id = (select id from t_g2)) is null, 'Ohne Laufzeit ist die Gruppe unbegrenzt');
    perform public.create_group('G3', 1);
    perform public.create_group('G4', 1);
    perform public.create_group('G5', 1);
end $$;
select tests.throws($$select public.create_group('G6', 1)$$, 'too_many_groups');
do $$ declare r record; begin
    for r in select group_id from public.list_groups() where name in ('G3', 'G4', 'G5') loop
        perform public.delete_group(r.group_id);
    end loop;
    perform tests.ok((select count(*) from public.list_groups()) = 2, 'Löschen gibt Platz für neue Gruppen');
end $$;

-- ---------------------------------------------------------------- Einladungscodes
create temp table t_code as select public.create_group_invite((select id from t_g), 10) as code;
grant select on t_code to authenticated;
do $$ begin
    perform tests.ok((select code from t_code) ~ '^[A-Z2-9]{6}$', 'Gruppen-Code hat 6 Zeichen ohne verwechselbare Zeichen');
end $$;
select tests.throws($$select public.create_group_invite((select id from t_g), 0)$$, 'invalid_max_uses');
select tests.throws($$select public.create_group_invite((select id from t_g), 21)$$, 'invalid_max_uses');
select tests.as_user('23000000-0000-0000-0000-000000000003');
select tests.throws($$select public.create_group_invite((select id from t_g), 10)$$, 'not_owner');
select tests.throws($$select * from public.list_group_members((select id from t_g))$$, 'not_a_member');
select tests.as_user('21000000-0000-0000-0000-000000000001');
-- Höchstens 5 offene Codes je Gruppe (Gruppe "Saison"), Widerruf macht Platz und entwertet die Codes
do $$ begin
    for i in 1..5 loop perform public.create_group_invite((select id from t_g2), 5); end loop;
end $$;
select tests.throws($$select public.create_group_invite((select id from t_g2), 5)$$, 'too_many_invites');
select public.revoke_group_invites((select id from t_g2));
do $$ begin
    perform public.create_group_invite((select id from t_g2), 5);
    perform tests.ok(true, 'Nach dem Widerruf lassen sich wieder Codes erzeugen');
end $$;

-- Code-Art erkennen: Freundes-Code, Gruppen-Code, ungültig
create temp table t_friend_code as select public.create_invite() as code;
grant select on t_friend_code to authenticated;
select tests.as_user('24000000-0000-0000-0000-000000000004');
do $$ begin
    perform tests.ok(public.code_kind((select code from t_friend_code)) = 'friend', 'Freundes-Code wird als friend erkannt');
    perform tests.ok(public.code_kind(lower((select code from t_code))) = 'group', 'Gruppen-Code wird als group erkannt (Kleinschreibung egal)');
    perform tests.ok(public.code_kind('ZZZZZZ') is null, 'Falscher Code liefert null');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok(public.code_kind((select code from t_friend_code)) is null, 'Der eigene Freundes-Code ist nicht einlösbar');
end $$;

-- Vorschau ohne Beitritt: nur Name, Ersteller, Anzahl
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select group_name from public.preview_group_invite((select code from t_code))) = 'Wochenende Arosa', 'Vorschau zeigt den Gruppennamen');
    perform tests.ok((select owner_name from public.preview_group_invite((select code from t_code))) = 'Greta', 'Vorschau zeigt den Ersteller');
    perform tests.ok((select member_count from public.preview_group_invite((select code from t_code))) = 1, 'Vorschau zeigt die Anzahl Mitglieder');
    perform tests.ok(not (select already_member from public.preview_group_invite((select code from t_code))), 'Vorschau: noch kein Mitglied');
    perform tests.ok((select count(*) from public.preview_group_invite('QQQQQQ')) = 0, 'Vorschau bei falschem Code: keine Zeile');
    perform tests.ok((select count(*) from public.list_groups()) = 0, 'Die Vorschau macht niemanden zum Mitglied');
end $$;

-- ---------------------------------------------------------------- Beitreten per Code
do $$ begin
    perform tests.ok(public.join_group(lower((select code from t_code))) = (select id from t_g), 'Iris tritt per Code bei (Kleinschreibung egal)');
    perform tests.ok(public.join_group((select code from t_code)) = (select id from t_g), 'Zweiter Beitritt ist harmlos und liefert dieselbe Gruppe');
    perform tests.ok((select role from public.list_groups() where group_id = (select id from t_g)) = 'member', 'Iris ist Mitglied, nicht Ersteller');
    perform tests.ok((select count(*) from public.list_group_members((select id from t_g))) = 2, 'Iris sieht zwei Mitglieder');
    perform tests.ok((select pending_count from public.list_groups() where group_id = (select id from t_g)) = 0, 'Nur der Ersteller sieht offene Einladungen');
end $$;
select tests.as_admin();
do $$ begin
    perform tests.ok((select uses from public.group_invites where code = (select code from t_code)) = 1, 'Doppelter Beitritt zählt den Code nur einmal');
end $$;
-- Code mit nur einem Beitritt
select tests.as_user('21000000-0000-0000-0000-000000000001');
create temp table t_once as select public.create_group_invite((select id from t_g), 1) as code;
grant select on t_once to authenticated;
select tests.as_user('26000000-0000-0000-0000-000000000006');
do $$ begin
    perform tests.ok(public.join_group((select code from t_once)) = (select id from t_g), 'Lena tritt mit dem Einmal-Code bei');
end $$;
select tests.as_user('25000000-0000-0000-0000-000000000005');
do $$ begin
    perform tests.ok(public.join_group((select code from t_once)) is null, 'Aufgebrauchter Code ist ungültig');
end $$;
-- Abgelaufener Code
select tests.as_user('21000000-0000-0000-0000-000000000001');
create temp table t_old as select public.create_group_invite((select id from t_g), 5) as code;
grant select on t_old to authenticated;
select tests.as_admin();
update public.group_invites set expires_at = now() - interval '1 minute' where code = (select code from t_old);
select tests.as_user('25000000-0000-0000-0000-000000000005');
do $$ begin
    perform tests.ok(public.join_group((select code from t_old)) is null, 'Abgelaufener Code ist ungültig');
    perform tests.ok(public.code_kind((select code from t_old)) is null, 'Abgelaufener Code wird nicht erkannt');
    perform tests.ok((select count(*) from public.list_groups()) = 0, 'Kai ist nirgends Mitglied geworden');
end $$;

-- ---------------------------------------------------------------- Sichtbarkeit (der Kern: keine Lecks)
-- Mitglieder jetzt: U1 (Ersteller), U3, U6. U2 und U5 sind Freunde von U1, aber nicht in der Gruppe. U4 ist draussen.
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.start_share(4, '{}'::uuid[]);
select public.update_location(46.4, 7.7, 2000, 10, 90, 5, 'downhill');

select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Iris (Mitglied, kein Freund) sieht Gretas Position');
    perform tests.ok((select visible from public.list_group_members((select id from t_g)) where member_id = '21000000-0000-0000-0000-000000000001'), 'Mitgliederliste: Greta ist sichtbar');
    perform tests.ok((select display_name from public.list_group_members((select id from t_g)) where member_id = '21000000-0000-0000-0000-000000000001') = 'Greta', 'Mitgliederliste zeigt den Namen');
    perform tests.ok((select count(*) from public.profiles where id = '21000000-0000-0000-0000-000000000001') = 0, 'Das Profil bleibt gesperrt, Namen kommen nur über die Mitgliederliste');
    perform tests.ok((select count(*) from public.list_friends()) = 0, 'Mitglieder werden dadurch keine Freunde');
end $$;
select tests.as_user('26000000-0000-0000-0000-000000000006');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 1, 'Lena (Mitglied) sieht Gretas Position');
end $$;
select tests.as_user('24000000-0000-0000-0000-000000000004');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Jonas (nicht in der Gruppe) sieht nichts');
end $$;
select tests.as_user('22000000-0000-0000-0000-000000000002');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Hugo (Freund, aber nicht freigegeben und nicht in der Gruppe) sieht nichts');
end $$;

-- Schalter "Standort in dieser Gruppe teilen"
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.set_group_sharing((select id from t_g), false);
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Teilen aus: Iris sieht Greta nicht mehr');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.set_group_sharing((select id from t_g), true);
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 1, 'Teilen wieder an: Iris sieht Greta');
end $$;

-- Gegenrichtung: Iris teilt auch, Greta sieht Iris, Jonas nicht
select public.start_share(4, '{}'::uuid[]);
select public.update_location(46.41, 7.71, 2100, 8, 80, 5, 'lift');
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '23000000-0000-0000-0000-000000000003') = 1, 'Greta sieht Iris');
end $$;
select tests.as_user('24000000-0000-0000-0000-000000000004');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Jonas sieht auch Iris nicht');
end $$;
-- Iris schaltet ihr Teilen aus: Greta sieht sie nicht mehr, Iris sieht Greta weiterhin (wie beim Schalter pro Freund)
select tests.as_user('23000000-0000-0000-0000-000000000003');
select public.set_group_sharing((select id from t_g), false);
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Iris sieht Greta weiterhin');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '23000000-0000-0000-0000-000000000003') = 0, 'Greta sieht Iris nicht, wenn Iris das Teilen ausgeschaltet hat');
end $$;
select tests.as_user('23000000-0000-0000-0000-000000000003');
select public.set_group_sharing((select id from t_g), true);

-- Blockieren gewinnt, auch in der Gruppe (beide Richtungen)
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.block_friend('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '23000000-0000-0000-0000-000000000003') = 0, 'Greta blockiert Iris: Greta sieht Iris nicht');
end $$;
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Greta blockiert Iris: Iris sieht Greta nicht');
end $$;
select tests.as_user('26000000-0000-0000-0000-000000000006');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Die Blockierung betrifft nur das Paar, Lena sieht Greta weiterhin');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.unblock_friend('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '23000000-0000-0000-0000-000000000003') = 1, 'Blockierung aufgehoben: Greta sieht Iris wieder');
end $$;
-- Auch ein Mitglied kann den Ersteller blockieren
select tests.as_user('23000000-0000-0000-0000-000000000003');
select public.block_friend('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Iris blockiert Greta: Iris sieht Greta nicht');
end $$;
select public.unblock_friend('21000000-0000-0000-0000-000000000001');

-- Ablauf der Gruppe schneidet die Sicht sofort ab, Verlängern stellt sie wieder her
select tests.as_admin();
update public.groups set expires_at = now() - interval '1 hour' where id = (select id from t_g);
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Abgelaufene Gruppe: keine Sicht mehr');
    perform tests.ok((select count(*) from public.list_groups()) = 1, 'Abgelaufene Gruppe bleibt in der Liste (verlängerbar)');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
select tests.throws($$select public.create_group_invite((select id from t_g), 5)$$, 'group_expired');
select tests.throws($$select public.invite_friends_to_group((select id from t_g), array['22000000-0000-0000-0000-000000000002'::uuid])$$, 'group_expired');
select public.extend_group((select id from t_g), 3);
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Verlängert: Iris sieht Greta wieder');
    perform tests.ok((select expires_at from public.list_groups() where group_id = (select id from t_g)) between now() + interval '2 days' and now() + interval '4 days', 'Verlängern zählt ab jetzt, wenn schon abgelaufen');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
select tests.throws($$select public.extend_group((select id from t_g), 400)$$, 'invalid_duration');
select tests.as_user('23000000-0000-0000-0000-000000000003');
select tests.throws($$select public.extend_group((select id from t_g), 3)$$, 'not_owner');

-- Teilen beenden: Live-Zeile weg, keine Sicht
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.stop_share();
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Nach dem Stopp sieht niemand mehr Gretas Position');
end $$;
select public.stop_share();

-- ---------------------------------------------------------------- Verwalten: umbenennen, Rechte, Announce
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.rename_group((select id from t_g), '  Arosa Gang  ');
do $$ begin
    perform tests.ok((select name from public.list_groups() where group_id = (select id from t_g)) = 'Arosa Gang', 'Umbenennen trimmt den Namen');
end $$;
select tests.throws($$select public.rename_group((select id from t_g), '')$$, 'invalid_name');
select public.rename_group((select id from t_g), 'Wochenende Arosa');
select tests.as_user('23000000-0000-0000-0000-000000000003');
select tests.throws($$select public.rename_group((select id from t_g), 'Hack')$$, 'not_owner');
select tests.throws($$select public.delete_group((select id from t_g))$$, 'not_owner');
select tests.throws($$select * from public.list_group_pending((select id from t_g))$$, 'not_owner');
select public.set_group_announce((select id from t_g), false);
do $$ begin
    perform tests.ok(not (select announce from public.list_groups() where group_id = (select id from t_g)), 'Ansagen lassen sich pro Gruppe ausschalten');
end $$;
select tests.as_user('24000000-0000-0000-0000-000000000004');
select tests.throws($$select public.set_group_announce((select id from t_g), false)$$, 'not_a_member');
select tests.throws($$select public.set_group_sharing((select id from t_g), false)$$, 'not_a_member');
select tests.throws($$select public.leave_group((select id from t_g))$$, 'not_a_member');
select tests.throws($$select public.remove_member((select id from t_g), '23000000-0000-0000-0000-000000000003')$$, 'not_owner');

-- ---------------------------------------------------------------- Austreten und Entfernen
-- Lena tritt aus: keine Sicht mehr, kann aber mit einem neuen Code wieder beitreten
select tests.as_user('26000000-0000-0000-0000-000000000006');
select public.leave_group((select id from t_g));
do $$ begin
    perform tests.ok((select count(*) from public.list_groups()) = 0, 'Lena ist ausgetreten');
end $$;
select tests.throws($$select * from public.list_group_members((select id from t_g))$$, 'not_a_member');

-- Greta entfernt Iris: keine Sicht, kein Wiedereintritt per Code
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.start_share(4, '{}'::uuid[]);
select public.update_location(46.4, 7.7, 2000, 10, 90, 5, 'downhill');
select tests.throws($$select public.remove_member((select id from t_g), auth.uid())$$, 'invalid_target');
select tests.throws($$select public.remove_member((select id from t_g), '24000000-0000-0000-0000-000000000004')$$, 'not_a_member');
select public.remove_member((select id from t_g), '23000000-0000-0000-0000-000000000003');
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations) = 0, 'Entfernt: Iris sieht Greta sofort nicht mehr');
    perform tests.ok(public.join_group((select code from t_code)) is null, 'Entfernte Mitglieder kommen nicht per Code zurück');
    perform tests.ok(public.code_kind((select code from t_code)) is null, 'Der Code wird für Entfernte nicht einmal erkannt');
    perform tests.ok((select count(*) from public.preview_group_invite((select code from t_code))) = 0, 'Entfernte sehen auch keine Vorschau');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '23000000-0000-0000-0000-000000000003') = 0, 'Iris ist weg, Greta sieht sie nicht');
    perform tests.ok((select member_count from public.list_groups() where group_id = (select id from t_g)) = 1, 'Nur Greta bleibt in der Gruppe');
end $$;

-- ---------------------------------------------------------------- Freunde einladen (mit Annahme)
-- Kai (U5) ist Freund, Hugo (U2) ist Freund, Jonas (U4) ist kein Freund. Gretas Teilen läuft noch.
select tests.as_admin();
insert into public.friendships (user_a, user_b, status) values
    ('21000000-0000-0000-0000-000000000001', '24000000-0000-0000-0000-000000000004', 'blocked');
update public.friendships set blocked_by = '21000000-0000-0000-0000-000000000001'
    where user_a = '21000000-0000-0000-0000-000000000001' and user_b = '24000000-0000-0000-0000-000000000004';
select tests.as_user('22000000-0000-0000-0000-000000000002');
select tests.throws($$select public.invite_friends_to_group((select id from t_g), array['25000000-0000-0000-0000-000000000005'::uuid])$$, 'not_owner');
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok(public.invite_friends_to_group((select id from t_g), array[
        '22000000-0000-0000-0000-000000000002'::uuid, '25000000-0000-0000-0000-000000000005'::uuid,
        '23000000-0000-0000-0000-000000000003'::uuid,   -- kein Freund
        '24000000-0000-0000-0000-000000000004'::uuid,   -- blockiert
        '21000000-0000-0000-0000-000000000001'::uuid    -- man selbst
    ]) = 2, 'Eingeladen werden nur die zwei echten, nicht blockierten Freunde');
    perform tests.ok(public.invite_friends_to_group((select id from t_g), array['22000000-0000-0000-0000-000000000002'::uuid]) = 0, 'Schon Eingeladene werden nicht doppelt eingeladen');
    perform tests.ok((select pending_count from public.list_groups() where group_id = (select id from t_g)) = 2, 'Der Ersteller sieht zwei offene Einladungen');
    perform tests.ok((select count(*) from public.list_group_pending((select id from t_g))) = 2, 'Liste der offenen Einladungen');
    perform tests.ok((select member_count from public.list_groups() where group_id = (select id from t_g)) = 1, 'Eingeladene sind noch keine Mitglieder');
end $$;

-- Vor der Annahme: keine Sicht in beide Richtungen
select tests.as_user('22000000-0000-0000-0000-000000000002');
select public.start_share(4, '{}'::uuid[]);
select public.update_location(46.42, 7.72, 2050, 9, 70, 5, 'downhill');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Vor der Annahme sieht Hugo Greta nicht');
    perform tests.ok((select count(*) from public.list_groups()) = 0, 'Vor der Annahme ist Hugo nirgends Mitglied');
    perform tests.ok((select count(*) from public.list_group_invitations()) = 1, 'Hugo sieht seine Einladung');
    perform tests.ok((select inviter_name from public.list_group_invitations()) = 'Greta', 'Die Einladung nennt Greta');
    perform tests.ok((select group_name from public.list_group_invitations()) = 'Wochenende Arosa', 'Die Einladung nennt die Gruppe');
    perform tests.ok((select member_count from public.list_group_invitations()) = 1, 'Die Einladung nennt die Mitgliederzahl');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '22000000-0000-0000-0000-000000000002') = 0, 'Vor der Annahme sieht Greta Hugo nicht');
end $$;
select tests.as_user('24000000-0000-0000-0000-000000000004');
do $$ begin
    perform tests.ok((select count(*) from public.list_group_invitations()) = 0, 'Jonas sieht keine fremden Einladungen');
end $$;
select tests.throws($$select public.accept_group_invitation((select id from t_g))$$, 'no_invitation');

-- Annehmen
select tests.as_user('22000000-0000-0000-0000-000000000002');
select public.accept_group_invitation((select id from t_g));
do $$ begin
    perform tests.ok((select count(*) from public.list_groups()) = 1, 'Hugo ist nach der Annahme Mitglied');
    perform tests.ok((select count(*) from public.list_group_invitations()) = 0, 'Die Einladung ist verbraucht');
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Hugo sieht Greta nach der Annahme');
end $$;
select tests.throws($$select public.accept_group_invitation((select id from t_g))$$, 'no_invitation');
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '22000000-0000-0000-0000-000000000002') = 1, 'Greta sieht Hugo nach der Annahme');
    perform tests.ok((select pending_count from public.list_groups() where group_id = (select id from t_g)) = 1, 'Noch eine offene Einladung (Kai)');
end $$;

-- Ablehnen, zurückziehen, Ablauf
select tests.as_user('25000000-0000-0000-0000-000000000005');
select public.decline_group_invitation((select id from t_g));
do $$ begin
    perform tests.ok((select count(*) from public.list_group_invitations()) = 0, 'Abgelehnt: keine Einladung mehr');
    perform tests.ok((select count(*) from public.list_groups()) = 0, 'Abgelehnt: kein Mitglied');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok(public.invite_friends_to_group((select id from t_g), array['25000000-0000-0000-0000-000000000005'::uuid]) = 1, 'Nach dem Ablehnen lässt sich Kai neu einladen');
end $$;
select public.cancel_group_invitation((select id from t_g), '25000000-0000-0000-0000-000000000005');
select tests.as_user('25000000-0000-0000-0000-000000000005');
select tests.throws($$select public.accept_group_invitation((select id from t_g))$$, 'no_invitation');
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.invite_friends_to_group((select id from t_g), array['25000000-0000-0000-0000-000000000005'::uuid]);
select tests.as_admin();
update public.group_friend_invites set expires_at = now() - interval '1 minute';
select tests.as_user('25000000-0000-0000-0000-000000000005');
do $$ begin
    perform tests.ok((select count(*) from public.list_group_invitations()) = 0, 'Abgelaufene Einladung wird nicht angezeigt');
end $$;
select tests.throws($$select public.accept_group_invitation((select id from t_g))$$, 'no_invitation');
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok((select pending_count from public.list_groups() where group_id = (select id from t_g)) = 0, 'Abgelaufene Einladungen zählen nicht als offen');
end $$;

-- Entfernte Freunde: nicht per Code, aber der Ersteller kann sie bewusst neu einladen
select public.remove_member((select id from t_g), '22000000-0000-0000-0000-000000000002');
select tests.as_user('22000000-0000-0000-0000-000000000002');
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 0, 'Entfernter Freund sieht Greta nicht mehr');
    perform tests.ok(public.join_group((select code from t_code)) is null, 'Entfernter Freund kommt nicht per Code zurück');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
do $$ begin
    perform tests.ok(public.invite_friends_to_group((select id from t_g), array['22000000-0000-0000-0000-000000000002'::uuid]) = 1, 'Greta lädt den entfernten Freund bewusst neu ein');
end $$;
select tests.as_user('22000000-0000-0000-0000-000000000002');
select public.accept_group_invitation((select id from t_g));
do $$ begin
    perform tests.ok((select count(*) from public.live_locations where user_id = '21000000-0000-0000-0000-000000000001') = 1, 'Nach der neuen Einladung ist Hugo wieder dabei');
end $$;

-- ---------------------------------------------------------------- Wer sieht mich gerade?
-- Lena tritt wieder bei (sie ist freiwillig gegangen), Kai und Hugo sind Freunde von Greta.
select tests.as_user('21000000-0000-0000-0000-000000000001');
create temp table t_code3 as select public.create_group_invite((select id from t_g), 5) as code;
grant select on t_code3 to authenticated;
select tests.as_user('26000000-0000-0000-0000-000000000006');
select public.join_group((select code from t_code3));
select tests.as_user('21000000-0000-0000-0000-000000000001');
select public.start_share(4, array['22000000-0000-0000-0000-000000000002'::uuid, '25000000-0000-0000-0000-000000000005'::uuid]);
do $$ begin
    perform tests.ok((select count(*) from public.my_viewers()) = 3, 'Greta wird von Hugo, Kai und Lena gesehen, ohne Doppelte');
    perform tests.ok((select via_friend from public.my_viewers() where display_name = 'Hugo'), 'Hugo sieht sie als Freund');
    perform tests.ok(array_position((select group_names from public.my_viewers() where display_name = 'Hugo'), 'Wochenende Arosa') is not null, 'Hugo sieht sie auch über die Gruppe');
    perform tests.ok(not (select via_friend from public.my_viewers() where display_name = 'Lena'), 'Lena sieht sie nur über die Gruppe');
    perform tests.ok(cardinality((select group_names from public.my_viewers() where display_name = 'Kai')) = 0, 'Kai sieht sie nur als Freund');
end $$;
select public.set_group_sharing((select id from t_g), false);
do $$ begin
    perform tests.ok((select count(*) from public.my_viewers()) = 2, 'Gruppe aus: nur noch die freigegebenen Freunde sehen sie');
end $$;
select public.set_group_sharing((select id from t_g), true);
select public.stop_share();
do $$ begin
    perform tests.ok((select count(*) from public.my_viewers()) = 0, 'Ohne aktive Freigabe sieht sie niemand');
end $$;

-- ---------------------------------------------------------------- Grenzen: Gruppengrösse
select tests.as_admin();
-- Kai besitzt eine Gruppe mit 18 Mitgliedern plus ihm = 19
create temp table t_cap as select gen_random_uuid() as id;
insert into public.groups (id, name, owner_id) select id, 'Cap', '25000000-0000-0000-0000-000000000005' from t_cap;
insert into public.group_members (group_id, user_id, role) select id, '25000000-0000-0000-0000-000000000005', 'owner' from t_cap;
insert into public.group_members (group_id, user_id, role)
    select (select id from t_cap), ('30000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid, 'member' from generate_series(1, 18) as i;
grant select on t_cap to authenticated;
select tests.as_user('25000000-0000-0000-0000-000000000005');
create temp table t_capcode as select public.create_group_invite((select id from t_cap), 20) as code;
grant select on t_capcode to authenticated;
select tests.as_user('23000000-0000-0000-0000-000000000003');
do $$ begin
    perform tests.ok(public.join_group((select code from t_capcode)) = (select id from t_cap), 'Der 20. Platz ist frei: Iris tritt bei');
end $$;
select tests.as_user('26000000-0000-0000-0000-000000000006');
select tests.throws($$select public.join_group((select code from t_capcode))$$, 'group_full');
select tests.as_user('25000000-0000-0000-0000-000000000005');
do $$ begin
    perform tests.ok((select member_count from public.list_groups() where group_id = (select id from t_cap)) = 20, 'Die Gruppe hat 20 Mitglieder');
    perform tests.ok((select is_full from public.preview_group_invite((select code from t_capcode))), 'Vorschau meldet eine volle Gruppe');
end $$;

-- Offene Einladungen zählen gegen die Grenze
select tests.as_admin();
create temp table t_cap2 as select gen_random_uuid() as id;
insert into public.groups (id, name, owner_id) select id, 'Cap2', '21000000-0000-0000-0000-000000000001' from t_cap2;
insert into public.group_members (group_id, user_id, role) select id, '21000000-0000-0000-0000-000000000001', 'owner' from t_cap2;
insert into public.group_members (group_id, user_id, role)
    select (select id from t_cap2), ('30000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid, 'member' from generate_series(1, 18) as i;
insert into public.group_friend_invites (group_id, friend_id, invited_by, expires_at)
    select (select id from t_cap2), ('30000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid, '21000000-0000-0000-0000-000000000001', now() + interval '1 day'
    from generate_series(31, 31) as i;
grant select on t_cap2 to authenticated;
select tests.as_user('21000000-0000-0000-0000-000000000001');
-- 19 Mitglieder + 1 offene Einladung = 20: kein Platz mehr für eine weitere Einladung
select tests.throws($$select public.invite_friends_to_group((select id from t_cap2), array['25000000-0000-0000-0000-000000000005'::uuid])$$, 'group_full');
select tests.throws($$select public.group_load((select id from t_cap2))$$, 'permission denied');

-- ---------------------------------------------------------------- Grenzen: Mitgliedschaften
-- Jonas (U4) ist schon in 10 Gruppen (angelegt von anderen Nutzern)
select tests.as_admin();
do $$ declare gid uuid; begin
    for i in 1..10 loop
        gid := gen_random_uuid();
        insert into public.groups (id, name, owner_id) values (gid, 'Fremd' || i, ('30000000-0000-0000-0000-' || lpad((20 + i)::text, 12, '0'))::uuid);
        insert into public.group_members (group_id, user_id, role) values (gid, ('30000000-0000-0000-0000-' || lpad((20 + i)::text, 12, '0'))::uuid, 'owner');
        insert into public.group_members (group_id, user_id, role) values (gid, '24000000-0000-0000-0000-000000000004', 'member');
    end loop;
end $$;
select tests.as_user('24000000-0000-0000-0000-000000000004');
select tests.throws($$select public.join_group((select code from t_capcode))$$, 'too_many_memberships');
do $$ begin
    perform tests.ok((select count(*) from public.list_groups()) = 10, 'Jonas hat zehn Gruppen');
end $$;

-- ---------------------------------------------------------------- Eigentümerwechsel
-- Gruppe "Own": Kai besitzt sie, Iris (seit 2 Tagen) und Lena (seit 1 Tag) sind Mitglieder
select tests.as_admin();
create temp table t_own as select gen_random_uuid() as id;
grant select on t_own to authenticated;
insert into public.groups (id, name, owner_id) select id, 'Own', '25000000-0000-0000-0000-000000000005' from t_own;
insert into public.group_members (group_id, user_id, role, joined_at) select id, '25000000-0000-0000-0000-000000000005', 'owner', now() - interval '3 days' from t_own;
insert into public.group_members (group_id, user_id, role, joined_at) select id, '26000000-0000-0000-0000-000000000006', 'member', now() - interval '1 day' from t_own;
insert into public.group_members (group_id, user_id, role, joined_at) select id, '23000000-0000-0000-0000-000000000003', 'member', now() - interval '2 days' from t_own;
select tests.as_user('25000000-0000-0000-0000-000000000005');
select public.leave_group((select id from t_own));
select tests.as_admin();
do $$ begin
    perform tests.ok((select owner_id from public.groups where id = (select id from t_own)) = '23000000-0000-0000-0000-000000000003', 'Verlässt der Ersteller die Gruppe, wird das am längsten dabei Seiende neuer Ersteller');
    perform tests.ok((select role from public.group_members where group_id = (select id from t_own) and user_id = '23000000-0000-0000-0000-000000000003') = 'owner', 'Iris hat jetzt die Rolle owner');
    perform tests.ok((select count(*) from public.group_members where group_id = (select id from t_own) and user_id = '25000000-0000-0000-0000-000000000005') = 0, 'Kai ist nicht mehr Mitglied');
end $$;
-- Konto löschen: Iris ist Ersteller, Lena erbt
select tests.as_user('23000000-0000-0000-0000-000000000003');
select public.delete_account();
select tests.as_admin();
do $$ begin
    perform tests.ok((select owner_id from public.groups where id = (select id from t_own)) = '26000000-0000-0000-0000-000000000006', 'Konto löschen: die Gruppe geht an das nächste Mitglied');
    perform tests.ok((select count(*) from public.group_members where user_id = '23000000-0000-0000-0000-000000000003') = 0, 'Konto löschen entfernt die Mitgliedschaften');
end $$;
-- Lena löscht ihr Konto als letztes Mitglied: die Gruppe verschwindet
select tests.as_user('26000000-0000-0000-0000-000000000006');
select public.delete_account();
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.groups where id = (select id from t_own)) = 0, 'Ohne Mitglieder wird die Gruppe gelöscht');
end $$;
-- Allein austreten löscht ebenfalls
select tests.as_user('25000000-0000-0000-0000-000000000005');
create temp table t_solo as select public.create_group('Solo', 3) as id;
grant select on t_solo to authenticated;
select public.leave_group((select id from t_solo));
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.groups where id = (select id from t_solo)) = 0, 'Der letzte Austritt löscht die Gruppe');
end $$;

-- ---------------------------------------------------------------- Aufräumen
select tests.as_user('21000000-0000-0000-0000-000000000001');
create temp table t_old_g as select public.create_group('Alt', 1) as id;
create temp table t_new_g as select public.create_group('Neu', 1) as id;
grant select on t_old_g, t_new_g to authenticated;
select tests.as_admin();
update public.groups set expires_at = now() - interval '8 days' where id = (select id from t_old_g);
update public.groups set expires_at = now() - interval '2 days' where id = (select id from t_new_g);
update public.group_invites set expires_at = now() - interval '8 days' where code = (select code from t_old);
insert into public.group_friend_invites (group_id, friend_id, invited_by, expires_at)
    values ((select id from t_new_g), '30000000-0000-0000-0000-000000000032', '21000000-0000-0000-0000-000000000001', now() - interval '2 days');
select public.cleanup_expired();
do $$ begin
    perform tests.ok((select count(*) from public.groups where id = (select id from t_old_g)) = 0, 'Aufräumen löscht Gruppen sieben Tage nach Ablauf');
    perform tests.ok((select count(*) from public.groups where id = (select id from t_new_g)) = 1, 'Kürzlich abgelaufene Gruppen bleiben (verlängerbar)');
    perform tests.ok((select count(*) from public.group_members where group_id = (select id from t_old_g)) = 0, 'Mit der Gruppe verschwinden die Mitglieder');
    perform tests.ok((select count(*) from public.group_invites where code = (select code from t_old)) = 0, 'Alte Codes werden gelöscht');
    perform tests.ok((select count(*) from public.group_friend_invites where friend_id = '30000000-0000-0000-0000-000000000032') = 0, 'Alte Einladungen an Freunde werden gelöscht');
end $$;
select tests.as_user('21000000-0000-0000-0000-000000000001');
select tests.throws($$select * from public.cleanup_expired()$$, 'permission denied');

-- ---------------------------------------------------------------- Direkter Schreibzugriff ist verboten
select tests.throws($$insert into public.group_members (group_id, user_id, role) values ((select id from t_g), auth.uid(), 'owner')$$, 'permission denied');
select tests.throws($$update public.groups set name = 'Hack' where true$$, 'permission denied');
select tests.throws($$delete from public.group_members$$, 'permission denied');
select tests.throws($$update public.group_members set share_location = false$$, 'permission denied');
select tests.throws($$insert into public.group_friend_invites (group_id, friend_id, invited_by, expires_at) values ((select id from t_g), auth.uid(), auth.uid(), now())$$, 'permission denied');

-- ---------------------------------------------------------------- Fehlversuche werden gebremst (gemeinsam mit den Freundes-Codes)
select tests.as_user('27000000-0000-0000-0000-000000000007');
insert into public.profiles (id, display_name) values (auth.uid(), 'Mia');
do $$ begin
    for i in 1..10 loop perform public.code_kind('AAAAA' || i); end loop;
end $$;
select tests.throws($$select public.code_kind('BBBBBB')$$, 'too_many_attempts');
select tests.throws($$select public.join_group((select code from t_code3))$$, 'too_many_attempts');
select tests.throws($$select * from public.preview_group_invite((select code from t_code3))$$, 'too_many_attempts');
select tests.throws($$select public.redeem_invite('CCCCCC')$$, 'too_many_attempts');

rollback;

select 'ALLE GRUPPEN-TESTS BESTANDEN' as ergebnis;

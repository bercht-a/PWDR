-- Tests der Community-Meldungen (Phase 7). Läuft nach 10_rls_tests.sql, nutzt dessen Hilfsfunktionen im Schema tests.

begin;

insert into auth.users (id, email) values
    ('e0000000-0000-0000-0000-00000000000e', 'e@test'),
    ('f0000000-0000-0000-0000-00000000000f', 'f@test'),
    ('01000000-0000-0000-0000-000000000001', 'g@test'),
    ('02000000-0000-0000-0000-000000000002', 'h@test');

-- ---- Zugriff nur über Funktionen
select tests.as_anon();
select tests.throws($$select public.post_report('queue', 46.4, 7.7, 'x')$$, 'permission denied');
select tests.throws($$select * from public.list_reports(46.4, 7.7)$$, 'permission denied');
select tests.as_user('e0000000-0000-0000-0000-00000000000e');
select tests.throws($$select * from public.reports$$, 'permission denied');
select tests.throws($$insert into public.reports (user_id, type, geom, lat, lon, expires_at) values (auth.uid(), 'queue', 'POINT(7 46)', 46, 7, now() + interval '1 hour')$$, 'permission denied');
select tests.throws($$select * from public.report_votes$$, 'permission denied');

-- ---- Erstellen und Prüfen
create temp table t_rep as select public.post_report('queue', 46.4, 7.7, '  Lift Gandegg: 20 Minuten  ') as id;
grant select on t_rep to authenticated;
do $$ begin
    perform tests.ok((select text from public.list_reports(46.4, 7.7) limit 1) = 'Lift Gandegg: 20 Minuten', 'Text wird getrimmt gespeichert');
    perform tests.ok((select mine from public.list_reports(46.4, 7.7) limit 1), 'Eigene Meldung ist als eigene markiert');
end $$;
select tests.throws($$select public.post_report('quatsch', 46.4, 7.7, null)$$, 'invalid_type');
select tests.throws($$select public.post_report('queue', 99, 7.7, null)$$, 'invalid_position');
select tests.throws($$select public.post_report('queue', 46.4, 7.7, repeat('x', 141))$$, 'text_too_long');
do $$ begin
    perform tests.ok((select text from public.list_reports(46.4, 7.7) where id = public.post_report('snow', 46.41, 7.71, '   ')) is null, 'Leerer Text wird null');
end $$;

-- Après-Ski (0005): neuer Typ, 6 Stunden
select tests.as_user('e0000000-0000-0000-0000-00000000000e');
select public.post_report('apres', 46.43, 7.73, 'Gandegg Hütte');
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.reports where type = 'apres' and expires_at between now() + interval '359 minutes' and now() + interval '361 minutes') = 1, 'Après-Ski läuft nach 6 h ab');
end $$;
select tests.as_user('e0000000-0000-0000-0000-00000000000e');
select tests.throws($$select public.post_report('quatsch', 46.4, 7.7, null)$$, 'invalid_type');

-- Ablaufzeit nach Typ
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.reports where type = 'queue' and expires_at between now() + interval '119 minutes' and now() + interval '121 minutes') = 1, 'Andrang läuft nach 2 h ab');
    perform tests.ok((select count(*) from public.reports where type = 'snow' and expires_at between now() + interval '359 minutes' and now() + interval '361 minutes') = 1, 'Neuschnee läuft nach 6 h ab');
end $$;

-- ---- Sichtbarkeit: andere Nutzer sehen sie, aber nicht als eigene, und nur im Umkreis
select tests.as_user('f0000000-0000-0000-0000-00000000000f');
do $$ begin
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7)) = 3, 'Anderer Nutzer sieht die Meldungen im Umkreis');
    perform tests.ok(not (select bool_or(mine) from public.list_reports(46.4, 7.7)), 'Für andere ist keine Meldung eigene');
    perform tests.ok((select count(*) from public.list_reports(47.4, 8.5)) = 0, 'Weit entfernt: keine Meldungen');
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7, 1)) = 1, 'Radius wird auf mindestens 100 m angehoben (1,3 km entfernte Meldung fehlt)');
end $$;

-- ---- Abstimmen
do $$ begin
    perform public.vote_report((select id from t_rep), 1);
    perform tests.ok((select up_votes from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 1, 'Daumen hoch zählt');
    perform tests.ok((select my_vote from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 1, 'Eigene Stimme wird angezeigt');
    perform public.vote_report((select id from t_rep), -1);
    perform tests.ok((select up_votes from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 0
                 and (select down_votes from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 1, 'Stimme ändern ersetzt die alte, zählt nicht doppelt');
    perform public.vote_report((select id from t_rep), 0);
    perform tests.ok((select down_votes from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 0, 'Stimme zurücknehmen');
end $$;
select tests.throws($$select public.vote_report((select id from t_rep), 5)$$, 'invalid_vote');
select tests.throws($$select public.vote_report('11111111-1111-1111-1111-111111111111', 1)$$, 'report_not_found');
select tests.as_user('e0000000-0000-0000-0000-00000000000e');
select tests.throws($$select public.vote_report((select id from t_rep), 1)$$, 'own_report');

-- ---- Ausblenden bei vielen Daumen runter (3 runter, 0 hoch)
select tests.as_user('f0000000-0000-0000-0000-00000000000f');
do $$ begin perform public.vote_report((select id from t_rep), -1); end $$;
select tests.as_user('01000000-0000-0000-0000-000000000001');
do $$ begin perform public.vote_report((select id from t_rep), -1); end $$;
select tests.as_user('02000000-0000-0000-0000-000000000002');
do $$ begin
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 1, 'Bei 2 Daumen runter noch sichtbar');
    perform public.vote_report((select id from t_rep), -1);
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 0, 'Bei 3 Daumen runter ausgeblendet');
    perform public.vote_report((select id from t_rep), 1);
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 1, 'Ein Daumen hoch bringt sie bei 2 gegen 1 zurück');
end $$;

-- ---- Abgelaufene Meldungen fehlen
select tests.as_admin();
update public.reports set expires_at = now() - interval '1 minute' where id = (select id from t_rep);
select tests.as_user('f0000000-0000-0000-0000-00000000000f');
do $$ begin
    perform tests.ok((select count(*) from public.list_reports(46.4, 7.7) where id = (select id from t_rep)) = 0, 'Abgelaufene Meldung wird nicht angezeigt');
end $$;
select tests.throws($$select public.vote_report((select id from t_rep), 1)$$, 'report_not_found');

-- ---- Löschen: nur eigene
select tests.as_user('f0000000-0000-0000-0000-00000000000f');
create temp table t_rep2 as select public.post_report('piste', 46.42, 7.72, 'Eisig') as id;
grant select on t_rep2 to authenticated;
select tests.as_user('e0000000-0000-0000-0000-00000000000e');
select tests.throws($$select public.delete_report((select id from t_rep2))$$, 'report_not_found');
select tests.as_user('f0000000-0000-0000-0000-00000000000f');
select public.delete_report((select id from t_rep2));
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.reports where id = (select id from t_rep2)) = 0, 'Eigene Meldung gelöscht');
end $$;

-- ---- Begrenzung: 10 pro Stunde
select tests.as_user('02000000-0000-0000-0000-000000000002');
do $$ begin
    for i in 1..10 loop perform public.post_report('other', 46.5, 7.8, null); end loop;
end $$;
select tests.throws($$select public.post_report('other', 46.5, 7.8, null)$$, 'too_many_reports');

-- ---- Aufräumen und Konto löschen
select tests.as_admin();
update public.reports set expires_at = now() - interval '2 days' where id = (select id from t_rep);
select public.cleanup_expired();
do $$ begin
    perform tests.ok((select count(*) from public.reports where id = (select id from t_rep)) = 0, 'Aufräumen löscht Meldungen einen Tag nach Ablauf');
end $$;
select tests.as_user('02000000-0000-0000-0000-000000000002');
select public.delete_account();
select tests.as_admin();
do $$ begin
    perform tests.ok((select count(*) from public.reports where user_id = '02000000-0000-0000-0000-000000000002') = 0, 'Konto löschen entfernt die Meldungen');
    perform tests.ok((select count(*) from public.report_votes where user_id = '02000000-0000-0000-0000-000000000002') = 0, 'Konto löschen entfernt die Stimmen');
end $$;

rollback;

select 'ALLE MELDUNGS-TESTS BESTANDEN' as ergebnis;

-- PWDR Backend: Suchpfad der Funktionen um das Schema "extensions" erweitern.
--
-- Fehler in 0001/0002: Die Funktionen suchten nur in "public" und "pg_temp". In Supabase liegen Erweiterungen wie
-- pgcrypto und PostGIS aber im Schema "extensions". Dadurch scheiterte create_invite() mit
-- "function gen_random_bytes(integer) does not exist", und update_location() konnte die Position nicht speichern.
--
-- Im SQL Editor von Supabase ausführen, wenn 0001 und 0002 schon eingespielt sind. Das Skript ist wiederholbar.

do $$
declare r record;
begin
    for r in
        select p.oid::regprocedure as sig
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname in ('is_friend', 'can_view_location', 'create_invite', 'redeem_invite',
                            'revoke_sharing_between', 'remove_friend', 'block_friend', 'unblock_friend',
                            'list_friends', 'start_share', 'set_share_target', 'stop_share',
                            'update_location', 'delete_account', 'cleanup_expired')
    loop
        execute format('alter function %s set search_path = public, extensions, pg_temp', r.sig);
    end loop;
end $$;

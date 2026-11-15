-- PWDR Backend: Meldungstyp "Après-Ski" (apres). Läuft nach 0004_reports.sql, im SQL Editor von Supabase ausführen.
-- Das Skript ist wiederholbar.

-- Die Typ-Prüfung der Tabelle um 'apres' erweitern (der Name der alten Prüfung ist vom System vergeben).
do $$
declare c record;
begin
    for c in
        select conname from pg_constraint
        where conrelid = 'public.reports'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%queue%'
    loop
        execute format('alter table public.reports drop constraint %I', c.conname);
    end loop;
    alter table public.reports add constraint reports_type_check
        check (type in ('queue', 'snow', 'piste', 'warning', 'apres', 'other'));
end $$;

-- Wie in 0004, mit Après-Ski (6 Stunden).
create or replace function public.post_report(p_type text, p_lat double precision, p_lon double precision, p_text text)
returns uuid
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid uuid := auth.uid();
    hours int;
    clean text := nullif(btrim(coalesce(p_text, '')), '');
    rid uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    hours := case p_type
        when 'queue' then 2 when 'piste' then 4 when 'warning' then 4 when 'snow' then 6
        when 'apres' then 6 when 'other' then 2 end;
    if hours is null then raise exception 'invalid_type'; end if;
    if p_lat not between -90 and 90 or p_lon not between -180 and 180 then raise exception 'invalid_position'; end if;
    if clean is not null and char_length(clean) > 140 then raise exception 'text_too_long'; end if;
    if (select count(*) from public.reports where user_id = uid and created_at > now() - interval '1 hour') >= 10 then
        raise exception 'too_many_reports';
    end if;

    insert into public.reports (user_id, type, geom, lat, lon, text, expires_at)
    values (uid, p_type, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography, p_lat, p_lon, clean,
            now() + make_interval(hours => hours))
    returning id into rid;
    return rid;
end $$;

revoke all on function public.post_report(text, double precision, double precision, text) from public, anon;
grant execute on function public.post_report(text, double precision, double precision, text) to authenticated;

-- PWDR Backend: Breite und Länge als einfache Spalten in live_locations.
-- Die App liest die Live-Positionen per Realtime. Der PostGIS-Typ kommt dort als Hex-Text an,
-- einfache Zahlenspalten sind robuster. geom bleibt für räumliche Abfragen erhalten.
-- Im SQL Editor von Supabase nach 0001_social.sql ausführen.

alter table public.live_locations
    add column if not exists lat double precision,
    add column if not exists lon double precision;

create or replace function public.update_location(
    p_lat double precision, p_lon double precision,
    p_alt real, p_speed real, p_heading real, p_accuracy real, p_state text
) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
    uid uuid := auth.uid();
    sid uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if p_lat not between -90 and 90 or p_lon not between -180 and 180 then raise exception 'invalid_position'; end if;
    if p_state is not null and p_state not in ('downhill', 'lift', 'pause') then raise exception 'invalid_state'; end if;
    select id into sid from public.share_sessions
        where user_id = uid and ended_at is null and expires_at > now();
    if sid is null then raise exception 'no_active_share'; end if;

    insert into public.live_locations (user_id, session_id, geom, lat, lon, alt, speed, heading, accuracy, state, updated_at)
    values (uid, sid, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography, p_lat, p_lon,
            p_alt, p_speed, p_heading, p_accuracy, p_state, now())
    on conflict (user_id) do update set
        session_id = excluded.session_id, geom = excluded.geom, lat = excluded.lat, lon = excluded.lon,
        alt = excluded.alt, speed = excluded.speed, heading = excluded.heading, accuracy = excluded.accuracy,
        state = excluded.state, updated_at = excluded.updated_at;
end $$;

-- Ausführungsrechte wie in 0001 (create or replace behält sie, hier zur Sicherheit erneut gesetzt).
revoke all on function public.update_location(double precision, double precision, real, real, real, real, text) from public, anon;
grant execute on function public.update_location(double precision, double precision, real, real, real, real, text) to authenticated;

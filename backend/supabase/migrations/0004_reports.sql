-- PWDR Backend, Phase 7: Community-Meldungen ("Hier ist viel los", Pistenzustand, Neuschnee, Warnung).
-- Siehe PLAN.md Abschnitt 5c. Im SQL Editor von Supabase nach 0001 bis 0003 ausführen.
-- Grundsatz wie bisher: Clients haben keinen direkten Zugriff auf die Tabellen, alles läuft über die Funktionen unten.
-- Meldungen sind für alle angemeldeten Nutzer im Umkreis sichtbar. Der Verfasser wird nicht angezeigt.

create table public.reports (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references auth.users (id) on delete cascade,
    type       text not null check (type in ('queue', 'snow', 'piste', 'warning', 'other')),
    geom       geography(Point, 4326) not null,
    lat        double precision not null,
    lon        double precision not null,
    text       text check (text is null or char_length(text) between 1 and 140),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);
create index on public.reports using gist (geom);
create index on public.reports (user_id, created_at);
create index on public.reports (expires_at);

create table public.report_votes (
    report_id uuid not null references public.reports (id) on delete cascade,
    user_id   uuid not null references auth.users (id) on delete cascade,
    vote      smallint not null check (vote in (-1, 1)),
    primary key (report_id, user_id)
);

alter table public.reports       enable row level security;
alter table public.report_votes  enable row level security;
-- Keine Policy und keine Rechte: Zugriff nur über die Funktionen.
revoke all on public.reports, public.report_votes from anon, authenticated;

-- Meldung erstellen. Höchstens 10 pro Stunde. Die Ablaufzeit hängt vom Typ ab.
create function public.post_report(p_type text, p_lat double precision, p_lon double precision, p_text text)
returns uuid
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid uuid := auth.uid();
    hours int;
    clean text := nullif(btrim(coalesce(p_text, '')), '');
    rid uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    hours := case p_type when 'queue' then 2 when 'piste' then 4 when 'warning' then 4 when 'snow' then 6 when 'other' then 2 end;
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

-- Meldungen im Umkreis (Standard 30 km, höchstens 100 km). Abgelaufene fehlen. Bei vielen Daumen runter
-- (mindestens 3 und mehr als Daumen hoch) wird eine Meldung ausgeblendet.
create function public.list_reports(p_lat double precision, p_lon double precision, p_radius_m integer default 30000)
returns table (
    id uuid, type text, lat double precision, lon double precision, text text,
    created_at timestamptz, expires_at timestamptz,
    up_votes integer, down_votes integer, my_vote integer, mine boolean
)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    return query
    select r.id, r.type, r.lat, r.lon, r.text, r.created_at, r.expires_at,
           coalesce(v.up, 0)::integer, coalesce(v.down, 0)::integer,
           coalesce(mv.vote, 0)::integer, (r.user_id = uid)
    from public.reports r
    left join lateral (
        select count(*) filter (where vote = 1) as up, count(*) filter (where vote = -1) as down
        from public.report_votes where report_id = r.id
    ) v on true
    left join public.report_votes mv on mv.report_id = r.id and mv.user_id = uid
    where r.expires_at > now()
      and st_dwithin(r.geom, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography, least(greatest(p_radius_m, 100), 100000))
      and not (coalesce(v.down, 0) >= 3 and coalesce(v.down, 0) > coalesce(v.up, 0))
    order by r.created_at desc
    limit 200;
end $$;

-- Daumen hoch (1), runter (-1) oder zurücknehmen (0). Nicht für die eigene Meldung.
create function public.vote_report(p_id uuid, p_vote integer) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid uuid := auth.uid();
    owner uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if p_vote not in (-1, 0, 1) then raise exception 'invalid_vote'; end if;
    select user_id into owner from public.reports where id = p_id and expires_at > now();
    if owner is null then raise exception 'report_not_found'; end if;
    if owner = uid then raise exception 'own_report'; end if;
    if p_vote = 0 then
        delete from public.report_votes where report_id = p_id and user_id = uid;
    else
        insert into public.report_votes (report_id, user_id, vote) values (p_id, uid, p_vote)
        on conflict (report_id, user_id) do update set vote = excluded.vote;
    end if;
end $$;

-- Eigene Meldung löschen.
create function public.delete_report(p_id uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    delete from public.reports where id = p_id and user_id = uid;
    if not found then raise exception 'report_not_found'; end if;
end $$;

-- Aufräumen erweitern: alte Meldungen löschen (einen Tag nach Ablauf).
create or replace function public.cleanup_expired() returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    update public.share_sessions set ended_at = least(now(), expires_at)
        where ended_at is null and expires_at <= now();
    delete from public.live_locations l using public.share_sessions s
        where s.id = l.session_id and (s.ended_at is not null or s.expires_at <= now());
    delete from public.redeem_failures where at < now() - interval '1 day';
    delete from public.invites where expires_at < now() - interval '7 days';
    delete from public.reports where expires_at < now() - interval '1 day';
end $$;

do $$
declare r record;
begin
    for r in
        select p.oid::regprocedure as sig
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname in ('post_report', 'list_reports', 'vote_report', 'delete_report')
    loop
        execute format('revoke all on function %s from public, anon', r.sig);
        execute format('grant execute on function %s to authenticated', r.sig);
    end loop;
end $$;

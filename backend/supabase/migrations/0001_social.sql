-- PWDR Backend, Phase 4: Profile, Freunde, Einladungen, Standort teilen.
-- Siehe PLAN.md Abschnitt 5c. Läuft in Supabase (Postgres mit PostGIS, Auth, Realtime).
-- Grundsatz: Clients lesen nur über Row Level Security und schreiben ausschliesslich über die Funktionen unten.
-- Es gibt keine Standortgeschichte, live_locations hält pro Nutzer nur den letzten Stand.

create extension if not exists postgis;
create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- Tabellen

create table public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    display_name text not null check (char_length(display_name) between 1 and 30),
    spoken_name  text check (spoken_name is null or char_length(spoken_name) between 1 and 30),
    color        text not null default '#4CC9F0' check (color ~ '^#[0-9A-Fa-f]{6}$'),
    created_at   timestamptz not null default now()
);

-- Eine Zeile pro Paar, user_a < user_b.
create table public.friendships (
    id         bigint generated always as identity primary key,
    user_a     uuid not null references auth.users (id) on delete cascade,
    user_b     uuid not null references auth.users (id) on delete cascade,
    status     text not null check (status in ('accepted', 'blocked')),
    blocked_by uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now(),
    check (user_a < user_b),
    unique (user_a, user_b)
);

create table public.invites (
    code       text primary key check (code ~ '^[A-Z2-9]{6}$'),
    created_by uuid not null references auth.users (id) on delete cascade,
    expires_at timestamptz not null,
    used_by    uuid references auth.users (id) on delete set null,
    used_at    timestamptz
);

-- Fehlversuche beim Einlösen, gegen Raten von Codes.
create table public.redeem_failures (
    id      bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    at      timestamptz not null default now()
);
create index on public.redeem_failures (user_id, at);

create table public.share_sessions (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references auth.users (id) on delete cascade,
    started_at timestamptz not null default now(),
    expires_at timestamptz not null,
    ended_at   timestamptz
);
create index on public.share_sessions (user_id) where ended_at is null;

create table public.share_targets (
    session_id uuid not null references public.share_sessions (id) on delete cascade,
    friend_id  uuid not null references auth.users (id) on delete cascade,
    primary key (session_id, friend_id)
);

create table public.live_locations (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    session_id uuid not null references public.share_sessions (id) on delete cascade,
    geom       geography(Point, 4326) not null,
    alt        real,
    speed      real,
    heading    real,
    accuracy   real,
    state      text check (state in ('downhill', 'lift', 'pause')),
    updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- Hilfsfunktionen (umgehen RLS gezielt)

create function public.is_friend(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
    select exists (
        select 1 from public.friendships f
        where f.user_a = least(a, b) and f.user_b = greatest(a, b) and f.status = 'accepted'
    );
$$;

-- Darf der Aufrufer die Live-Position des Besitzers sehen?
-- Aktive Sitzung, Aufrufer ist freigegeben, und die Freundschaft ist akzeptiert (nicht blockiert).
create function public.can_view_location(owner uuid, sess uuid) returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
    select auth.uid() is not null and (
        owner = auth.uid()
        or exists (
            select 1
            from public.share_sessions s
            join public.share_targets t on t.session_id = s.id
            where s.id = sess
              and s.user_id = owner
              and s.ended_at is null
              and s.expires_at > now()
              and t.friend_id = auth.uid()
              and public.is_friend(owner, auth.uid())
        )
    );
$$;

-- ---------------------------------------------------------------- Row Level Security

alter table public.profiles        enable row level security;
alter table public.friendships     enable row level security;
alter table public.invites         enable row level security;
alter table public.redeem_failures enable row level security;
alter table public.share_sessions  enable row level security;
alter table public.share_targets   enable row level security;
alter table public.live_locations  enable row level security;

create policy profiles_select on public.profiles for select to authenticated
    using (id = auth.uid() or public.is_friend(id, auth.uid()));
create policy profiles_insert on public.profiles for insert to authenticated
    with check (id = auth.uid());
create policy profiles_update on public.profiles for update to authenticated
    using (id = auth.uid()) with check (id = auth.uid());

create policy friendships_select on public.friendships for select to authenticated
    using (auth.uid() in (user_a, user_b));

-- Einladungen und Fehlversuche: nur über Funktionen, keine Policy = kein direkter Zugriff.

create policy share_sessions_select on public.share_sessions for select to authenticated
    using (user_id = auth.uid());

create policy share_targets_select on public.share_targets for select to authenticated
    using (exists (select 1 from public.share_sessions s where s.id = session_id and s.user_id = auth.uid()));

create policy live_locations_select on public.live_locations for select to authenticated
    using (public.can_view_location(user_id, session_id));

-- Schreibrechte der Clients: nur eigenes Profil. Alles andere läuft über Funktionen.
revoke all on all tables in schema public from anon;
revoke insert, update, delete, truncate on
    public.friendships, public.invites, public.redeem_failures,
    public.share_sessions, public.share_targets, public.live_locations
    from authenticated;
revoke select on public.invites, public.redeem_failures from authenticated;
revoke delete, truncate on public.profiles from authenticated;

-- ---------------------------------------------------------------- Funktionen für den Client

create function public.create_invite() returns text
language plpgsql security definer set search_path = public, pg_temp as $$
declare
    uid      uuid := auth.uid();
    alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  -- ohne 0, O, 1, I
    c        text;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if (select count(*) from public.invites where created_by = uid and used_by is null and expires_at > now()) >= 10 then
        raise exception 'too_many_invites';
    end if;
    loop
        c := '';
        for i in 1..6 loop
            -- 256 ist durch 32 teilbar, deshalb ohne Verzerrung
            c := c || substr(alphabet, 1 + (get_byte(gen_random_bytes(1), 0) % 32), 1);
        end loop;
        begin
            insert into public.invites (code, created_by, expires_at) values (c, uid, now() + interval '24 hours');
            return c;
        exception when unique_violation then
            -- Code existiert schon, neuen würfeln
        end;
    end loop;
end $$;

-- Gibt die ID des Einladenden zurück, bei ungültigem Code null (zählt als Fehlversuch).
create function public.redeem_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
    uid uuid := auth.uid();
    inv public.invites;
    existing public.friendships;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if (select count(*) from public.redeem_failures where user_id = uid and at > now() - interval '1 hour') >= 10 then
        raise exception 'too_many_attempts';
    end if;

    select * into inv from public.invites where code = upper(btrim(p_code)) for update;
    if not found or inv.used_by is not null or inv.expires_at <= now() or inv.created_by = uid then
        insert into public.redeem_failures (user_id) values (uid);
        return null;
    end if;

    select * into existing from public.friendships
        where user_a = least(uid, inv.created_by) and user_b = greatest(uid, inv.created_by);
    if found and existing.status = 'blocked' then
        -- Eine Blockierung lässt sich nicht per Einladung umgehen. Antwort wie bei ungültigem Code.
        insert into public.redeem_failures (user_id) values (uid);
        return null;
    end if;
    if not found then
        insert into public.friendships (user_a, user_b, status)
        values (least(uid, inv.created_by), greatest(uid, inv.created_by), 'accepted');
    end if;

    update public.invites set used_by = uid, used_at = now() where code = inv.code;
    return inv.created_by;
end $$;

-- Beendet alle Freigaben zwischen den beiden in beide Richtungen.
create function public.revoke_sharing_between(a uuid, b uuid) returns void
language sql security definer set search_path = public, pg_temp as $$
    delete from public.share_targets t using public.share_sessions s
    where t.session_id = s.id
      and ((s.user_id = a and t.friend_id = b) or (s.user_id = b and t.friend_id = a));
$$;

create function public.remove_friend(p_friend uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.revoke_sharing_between(uid, p_friend);
    delete from public.friendships
        where user_a = least(uid, p_friend) and user_b = greatest(uid, p_friend) and status = 'accepted';
end $$;

create function public.block_friend(p_friend uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if uid = p_friend then raise exception 'invalid_target'; end if;
    perform public.revoke_sharing_between(uid, p_friend);
    insert into public.friendships (user_a, user_b, status, blocked_by)
    values (least(uid, p_friend), greatest(uid, p_friend), 'blocked', uid)
    on conflict (user_a, user_b) do update set status = 'blocked', blocked_by = uid;
end $$;

-- Hebt nur eine eigene Blockierung auf. Danach besteht keine Freundschaft, eine neue Einladung ist nötig.
create function public.unblock_friend(p_friend uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    delete from public.friendships
        where user_a = least(uid, p_friend) and user_b = greatest(uid, p_friend)
          and status = 'blocked' and blocked_by = uid;
end $$;

create function public.list_friends()
returns table (friend_id uuid, display_name text, spoken_name text, color text, status text)
language sql stable security definer set search_path = public, pg_temp as $$
    select p.id, p.display_name, p.spoken_name, p.color, f.status
    from public.friendships f
    join public.profiles p on p.id = case when f.user_a = auth.uid() then f.user_b else f.user_a end
    where auth.uid() in (f.user_a, f.user_b)
      and (f.status = 'accepted' or f.blocked_by = auth.uid());
$$;

-- Startet das Teilen. p_hours 1 bis 12 (null = 12), nur akzeptierte Freunde aus p_friends werden freigegeben.
create function public.start_share(p_hours integer, p_friends uuid[]) returns uuid
language plpgsql security definer set search_path = public, pg_temp as $$
declare
    uid uuid := auth.uid();
    sid uuid;
    hours integer := coalesce(p_hours, 12);
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if hours < 1 or hours > 12 then raise exception 'invalid_duration'; end if;

    perform public.stop_share();
    insert into public.share_sessions (user_id, expires_at)
    values (uid, now() + make_interval(hours => hours)) returning id into sid;
    insert into public.share_targets (session_id, friend_id)
    select sid, f from unnest(coalesce(p_friends, '{}')) as f where public.is_friend(uid, f);
    return sid;
end $$;

-- Gibt einen Freund während des Teilens frei oder nimmt die Freigabe zurück.
create function public.set_share_target(p_friend uuid, p_enabled boolean) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
    uid uuid := auth.uid();
    sid uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    select id into sid from public.share_sessions
        where user_id = uid and ended_at is null and expires_at > now();
    if sid is null then raise exception 'no_active_share'; end if;
    if p_enabled then
        if not public.is_friend(uid, p_friend) then raise exception 'not_a_friend'; end if;
        insert into public.share_targets (session_id, friend_id) values (sid, p_friend) on conflict do nothing;
    else
        delete from public.share_targets where session_id = sid and friend_id = p_friend;
    end if;
end $$;

create function public.stop_share() returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    update public.share_sessions set ended_at = now() where user_id = uid and ended_at is null;
    delete from public.live_locations where user_id = uid;
end $$;

-- Überschreibt die eigene Live-Zeile. Ohne aktive Sitzung gibt es einen Fehler, nichts wird gespeichert.
create function public.update_location(
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

    insert into public.live_locations (user_id, session_id, geom, alt, speed, heading, accuracy, state, updated_at)
    values (uid, sid, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
            p_alt, p_speed, p_heading, p_accuracy, p_state, now())
    on conflict (user_id) do update set
        session_id = excluded.session_id, geom = excluded.geom, alt = excluded.alt, speed = excluded.speed,
        heading = excluded.heading, accuracy = excluded.accuracy, state = excluded.state, updated_at = excluded.updated_at;
end $$;

-- Löscht das Konto samt Profil, Freundschaften, Freigaben und Live-Daten (über ON DELETE CASCADE).
create function public.delete_account() returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    delete from auth.users where id = auth.uid();
end $$;

-- Aufräumen, vom Zeitplan aufgerufen (nicht vom Client).
create function public.cleanup_expired() returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
    update public.share_sessions set ended_at = least(now(), expires_at)
        where ended_at is null and expires_at <= now();
    delete from public.live_locations l using public.share_sessions s
        where s.id = l.session_id and (s.ended_at is not null or s.expires_at <= now());
    delete from public.redeem_failures where at < now() - interval '1 day';
    delete from public.invites where expires_at < now() - interval '7 days';
end $$;

-- ---------------------------------------------------------------- Ausführungsrechte

do $$
declare r record;
begin
    for r in
        select p.oid::regprocedure as sig, p.proname
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname in ('is_friend', 'can_view_location', 'create_invite', 'redeem_invite',
                            'revoke_sharing_between', 'remove_friend', 'block_friend', 'unblock_friend',
                            'list_friends', 'start_share', 'set_share_target', 'stop_share',
                            'update_location', 'delete_account', 'cleanup_expired')
    loop
        execute format('revoke all on function %s from public, anon', r.sig);
        if r.proname in ('revoke_sharing_between', 'cleanup_expired') then
            -- Nur intern bzw. für den Zeitplan.
            execute format('revoke all on function %s from authenticated', r.sig);
        else
            execute format('grant execute on function %s to authenticated', r.sig);
        end if;
    end loop;
end $$;

-- ---------------------------------------------------------------- Supabase-spezifisch (nur wenn vorhanden)

do $$
begin
    -- Live-Positionen per Realtime an berechtigte Freunde, die Filterung übernimmt die Policy.
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        alter publication supabase_realtime add table public.live_locations;
    end if;
    -- Abgelaufene Freigaben jede Minute aufräumen.
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule('pwdr-cleanup-expired', '* * * * *', 'select public.cleanup_expired()');
    end if;
end $$;

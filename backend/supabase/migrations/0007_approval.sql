-- PWDR Freigabe neuer Konten (PLAN.md 5g).
-- Wer sich anmeldet, hat ein Konto, aber erst nach der Freigabe durch einen Admin darf er etwas tun. Bis dahin sperrt der Server
-- alles ausser dem eigenen Profil, der Statusabfrage und dem Löschen des Kontos.
--
-- Wie die Sperre wirkt (zwei Schichten, beide serverseitig):
--  1. pwdr_gate(): PostgREST ruft diese Funktion vor JEDER Anfrage auf (Einstellung pgrst.db_pre_request, unten gesetzt).
--     Für nicht freigegebene Konten lässt sie nur drei Pfade durch. Das deckt alle Funktionen und Tabellen ab, auch künftige.
--  2. can_view_location() verlangt ein freigegebenes Konto. Das deckt Realtime ab, das PostgREST umgeht.
--
-- Alle Konten, die es bei der Migration schon gibt, gelten als freigegeben. Wiederholbar ist die Migration nicht
-- (sie würde sonst neue, wartende Konten freigeben).

create table public.approvals (
    user_id     uuid primary key references auth.users (id) on delete cascade,
    approved_at timestamptz not null default now(),
    approved_by uuid references auth.users (id) on delete set null
);

create table public.admins (
    user_id uuid primary key references auth.users (id) on delete cascade
);

alter table public.approvals enable row level security;
alter table public.admins    enable row level security;
-- Keine Policy und keine Rechte: Zugriff nur über die Funktionen.
revoke all on public.approvals, public.admins from anon, authenticated;

insert into public.approvals (user_id) select id from auth.users;

-- ---------------------------------------------------------------- Hilfsfunktionen

create function public.is_approved(u uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select exists (select 1 from public.approvals where user_id = u);
$$;

create function public.is_admin(u uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select exists (select 1 from public.admins where user_id = u);
$$;

-- Die Sperre vor jeder Anfrage. Läuft mit der Rolle der Anfrage (anon oder authenticated).
-- Nicht freigegebene Konten dürfen nur: Profil lesen und schreiben, my_status() abfragen, das Konto löschen.
-- Ohne bekannten Pfad (request.path fehlt) ist die Antwort ebenfalls "gesperrt".
create function public.pwdr_gate() returns void
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
declare
    uid  uuid := auth.uid();
    path text := coalesce(current_setting('request.path', true), '');
begin
    if uid is null then return; end if;
    if exists (select 1 from public.approvals where user_id = uid) then return; end if;
    if path ~ '^(/rest/v1)?/(profiles|rpc/my_status|rpc/delete_account)$' then return; end if;
    raise exception 'not_approved' using errcode = 'PT403';
end $$;

-- ---------------------------------------------------------------- Funktionen für die App

-- Eigener Stand. pending_count ist nur für Admins gefüllt (Anzahl wartender Konten mit bestätigter E-Mail-Adresse).
create function public.my_status()
returns table (approved boolean, is_admin boolean, pending_count integer)
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select public.is_approved(auth.uid()),
           public.is_admin(auth.uid()),
           case when public.is_admin(auth.uid()) then
               (select count(*)::int from auth.users u
                where u.email_confirmed_at is not null
                  and not exists (select 1 from public.approvals a where a.user_id = u.id))
           else 0 end
    where auth.uid() is not null;
$$;

-- Wartende Konten für Admins. Nur Konten, die ihre E-Mail-Adresse bestätigt haben (also wirklich Zugriff auf die Adresse haben).
create function public.admin_list_pending()
returns table (user_id uuid, email text, display_name text, created_at timestamptz)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
begin
    if not public.is_admin(auth.uid()) then raise exception 'not_admin' using errcode = '42501'; end if;
    return query
        select u.id, u.email::text, p.display_name, u.created_at
        from auth.users u
        left join public.profiles p on p.id = u.id
        where u.email_confirmed_at is not null
          and not exists (select 1 from public.approvals a where a.user_id = u.id)
        order by u.created_at
        limit 100;
end $$;

create function public.admin_approve(p_user uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if not public.is_admin(auth.uid()) then raise exception 'not_admin' using errcode = '42501'; end if;
    if not exists (select 1 from auth.users where id = p_user) then raise exception 'no_such_user'; end if;
    insert into public.approvals (user_id, approved_by) values (p_user, auth.uid()) on conflict do nothing;
end $$;

-- Lehnt ein wartendes Konto ab und löscht es. Freigegebene Konten und Admins lassen sich so nicht entfernen.
create function public.admin_reject(p_user uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if not public.is_admin(auth.uid()) then raise exception 'not_admin' using errcode = '42501'; end if;
    if public.is_approved(p_user) or public.is_admin(p_user) then raise exception 'already_approved'; end if;
    delete from auth.users where id = p_user;
end $$;

-- ---------------------------------------------------------------- Realtime und Standort: nur freigegebene Konten

create or replace function public.can_view_location(owner uuid, sess uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select auth.uid() is not null and public.is_approved(auth.uid()) and (
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
        or exists (
            select 1
            from public.share_sessions s
            where s.id = sess
              and s.user_id = owner
              and s.ended_at is null
              and s.expires_at > now()
              and public.sees_via_group(owner, auth.uid())
        )
    );
$$;

-- ---------------------------------------------------------------- Ausführungsrechte

do $$
declare r record;
begin
    for r in
        select p.oid::regprocedure as sig, p.proname
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname in ('is_approved', 'is_admin', 'pwdr_gate', 'my_status', 'admin_list_pending', 'admin_approve',
                            'admin_reject', 'can_view_location')
    loop
        execute format('revoke all on function %s from public, anon', r.sig);
        execute format('grant execute on function %s to authenticated', r.sig);
        -- Die Sperre läuft vor der Anmeldung der Anfrage und muss für beide Rollen aufrufbar sein.
        if r.proname = 'pwdr_gate' then
            execute format('grant execute on function %s to anon', r.sig);
        end if;
    end loop;
end $$;

-- Interne Hilfen nicht direkt aufrufbar
revoke all on function public.is_approved(uuid), public.is_admin(uuid) from authenticated;

-- ---------------------------------------------------------------- Sperre einschalten

-- Die Sperre vor jeder Anfrage. Notausstieg (falls etwas schiefgeht):
--   alter role authenticator reset pgrst.db_pre_request; notify pgrst, 'reload config';
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'authenticator') then
        alter role authenticator set pgrst.db_pre_request = 'public.pwdr_gate';
        notify pgrst, 'reload config';
    end if;
end $$;

-- GraphQL umgeht PostgREST und damit die Sperre. PWDR nutzt es nicht, also den Zugang schliessen.
do $$
begin
    if exists (select 1 from pg_namespace where nspname = 'graphql_public') then
        revoke usage on schema graphql_public from anon, authenticated;
    end if;
    if exists (select 1 from pg_namespace where nspname = 'graphql') then
        revoke usage on schema graphql from anon, authenticated;
    end if;
end $$;

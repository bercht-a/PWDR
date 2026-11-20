-- PWDR Backend, Gruppen: benannte Gruppen, in denen sich alle Mitglieder gegenseitig live sehen.
-- Siehe PLAN.md Abschnitt 5f. Im SQL Editor von Supabase nach 0001 bis 0005 ausführen.
-- Grundsatz wie bisher: Clients haben keinen direkten Zugriff auf die Tabellen, alles läuft über die Funktionen unten.
-- Wer in derselben aktiven Gruppe ist und dort seinen Standort teilt, ist für die anderen Mitglieder sichtbar, solange er
-- aufzeichnet. Freunde werden nie still hinzugefügt: Eine Einladung muss angenommen werden.

-- ---------------------------------------------------------------- Tabellen

create table public.groups (
    id         uuid primary key default gen_random_uuid(),
    name       text not null check (char_length(name) between 1 and 30),
    owner_id   uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz   -- null = unbegrenzt
);
create index on public.groups (owner_id);
create index on public.groups (expires_at) where expires_at is not null;

create table public.group_members (
    group_id       uuid not null references public.groups (id) on delete cascade,
    user_id        uuid not null references auth.users (id) on delete cascade,
    role           text not null check (role in ('owner', 'member')),
    share_location boolean not null default true,
    announce       boolean not null default true,
    joined_at      timestamptz not null default now(),
    primary key (group_id, user_id)
);
create index on public.group_members (user_id);
create unique index group_members_one_owner on public.group_members (group_id) where role = 'owner';

-- Einladungscodes für Personen, die noch keine Freunde sind. Mehrfach einlösbar, aber nie zweimal von derselben Person.
create table public.group_invites (
    code       text primary key check (code ~ '^[A-Z2-9]{6}$'),
    group_id   uuid not null references public.groups (id) on delete cascade,
    created_by uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    max_uses   integer not null check (max_uses between 1 and 20),
    uses       integer not null default 0,
    revoked_at timestamptz
);
create index on public.group_invites (group_id);

-- Offene Einladungen an bestehende Freunde. Bis zur Annahme sieht niemand etwas.
create table public.group_friend_invites (
    group_id   uuid not null references public.groups (id) on delete cascade,
    friend_id  uuid not null references auth.users (id) on delete cascade,
    invited_by uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    primary key (group_id, friend_id)
);
create index on public.group_friend_invites (friend_id);

-- Entfernte Mitglieder kommen nicht per Code zurück. Der Ersteller kann sie bewusst als Freund neu einladen.
create table public.group_removed (
    group_id   uuid not null references public.groups (id) on delete cascade,
    user_id    uuid not null references auth.users (id) on delete cascade,
    removed_at timestamptz not null default now(),
    primary key (group_id, user_id)
);

alter table public.groups               enable row level security;
alter table public.group_members        enable row level security;
alter table public.group_invites        enable row level security;
alter table public.group_friend_invites enable row level security;
alter table public.group_removed        enable row level security;
-- Keine Policy und keine Rechte: Zugriff nur über die Funktionen.
revoke all on public.groups, public.group_members, public.group_invites,
              public.group_friend_invites, public.group_removed from anon, authenticated;

-- ---------------------------------------------------------------- Hilfsfunktionen (umgehen RLS gezielt)

create function public.is_group_member(g uuid, u uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select exists (select 1 from public.group_members where group_id = g and user_id = u);
$$;

-- Sieht der Betrachter die Live-Position des Besitzers über eine gemeinsame Gruppe?
-- Beide in derselben nicht abgelaufenen Gruppe, der Besitzer teilt dort seinen Standort, und zwischen ihnen
-- besteht keine Blockierung (Blockieren gewinnt, auch in Gruppen).
create function public.sees_via_group(owner uuid, viewer uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select owner <> viewer
       and exists (
            select 1
            from public.group_members mo
            join public.group_members mv on mv.group_id = mo.group_id and mv.user_id = viewer
            join public.groups g on g.id = mo.group_id
            where mo.user_id = owner
              and mo.share_location
              and (g.expires_at is null or g.expires_at > now())
       )
       and not exists (
            select 1 from public.friendships f
            where f.user_a = least(owner, viewer) and f.user_b = greatest(owner, viewer) and f.status = 'blocked'
       );
$$;

-- Darf der Aufrufer die Live-Position des Besitzers sehen? Zwei Wege: als freigegebener Freund (wie bisher)
-- oder als Mitglied einer gemeinsamen Gruppe. In beiden Fällen braucht es eine aktive Sitzung des Besitzers.
create or replace function public.can_view_location(owner uuid, sess uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
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

create function public.require_group_owner(g uuid, u uuid) returns void
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
begin
    if not exists (select 1 from public.group_members where group_id = g and user_id = u and role = 'owner') then
        raise exception 'not_owner';
    end if;
end $$;

-- Mitglieder plus offene, noch gültige Einladungen (zählt gegen die Grenze von 20).
create function public.group_load(g uuid) returns integer
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select (select count(*) from public.group_members where group_id = g)::int
         + (select count(*) from public.group_friend_invites where group_id = g and expires_at > now())::int;
$$;

-- Übergibt die Gruppe an das am längsten beigetretene andere Mitglied. Ohne anderes Mitglied wird sie gelöscht.
create function public.group_hand_over(g uuid, old_owner uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare nxt uuid;
begin
    select user_id into nxt from public.group_members
        where group_id = g and user_id <> old_owner order by joined_at, user_id limit 1;
    if nxt is null then
        delete from public.groups where id = g;
    else
        update public.group_members set role = 'member' where group_id = g and user_id = old_owner;
        update public.group_members set role = 'owner' where group_id = g and user_id = nxt;
        update public.groups set owner_id = nxt where id = g;
    end if;
end $$;

create function public.too_many_attempts(uid uuid) returns boolean
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select (select count(*) from public.redeem_failures where user_id = uid and at > now() - interval '1 hour') >= 10;
$$;

-- ---------------------------------------------------------------- Gruppen verwalten

-- p_days: Laufzeit in Tagen (1 bis 366), null = unbegrenzt. Ohne Angabe 7 Tage.
create function public.create_group(p_name text, p_days integer default 7) returns uuid
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid   uuid := auth.uid();
    clean text := btrim(coalesce(p_name, ''));
    gid   uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if char_length(clean) not between 1 and 30 then raise exception 'invalid_name'; end if;
    if p_days is not null and p_days not between 1 and 366 then raise exception 'invalid_duration'; end if;
    if (select count(*) from public.groups where owner_id = uid) >= 5 then raise exception 'too_many_groups'; end if;

    insert into public.groups (name, owner_id, expires_at)
    values (clean, uid, case when p_days is null then null else now() + make_interval(days => p_days) end)
    returning id into gid;
    insert into public.group_members (group_id, user_id, role) values (gid, uid, 'owner');
    return gid;
end $$;

create function public.list_groups()
returns table (group_id uuid, name text, role text, member_count integer, pending_count integer,
               owner_name text, expires_at timestamptz, share_location boolean, announce boolean)
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select g.id, g.name, m.role,
           (select count(*)::int from public.group_members x where x.group_id = g.id),
           case when m.role = 'owner'
                then (select count(*)::int from public.group_friend_invites i where i.group_id = g.id and i.expires_at > now())
                else 0 end,
           coalesce(p.display_name, '?'), g.expires_at, m.share_location, m.announce
    from public.group_members m
    join public.groups g on g.id = m.group_id
    left join public.profiles p on p.id = g.owner_id
    where m.user_id = auth.uid()
    order by g.created_at;
$$;

-- Mitglieder einer Gruppe, nur für Mitglieder. "visible" heisst: Der Aufrufer sieht diese Person gerade auf der Karte.
create function public.list_group_members(p_group uuid)
returns table (member_id uuid, display_name text, spoken_name text, color text, role text,
               joined_at timestamptz, share_location boolean, visible boolean)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if not public.is_group_member(p_group, auth.uid()) then raise exception 'not_a_member'; end if;
    return query
        select m.user_id, coalesce(p.display_name, '?'), p.spoken_name, coalesce(p.color, '#4CC9F0'), m.role,
               m.joined_at, m.share_location,
               exists (select 1 from public.live_locations l
                       where l.user_id = m.user_id and public.can_view_location(l.user_id, l.session_id))
        from public.group_members m
        left join public.profiles p on p.id = m.user_id
        where m.group_id = p_group
        order by m.joined_at, m.user_id;
end $$;

-- Offene Einladungen an Freunde, nur für den Ersteller.
create function public.list_group_pending(p_group uuid)
returns table (friend_id uuid, display_name text, created_at timestamptz, expires_at timestamptz)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    return query
        select i.friend_id, coalesce(p.display_name, '?'), i.created_at, i.expires_at
        from public.group_friend_invites i
        left join public.profiles p on p.id = i.friend_id
        where i.group_id = p_group and i.expires_at > now()
        order by i.created_at;
end $$;

create function public.rename_group(p_group uuid, p_name text) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare clean text := btrim(coalesce(p_name, ''));
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    if char_length(clean) not between 1 and 30 then raise exception 'invalid_name'; end if;
    update public.groups set name = clean where id = p_group;
end $$;

-- Verlängert die Gruppe um p_days Tage ab dem späteren von Ablauf und jetzt. null = unbegrenzt.
create function public.extend_group(p_group uuid, p_days integer) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare base timestamptz;
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    if p_days is null then
        update public.groups set expires_at = null where id = p_group;
        return;
    end if;
    if p_days not between 1 and 366 then raise exception 'invalid_duration'; end if;
    select greatest(coalesce(expires_at, now()), now()) into base from public.groups where id = p_group;
    if base + make_interval(days => p_days) > now() + interval '366 days' then raise exception 'invalid_duration'; end if;
    update public.groups set expires_at = base + make_interval(days => p_days) where id = p_group;
end $$;

create function public.delete_group(p_group uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    delete from public.groups where id = p_group;
end $$;

create function public.leave_group(p_group uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid(); r text;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    select role into r from public.group_members where group_id = p_group and user_id = uid;
    if r is null then raise exception 'not_a_member'; end if;
    if r = 'owner' then perform public.group_hand_over(p_group, uid); end if;
    delete from public.group_members where group_id = p_group and user_id = uid;
end $$;

create function public.remove_member(p_group uuid, p_user uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, uid);
    if p_user = uid then raise exception 'invalid_target'; end if;
    if not public.is_group_member(p_group, p_user) then raise exception 'not_a_member'; end if;
    delete from public.group_members where group_id = p_group and user_id = p_user;
    delete from public.group_friend_invites where group_id = p_group and friend_id = p_user;
    insert into public.group_removed (group_id, user_id) values (p_group, p_user) on conflict do nothing;
end $$;

create function public.set_group_sharing(p_group uuid, p_on boolean) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    update public.group_members set share_location = coalesce(p_on, true) where group_id = p_group and user_id = auth.uid();
    if not found then raise exception 'not_a_member'; end if;
end $$;

create function public.set_group_announce(p_group uuid, p_on boolean) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    update public.group_members set announce = coalesce(p_on, true) where group_id = p_group and user_id = auth.uid();
    if not found then raise exception 'not_a_member'; end if;
end $$;

-- ---------------------------------------------------------------- Einladen per Code

-- 24 Stunden gültig, p_max_uses Beitritte (1 bis 20, Standard 10), höchstens 5 offene Codes je Gruppe.
create function public.create_group_invite(p_group uuid, p_max_uses integer default 10) returns text
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid      uuid := auth.uid();
    alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  -- ohne 0, O, 1, I
    c        text;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, uid);
    if not exists (select 1 from public.groups where id = p_group and (expires_at is null or expires_at > now())) then
        raise exception 'group_expired';
    end if;
    if p_max_uses is null or p_max_uses not between 1 and 20 then raise exception 'invalid_max_uses'; end if;
    if (select count(*) from public.group_invites
        where group_id = p_group and revoked_at is null and expires_at > now() and uses < max_uses) >= 5 then
        raise exception 'too_many_invites';
    end if;
    loop
        c := '';
        for i in 1..6 loop
            c := c || substr(alphabet, 1 + (get_byte(gen_random_bytes(1), 0) % 32), 1);
        end loop;
        -- Ein Code gehört entweder zu einer Freundes-Einladung oder zu einer Gruppe, nie zu beiden.
        if not exists (select 1 from public.invites where code = c) then
            begin
                insert into public.group_invites (code, group_id, created_by, expires_at, max_uses)
                values (c, p_group, uid, now() + interval '24 hours', p_max_uses);
                return c;
            exception when unique_violation then
                -- Code existiert schon, neuen würfeln
            end;
        end if;
    end loop;
end $$;

create function public.revoke_group_invites(p_group uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    update public.group_invites set revoked_at = now() where group_id = p_group and revoked_at is null;
end $$;

-- Erkennt, wozu ein Code gehört: 'friend', 'group' oder null (ungültig, zählt als Fehlversuch).
-- So braucht die App nur ein Eingabefeld und löst keine Fehlversuche aus, wenn sie zuerst den falschen Weg probiert.
create function public.code_kind(p_code text) returns text
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid(); c text := upper(btrim(coalesce(p_code, '')));
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if public.too_many_attempts(uid) then raise exception 'too_many_attempts'; end if;

    if exists (select 1 from public.invites
               where code = c and used_by is null and expires_at > now() and created_by <> uid) then
        return 'friend';
    end if;
    if exists (select 1 from public.group_invites i join public.groups g on g.id = i.group_id
               where i.code = c and i.revoked_at is null and i.expires_at > now() and i.uses < i.max_uses
                 and (g.expires_at is null or g.expires_at > now())
                 and not exists (select 1 from public.group_removed r where r.group_id = i.group_id and r.user_id = uid)) then
        return 'group';
    end if;
    insert into public.redeem_failures (user_id) values (uid);
    return null;
end $$;

-- Zeigt vor dem Beitritt, worum es geht, ohne Mitglieder zu verraten. Ungültiger Code: keine Zeile, zählt als Fehlversuch.
create function public.preview_group_invite(p_code text)
returns table (group_id uuid, group_name text, owner_name text, member_count integer, is_full boolean, already_member boolean)
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid(); c text := upper(btrim(coalesce(p_code, ''))); gid uuid;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if public.too_many_attempts(uid) then raise exception 'too_many_attempts'; end if;

    select i.group_id into gid
    from public.group_invites i join public.groups g on g.id = i.group_id
    where i.code = c and i.revoked_at is null and i.expires_at > now() and i.uses < i.max_uses
      and (g.expires_at is null or g.expires_at > now())
      and not exists (select 1 from public.group_removed r where r.group_id = i.group_id and r.user_id = uid);
    if gid is null then
        insert into public.redeem_failures (user_id) values (uid);
        return;
    end if;
    return query
        select g.id, g.name, coalesce(p.display_name, '?'),
               (select count(*)::int from public.group_members x where x.group_id = g.id),
               public.group_load(g.id) >= 20,
               public.is_group_member(g.id, uid)
        from public.groups g left join public.profiles p on p.id = g.owner_id
        where g.id = gid;
end $$;

-- Tritt der Gruppe bei. Gibt die Gruppen-ID zurück, bei ungültigem Code null (zählt als Fehlversuch).
create function public.join_group(p_code text) returns uuid
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid uuid := auth.uid();
    c   text := upper(btrim(coalesce(p_code, '')));
    inv public.group_invites;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if public.too_many_attempts(uid) then raise exception 'too_many_attempts'; end if;

    select i.* into inv
    from public.group_invites i join public.groups g on g.id = i.group_id
    where i.code = c and i.revoked_at is null and i.expires_at > now() and i.uses < i.max_uses
      and (g.expires_at is null or g.expires_at > now())
      and not exists (select 1 from public.group_removed r where r.group_id = i.group_id and r.user_id = uid)
    for update of i;
    if not found then
        insert into public.redeem_failures (user_id) values (uid);
        return null;
    end if;

    if public.is_group_member(inv.group_id, uid) then return inv.group_id; end if;
    if (select count(*) from public.group_members where user_id = uid and role = 'member') >= 10 then
        raise exception 'too_many_memberships';
    end if;
    -- Eine eigene offene Einladung zählt nicht doppelt.
    if public.group_load(inv.group_id)
       - (select count(*)::int from public.group_friend_invites where group_id = inv.group_id and friend_id = uid and expires_at > now()) >= 20 then
        raise exception 'group_full';
    end if;

    insert into public.group_members (group_id, user_id, role) values (inv.group_id, uid, 'member');
    update public.group_invites set uses = uses + 1 where code = inv.code;
    delete from public.group_friend_invites where group_id = inv.group_id and friend_id = uid;
    return inv.group_id;
end $$;

-- ---------------------------------------------------------------- Einladen von Freunden (mit Annahme)

-- Lädt bestehende Freunde ein. Nicht-Freunde, Blockierte, Mitglieder und die eigene Person werden übersprungen.
-- Gibt die Anzahl neu eingeladener Freunde zurück. Eine Einladung gilt 7 Tage. Wer entfernt wurde, wird mit einer
-- bewussten neuen Einladung wieder zugelassen.
create function public.invite_friends_to_group(p_group uuid, p_friends uuid[]) returns integer
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
    uid uuid := auth.uid();
    f   uuid;
    n   integer := 0;
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, uid);
    if not exists (select 1 from public.groups where id = p_group and (expires_at is null or expires_at > now())) then
        raise exception 'group_expired';
    end if;
    if coalesce(array_length(p_friends, 1), 0) > 20 then raise exception 'too_many_friends'; end if;

    foreach f in array coalesce(p_friends, '{}') loop
        continue when f is null or f = uid;
        continue when not public.is_friend(uid, f);
        continue when public.is_group_member(p_group, f);
        if exists (select 1 from public.group_friend_invites where group_id = p_group and friend_id = f and expires_at > now()) then
            continue;  -- schon eingeladen
        end if;
        if public.group_load(p_group) >= 20 then raise exception 'group_full'; end if;
        insert into public.group_friend_invites (group_id, friend_id, invited_by, expires_at)
        values (p_group, f, uid, now() + interval '7 days')
        on conflict (group_id, friend_id) do update
            set invited_by = excluded.invited_by, created_at = now(), expires_at = excluded.expires_at;
        delete from public.group_removed where group_id = p_group and user_id = f;
        n := n + 1;
    end loop;
    return n;
end $$;

create function public.list_group_invitations()
returns table (group_id uuid, group_name text, inviter_name text, member_count integer, created_at timestamptz, expires_at timestamptz)
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    select g.id, g.name, coalesce(p.display_name, '?'),
           (select count(*)::int from public.group_members x where x.group_id = g.id),
           i.created_at, i.expires_at
    from public.group_friend_invites i
    join public.groups g on g.id = i.group_id
    left join public.profiles p on p.id = i.invited_by
    where i.friend_id = auth.uid()
      and i.expires_at > now()
      and (g.expires_at is null or g.expires_at > now())
    order by i.created_at;
$$;

create function public.accept_group_invitation(p_group uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare uid uuid := auth.uid();
begin
    if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    if not exists (select 1 from public.group_friend_invites
                   where group_id = p_group and friend_id = uid and expires_at > now()) then
        raise exception 'no_invitation';
    end if;
    if not exists (select 1 from public.groups where id = p_group and (expires_at is null or expires_at > now())) then
        raise exception 'group_expired';
    end if;
    if (select count(*) from public.group_members where user_id = uid and role = 'member') >= 10 then
        raise exception 'too_many_memberships';
    end if;
    -- Die eigene Einladung ist schon eingerechnet, deshalb zählen hier nur die Mitglieder.
    if (select count(*) from public.group_members where group_id = p_group) >= 20 then raise exception 'group_full'; end if;

    insert into public.group_members (group_id, user_id, role) values (p_group, uid, 'member') on conflict do nothing;
    delete from public.group_friend_invites where group_id = p_group and friend_id = uid;
end $$;

create function public.decline_group_invitation(p_group uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    delete from public.group_friend_invites where group_id = p_group and friend_id = auth.uid();
end $$;

create function public.cancel_group_invitation(p_group uuid, p_friend uuid) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    perform public.require_group_owner(p_group, auth.uid());
    delete from public.group_friend_invites where group_id = p_group and friend_id = p_friend;
end $$;

-- ---------------------------------------------------------------- Wer sieht mich gerade?

-- Alle, die den Aufrufer gerade sehen (während einer aktiven Freigabe): freigegebene Freunde und Gruppenmitglieder,
-- ohne Doppelte, mit den Gruppen, über die sie ihn sehen. Grundlage für den Hinweis "Du teilst deinen Standort mit N Personen".
create function public.my_viewers()
returns table (viewer_id uuid, display_name text, color text, via_friend boolean, group_names text[])
language sql stable security definer set search_path = public, extensions, pg_temp as $$
    with sess as (
        select id from public.share_sessions where user_id = auth.uid() and ended_at is null and expires_at > now()
    ),
    friends_v as (
        select t.friend_id as vid
        from public.share_targets t join sess on sess.id = t.session_id
        where public.is_friend(auth.uid(), t.friend_id)
    ),
    group_v as (
        select mv.user_id as vid, g.name as gname
        from public.group_members mo
        join public.group_members mv on mv.group_id = mo.group_id and mv.user_id <> mo.user_id
        join public.groups g on g.id = mo.group_id
        where mo.user_id = auth.uid()
          and mo.share_location
          and (g.expires_at is null or g.expires_at > now())
          and exists (select 1 from sess)
          and not exists (
                select 1 from public.friendships f
                where f.user_a = least(auth.uid(), mv.user_id) and f.user_b = greatest(auth.uid(), mv.user_id)
                  and f.status = 'blocked')
    ),
    ids as (select vid from friends_v union select vid from group_v)
    select i.vid, coalesce(p.display_name, '?'), coalesce(p.color, '#4CC9F0'),
           exists (select 1 from friends_v f where f.vid = i.vid),
           coalesce((select array_agg(distinct gv.gname) from group_v gv where gv.vid = i.vid), '{}')
    from ids i left join public.profiles p on p.id = i.vid;
$$;

-- ---------------------------------------------------------------- Konto löschen und Aufräumen

-- Wie bisher, aber Gruppen, die dem Nutzer gehören, gehen vorher an ein anderes Mitglied (oder werden gelöscht).
create or replace function public.delete_account() returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare g uuid;
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
    for g in select id from public.groups where owner_id = auth.uid() loop
        perform public.group_hand_over(g, auth.uid());
    end loop;
    delete from auth.users where id = auth.uid();
end $$;

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
    -- Abgelaufene Gruppen bleiben 7 Tage sichtbar (verlängerbar), dann verschwinden sie samt Mitgliedern und Einladungen.
    delete from public.groups where expires_at is not null and expires_at < now() - interval '7 days';
    delete from public.group_friend_invites where expires_at < now() - interval '1 day';
    delete from public.group_invites where expires_at < now() - interval '7 days';
end $$;

-- ---------------------------------------------------------------- Ausführungsrechte

do $$
declare r record;
begin
    for r in
        select p.oid::regprocedure as sig, p.proname
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname in ('is_group_member', 'sees_via_group', 'can_view_location', 'require_group_owner', 'group_load',
                            'group_hand_over', 'too_many_attempts', 'create_group', 'list_groups', 'list_group_members',
                            'list_group_pending', 'rename_group', 'extend_group', 'delete_group', 'leave_group',
                            'remove_member', 'set_group_sharing', 'set_group_announce', 'create_group_invite',
                            'revoke_group_invites', 'code_kind', 'preview_group_invite', 'join_group',
                            'invite_friends_to_group', 'list_group_invitations', 'accept_group_invitation',
                            'decline_group_invitation', 'cancel_group_invitation', 'my_viewers',
                            'delete_account', 'cleanup_expired')
    loop
        execute format('revoke all on function %s from public, anon', r.sig);
        if r.proname in ('require_group_owner', 'group_load', 'group_hand_over', 'too_many_attempts', 'cleanup_expired') then
            -- Nur intern bzw. für den Zeitplan.
            execute format('revoke all on function %s from authenticated', r.sig);
        else
            execute format('grant execute on function %s to authenticated', r.sig);
        end if;
    end loop;
end $$;

-- Minimaler Nachbau der Supabase-Umgebung für Tests in einer normalen Postgres-Datenbank.
-- In echtem Supabase gibt es das alles schon (Rollen, auth.users, auth.uid(), Standardrechte).

-- Wie in Supabase: Erweiterungen liegen im Schema "extensions", nicht in "public".
-- Dort steht der Suchpfad der Datenbank auf "$user", public, extensions.
create schema extensions;
create extension pgcrypto with schema extensions;
create extension postgis with schema extensions;
alter database supa set search_path = "$user", public, extensions;

create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;

create schema auth;
create table auth.users (
    id uuid primary key default gen_random_uuid(),
    email text,
    created_at timestamptz not null default now(),
    email_confirmed_at timestamptz default now()
);

-- Wie in Supabase gibt es die Rolle, über die PostgREST sich verbindet (0007 trägt dort die Sperre vor jeder Anfrage ein).
create role authenticator noinherit login;

-- Nur für die Tests: Neue Konten gelten sofort als freigegeben, damit die älteren Tests (Freunde, Meldungen, Gruppen) nicht
-- jedes Konto einzeln freigeben müssen. In Supabase gibt es das nicht. Die Freigabe-Tests (13) entfernen die Zeilen wieder.
create function auth.stub_auto_approve() returns trigger language plpgsql as $$
begin
    if to_regclass('public.approvals') is not null then
        execute 'insert into public.approvals (user_id) values ($1) on conflict do nothing' using new.id;
    end if;
    return new;
end $$;
create trigger stub_auto_approve after insert on auth.users for each row execute function auth.stub_auto_approve();

-- Wie bei Supabase: die Nutzer-ID kommt aus dem JWT (hier per set_config gesetzt).
create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

grant usage on schema public to anon, authenticated, service_role;
-- In Supabase dürfen die Rollen die Erweiterungen benutzen (z. B. den Typ geometry).
grant usage on schema extensions to anon, authenticated, service_role;
grant usage on schema auth to authenticated, anon, service_role;
-- Supabase vergibt diese Rechte standardmässig, die Migration nimmt sie gezielt wieder weg.
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;

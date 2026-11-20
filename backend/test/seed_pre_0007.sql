-- Konto aus der Zeit vor 0007: Die Migration muss es als freigegeben übernehmen (geprüft in 13_approval_tests.sql).
insert into auth.users (id, email) values ('99000000-0000-0000-0000-000000000099', 'alt@test');

-- Server-authoritative timestamps for public.todos.
--
-- `completed_at` shipped in the schema from day one and NOTHING ever wrote it: `TodoDto` does not
-- model the column, and `TodoRemoteImpl.setDone` only sends `set("done", done)`. Every completed row
-- carried `completed_at = NULL`, so the table could not answer "when did I finish this?".
--
-- The obvious fix — add the columns to `TodoDto` — is a trap. `SupabaseJson` sets `encodeDefaults =
-- true`, and `drainPending()` replays a PENDING row with `upsert(toDto())`. PostgREST's ON CONFLICT
-- DO UPDATE writes exactly the columns present in the JSON, so any field added to the DTO becomes a
-- column the drain blindly overwrites with the local snapshot. Keeping these two columns OUT of the
-- DTO is what makes them safe: absent from the payload, absent from the SET, preserved by the server.
--
-- Hence: the server owns them. A BEFORE trigger fills both, including on the conflict-update path of
-- the drain's upsert, without the client sending anything. `updated_at` from a single monotonic clock
-- also beats round-tripping the device's `TodoEntity.updatedAt`, which is unreliable today (it is
-- reset to now() on every pull by `toEntity`, and `add`/`delete` never bump it).
--
-- No backfill of `completed_at` for the rows already done: nobody recorded when they were finished,
-- and stamping them now() would be inventing data. NULL honestly means "completed before we tracked it".

alter table public.todos add column if not exists updated_at timestamptz not null default now();

create or replace function public.todos_touch() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();

  if tg_op = 'INSERT' then
    if new.done then
      new.completed_at := coalesce(new.completed_at, now());
    end if;
  -- Guard on `done` actually flipping: editing a title or a note must not restamp completed_at,
  -- and re-sending done=true (the drain replaying a row) must not move it either.
  elsif new.done is distinct from old.done then
    new.completed_at := case when new.done then now() else null end;
  end if;

  return new;
end $$;

drop trigger if exists todos_touch on public.todos;

create trigger todos_touch
before insert or update on public.todos
for each row execute function public.todos_touch();

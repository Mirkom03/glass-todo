-- v2 step 5 — enable realtime for public.todos. RLS is UNCHANGED (the stream applies it).
-- Applied to project aide-studio (wkjfnpjklmikswgofekq) on 2026-07-09 via Supabase MCP.
alter publication supabase_realtime add table public.todos;
-- Full old-row payloads on UPDATE/DELETE (delete reconciliation only needs the PK, but full
-- payloads keep the stream future-proof and debuggable).
alter table public.todos replica identity full;

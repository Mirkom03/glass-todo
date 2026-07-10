-- Panel de detalle de tarea (v1.4.0) — la descripción libre de una tarea.
-- Aplicada al proyecto aide-studio (wkjfnpjklmikswgofekq) el 2026-07-10 vía Supabase MCP.
--
-- Nullable y sin default: cambio de catálogo instantáneo, sin reescritura de tabla ni lock largo.
-- RLS NO cambia: las 4 políticas de public.todos filtran por user_id y son agnósticas a columnas.
-- Realtime NO cambia: replica identity ya es FULL (ver 001), así que la columna viaja en el WAL sola.
--
-- Se aplica ANTES de publicar la app: un cliente en v1.3.3 ignora una columna que no conoce.
alter table public.todos add column notes text;

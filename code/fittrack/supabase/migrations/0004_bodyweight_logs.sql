-- 体重按自然日记录；同一天写入时覆盖，避免趋势图出现重复点。

create table public.bodyweight_logs (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  logged_on date not null default current_date,
  weight numeric(6, 2) not null check (weight > 0 and weight <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_id, logged_on)
);

create index bodyweight_logs_owner_date_idx
  on public.bodyweight_logs (owner_id, logged_on desc);

create trigger bodyweight_logs_set_updated_at before update on public.bodyweight_logs
  for each row execute function public.set_updated_at();

alter table public.bodyweight_logs enable row level security;

create policy "bodyweight_logs_select_own" on public.bodyweight_logs
  for select to authenticated using (owner_id = auth.uid());
create policy "bodyweight_logs_insert_own" on public.bodyweight_logs
  for insert to authenticated with check (owner_id = auth.uid());
create policy "bodyweight_logs_update_own" on public.bodyweight_logs
  for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "bodyweight_logs_delete_own" on public.bodyweight_logs
  for delete to authenticated using (owner_id = auth.uid());

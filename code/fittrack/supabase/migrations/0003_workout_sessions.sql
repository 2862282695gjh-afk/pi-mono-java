-- 已完成训练的云端事实记录。训练中的草稿只驻留 Drift，成功同步后才删除。

create table public.workout_sessions (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  client_id text not null check (char_length(client_id) between 1 and 100),
  plan_id uuid references public.workout_plans(id) on delete set null,
  name text not null check (char_length(btrim(name)) between 1 and 100),
  started_at timestamptz not null,
  ended_at timestamptz not null,
  duration_sec integer not null check (duration_sec >= 0),
  note text not null default '' check (char_length(note) <= 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_id, client_id),
  check (ended_at >= started_at)
);

create index workout_sessions_owner_started_idx
  on public.workout_sessions (owner_id, started_at desc);

create table public.session_exercises (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.workout_sessions(id) on delete cascade,
  exercise_id uuid not null references public.exercises(id) on delete restrict,
  sort_order integer not null check (sort_order >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (session_id, sort_order)
);

create table public.session_sets (
  id uuid primary key default gen_random_uuid(),
  session_exercise_id uuid not null references public.session_exercises(id) on delete cascade,
  set_index integer not null check (set_index >= 0),
  weight numeric(7, 2) not null check (weight >= 0),
  reps integer not null check (reps between 1 and 100),
  completed_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (session_exercise_id, set_index)
);

create index session_exercises_session_sort_idx on public.session_exercises (session_id, sort_order);
create index session_sets_exercise_index_idx on public.session_sets (session_exercise_id, set_index);

create trigger workout_sessions_set_updated_at before update on public.workout_sessions
  for each row execute function public.set_updated_at();
create trigger session_exercises_set_updated_at before update on public.session_exercises
  for each row execute function public.set_updated_at();
create trigger session_sets_set_updated_at before update on public.session_sets
  for each row execute function public.set_updated_at();

alter table public.workout_sessions enable row level security;
alter table public.session_exercises enable row level security;
alter table public.session_sets enable row level security;

create policy "workout_sessions_select_own" on public.workout_sessions
  for select to authenticated using (owner_id = auth.uid());
create policy "workout_sessions_insert_own" on public.workout_sessions
  for insert to authenticated with check (owner_id = auth.uid());
create policy "workout_sessions_update_own" on public.workout_sessions
  for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "workout_sessions_delete_own" on public.workout_sessions
  for delete to authenticated using (owner_id = auth.uid());

create policy "session_exercises_select_session_owner" on public.session_exercises
  for select to authenticated using (
    exists (select 1 from public.workout_sessions where id = session_id and owner_id = auth.uid())
  );
create policy "session_exercises_insert_session_owner" on public.session_exercises
  for insert to authenticated with check (
    exists (select 1 from public.workout_sessions where id = session_id and owner_id = auth.uid())
  );
create policy "session_exercises_update_session_owner" on public.session_exercises
  for update to authenticated
  using (exists (select 1 from public.workout_sessions where id = session_id and owner_id = auth.uid()))
  with check (exists (select 1 from public.workout_sessions where id = session_id and owner_id = auth.uid()));
create policy "session_exercises_delete_session_owner" on public.session_exercises
  for delete to authenticated using (
    exists (select 1 from public.workout_sessions where id = session_id and owner_id = auth.uid())
  );

create policy "session_sets_select_session_owner" on public.session_sets
  for select to authenticated using (
    exists (
      select 1 from public.session_exercises session_exercise
      join public.workout_sessions session on session.id = session_exercise.session_id
      where session_exercise.id = session_exercise_id and session.owner_id = auth.uid()
    )
  );
create policy "session_sets_insert_session_owner" on public.session_sets
  for insert to authenticated with check (
    exists (
      select 1 from public.session_exercises session_exercise
      join public.workout_sessions session on session.id = session_exercise.session_id
      where session_exercise.id = session_exercise_id and session.owner_id = auth.uid()
    )
  );

create or replace function public.sync_workout_session(
  p_client_id text,
  p_plan_id uuid,
  p_name text,
  p_started_at timestamptz,
  p_ended_at timestamptz,
  p_exercises jsonb
)
returns uuid language plpgsql set search_path = public as $$
declare
  v_session_id uuid;
  v_session_exercise_id uuid;
  v_exercise jsonb;
  v_set jsonb;
  v_order integer := 0;
  v_set_order integer;
begin
  if auth.uid() is null then raise exception 'AUTHENTICATION_REQUIRED'; end if;
  if char_length(coalesce(p_client_id, '')) not between 1 and 100 then raise exception 'INVALID_CLIENT_ID'; end if;
  if char_length(btrim(coalesce(p_name, ''))) not between 1 and 100 then raise exception 'INVALID_SESSION_NAME'; end if;
  if p_started_at is null or p_ended_at is null or p_ended_at < p_started_at then raise exception 'INVALID_SESSION_TIME'; end if;
  if jsonb_typeof(p_exercises) <> 'array' or jsonb_array_length(p_exercises) = 0 then raise exception 'SESSION_REQUIRES_EXERCISE'; end if;

  select id into v_session_id from public.workout_sessions
    where owner_id = auth.uid() and client_id = p_client_id;
  if v_session_id is not null then return v_session_id; end if;
  if p_plan_id is not null and not exists (
    select 1 from public.workout_plans where id = p_plan_id and owner_id = auth.uid()
  ) then raise exception 'PLAN_NOT_FOUND'; end if;

  insert into public.workout_sessions (owner_id, client_id, plan_id, name, started_at, ended_at, duration_sec)
  values (auth.uid(), p_client_id, p_plan_id, btrim(p_name), p_started_at, p_ended_at,
    extract(epoch from p_ended_at - p_started_at)::integer)
  returning id into v_session_id;

  for v_exercise in select value from jsonb_array_elements(p_exercises) loop
    if not exists (
      select 1 from public.exercises
      where id = (v_exercise->>'exercise_id')::uuid
        and (owner_id is null or owner_id = auth.uid())
    ) then raise exception 'INVALID_SESSION_EXERCISE'; end if;
    insert into public.session_exercises (session_id, exercise_id, sort_order)
    values (v_session_id, (v_exercise->>'exercise_id')::uuid, v_order)
    returning id into v_session_exercise_id;
    v_set_order := 0;
    for v_set in select value from jsonb_array_elements(coalesce(v_exercise->'sets', '[]'::jsonb)) loop
      if v_set->>'completed_at' is not null then
        insert into public.session_sets (session_exercise_id, set_index, weight, reps, completed_at)
        values (v_session_exercise_id, v_set_order, (v_set->>'weight')::numeric,
          (v_set->>'reps')::integer, (v_set->>'completed_at')::timestamptz);
      end if;
      v_set_order := v_set_order + 1;
    end loop;
    v_order := v_order + 1;
  end loop;
  return v_session_id;
end $$;

grant execute on function public.sync_workout_session(text, uuid, text, timestamptz, timestamptz, jsonb) to authenticated;

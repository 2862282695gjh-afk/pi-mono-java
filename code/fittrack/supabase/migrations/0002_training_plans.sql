-- 动作库与训练计划：系统动作可读，用户动作和计划仅本人可见。

create or replace function public.set_updated_at()
returns trigger language plpgsql set search_path = public as $$
begin
  new.updated_at = now();
  return new;
end $$;

create table public.exercises (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(btrim(name)) between 1 and 100),
  category text not null check (category in ('复合', '孤立', '有氧', '拉伸')),
  muscle_group text not null check (char_length(btrim(muscle_group)) between 1 and 50),
  is_custom boolean not null default false,
  owner_id uuid references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint exercises_owner_kind_check check (
    (owner_id is null and is_custom = false)
    or (owner_id is not null and is_custom = true)
  )
);

create unique index exercises_system_name_unique
  on public.exercises (name) where owner_id is null;
create unique index exercises_owner_name_unique
  on public.exercises (owner_id, name) where owner_id is not null;

create table public.workout_plans (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  name text not null check (char_length(btrim(name)) between 1 and 100),
  description text not null default '' check (char_length(description) <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index workout_plans_owner_updated_idx
  on public.workout_plans (owner_id, updated_at desc);

create table public.plan_exercises (
  id uuid primary key default gen_random_uuid(),
  plan_id uuid not null references public.workout_plans(id) on delete cascade,
  exercise_id uuid not null references public.exercises(id) on delete restrict,
  sort_order integer not null check (sort_order >= 0),
  default_sets integer not null check (default_sets between 1 and 20),
  default_reps integer not null check (default_reps between 1 and 100),
  default_weight numeric(7, 2) not null default 0 check (default_weight >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (plan_id, sort_order),
  unique (plan_id, exercise_id)
);

create index plan_exercises_plan_sort_idx
  on public.plan_exercises (plan_id, sort_order);

create trigger exercises_set_updated_at
  before update on public.exercises
  for each row execute function public.set_updated_at();
create trigger workout_plans_set_updated_at
  before update on public.workout_plans
  for each row execute function public.set_updated_at();
create trigger plan_exercises_set_updated_at
  before update on public.plan_exercises
  for each row execute function public.set_updated_at();
create trigger profiles_set_updated_at
  before update on public.profiles
  for each row execute function public.set_updated_at();

alter table public.exercises enable row level security;
alter table public.workout_plans enable row level security;
alter table public.plan_exercises enable row level security;

create policy "exercises_select_system_or_own" on public.exercises
  for select to authenticated
  using (owner_id is null or owner_id = auth.uid());
create policy "exercises_insert_own_custom" on public.exercises
  for insert to authenticated
  with check (owner_id = auth.uid() and is_custom = true);
create policy "exercises_update_own_custom" on public.exercises
  for update to authenticated
  using (owner_id = auth.uid() and is_custom = true)
  with check (owner_id = auth.uid() and is_custom = true);
create policy "exercises_delete_own_custom" on public.exercises
  for delete to authenticated
  using (owner_id = auth.uid() and is_custom = true);

create policy "workout_plans_select_own" on public.workout_plans
  for select to authenticated using (owner_id = auth.uid());
create policy "workout_plans_insert_own" on public.workout_plans
  for insert to authenticated with check (owner_id = auth.uid());
create policy "workout_plans_update_own" on public.workout_plans
  for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "workout_plans_delete_own" on public.workout_plans
  for delete to authenticated using (owner_id = auth.uid());

create policy "plan_exercises_select_plan_owner" on public.plan_exercises
  for select to authenticated using (
    exists (select 1 from public.workout_plans where id = plan_id and owner_id = auth.uid())
  );
create policy "plan_exercises_insert_plan_owner" on public.plan_exercises
  for insert to authenticated with check (
    exists (select 1 from public.workout_plans where id = plan_id and owner_id = auth.uid())
  );
create policy "plan_exercises_update_plan_owner" on public.plan_exercises
  for update to authenticated
  using (exists (select 1 from public.workout_plans where id = plan_id and owner_id = auth.uid()))
  with check (exists (select 1 from public.workout_plans where id = plan_id and owner_id = auth.uid()));
create policy "plan_exercises_delete_plan_owner" on public.plan_exercises
  for delete to authenticated using (
    exists (select 1 from public.workout_plans where id = plan_id and owner_id = auth.uid())
  );

create or replace function public.save_workout_plan(
  p_plan_id uuid,
  p_name text,
  p_description text,
  p_exercises jsonb
)
returns uuid language plpgsql set search_path = public as $$
declare
  v_plan_id uuid;
  v_expected_count integer;
  v_allowed_count integer;
begin
  if auth.uid() is null then
    raise exception 'AUTHENTICATION_REQUIRED';
  end if;
  if char_length(btrim(coalesce(p_name, ''))) not between 1 and 100 then
    raise exception 'INVALID_PLAN_NAME';
  end if;
  if char_length(coalesce(p_description, '')) > 500 then
    raise exception 'INVALID_PLAN_DESCRIPTION';
  end if;
  if jsonb_typeof(p_exercises) <> 'array' or jsonb_array_length(p_exercises) = 0 then
    raise exception 'PLAN_REQUIRES_EXERCISE';
  end if;

  if p_plan_id is null then
    insert into public.workout_plans (owner_id, name, description)
    values (auth.uid(), btrim(p_name), coalesce(p_description, ''))
    returning id into v_plan_id;
  else
    update public.workout_plans
    set name = btrim(p_name), description = coalesce(p_description, '')
    where id = p_plan_id and owner_id = auth.uid()
    returning id into v_plan_id;
    if v_plan_id is null then
      raise exception 'PLAN_NOT_FOUND';
    end if;
  end if;

  select count(*), count(distinct exercise_id)
  into v_expected_count, v_allowed_count
  from jsonb_to_recordset(p_exercises) as item(
    exercise_id uuid,
    default_sets integer,
    default_reps integer,
    default_weight numeric
  );
  if v_expected_count <> v_allowed_count then
    raise exception 'DUPLICATE_PLAN_EXERCISE';
  end if;

  select count(*) into v_allowed_count
  from public.exercises exercise
  where exercise.id in (
    select item.exercise_id
    from jsonb_to_recordset(p_exercises) as item(
      exercise_id uuid,
      default_sets integer,
      default_reps integer,
      default_weight numeric
    )
  ) and (exercise.owner_id is null or exercise.owner_id = auth.uid());
  if v_allowed_count <> v_expected_count then
    raise exception 'INVALID_PLAN_EXERCISE';
  end if;

  if exists (
    select 1 from jsonb_to_recordset(p_exercises) as item(
      exercise_id uuid,
      default_sets integer,
      default_reps integer,
      default_weight numeric
    ) where default_sets not between 1 and 20
      or default_reps not between 1 and 100
      or default_weight < 0
  ) then
    raise exception 'INVALID_PLAN_DEFAULTS';
  end if;

  delete from public.plan_exercises where plan_id = v_plan_id;
  insert into public.plan_exercises (
    plan_id, exercise_id, sort_order, default_sets, default_reps, default_weight
  )
  select v_plan_id, item.exercise_id, item.ordinality - 1,
    item.default_sets, item.default_reps, item.default_weight
  from rows from (
    jsonb_to_recordset(p_exercises) as (
      exercise_id uuid,
      default_sets integer,
      default_reps integer,
      default_weight numeric
    )
  ) with ordinality as item(
    exercise_id,
    default_sets,
    default_reps,
    default_weight,
    ordinality
  );

  return v_plan_id;
end $$;

grant execute on function public.save_workout_plan(uuid, text, text, jsonb) to authenticated;

insert into public.exercises (name, category, muscle_group) values
  ('杠铃深蹲', '复合', '股四头肌'), ('高杠深蹲', '复合', '股四头肌'),
  ('前蹲', '复合', '股四头肌'), ('罗马尼亚硬拉', '复合', '腘绳肌'),
  ('传统硬拉', '复合', '背部'), ('相扑硬拉', '复合', '臀大肌'),
  ('臀推', '复合', '臀大肌'), ('腿举', '复合', '股四头肌'),
  ('保加利亚分腿蹲', '复合', '股四头肌'), ('箭步蹲', '复合', '股四头肌'),
  ('腿屈伸', '孤立', '股四头肌'), ('俯卧腿弯举', '孤立', '腘绳肌'),
  ('坐姿腿弯举', '孤立', '腘绳肌'), ('站姿提踵', '孤立', '小腿'),
  ('坐姿提踵', '孤立', '小腿'), ('髋外展', '孤立', '臀中肌'),
  ('髋内收', '孤立', '内收肌'), ('杠铃卧推', '复合', '胸大肌'),
  ('哑铃卧推', '复合', '胸大肌'), ('上斜哑铃卧推', '复合', '胸大肌'),
  ('双杠臂屈伸', '复合', '胸大肌'), ('俯卧撑', '复合', '胸大肌'),
  ('夹胸飞鸟', '孤立', '胸大肌'), ('绳索夹胸', '孤立', '胸大肌'),
  ('杠铃划船', '复合', '背部'), ('引体向上', '复合', '背阔肌'),
  ('高位下拉', '复合', '背阔肌'), ('坐姿划船', '复合', '背部'),
  ('单臂哑铃划船', '复合', '背部'), ('直臂下压', '孤立', '背阔肌'),
  ('面拉', '孤立', '后束三角肌'), ('杠铃肩推', '复合', '三角肌'),
  ('哑铃肩推', '复合', '三角肌'), ('哑铃侧平举', '孤立', '中束三角肌'),
  ('哑铃前平举', '孤立', '前束三角肌'), ('反向飞鸟', '孤立', '后束三角肌'),
  ('杠铃弯举', '孤立', '肱二头肌'), ('哑铃弯举', '孤立', '肱二头肌'),
  ('锤式弯举', '孤立', '肱肌'), ('绳索下压', '孤立', '肱三头肌'),
  ('仰卧臂屈伸', '孤立', '肱三头肌'), ('窄距卧推', '复合', '肱三头肌'),
  ('卷腹', '孤立', '腹直肌'), ('悬垂举腿', '孤立', '腹直肌'),
  ('平板支撑', '孤立', '核心'), ('俄罗斯转体', '孤立', '腹斜肌'),
  ('跑步机跑步', '有氧', '全身'), ('动感单车', '有氧', '全身'),
  ('划船机', '有氧', '全身'), ('椭圆机', '有氧', '全身');

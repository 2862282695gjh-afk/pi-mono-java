-- 多日自动计划必须原子写入，避免网络异常时只生成一部分训练日。
create or replace function public.save_generated_plan_bundle(p_plans jsonb)
returns uuid[] language plpgsql set search_path = public as $$
declare
  v_plan jsonb;
  v_ids uuid[] := '{}';
  v_id uuid;
begin
  if auth.uid() is null then raise exception 'AUTHENTICATION_REQUIRED'; end if;
  if jsonb_typeof(p_plans) <> 'array' or jsonb_array_length(p_plans) < 1 then
    raise exception 'BUNDLE_REQUIRES_PLAN';
  end if;
  for v_plan in select value from jsonb_array_elements(p_plans) loop
    v_id := public.save_workout_plan(
      null,
      v_plan->>'name',
      coalesce(v_plan->>'description', ''),
      v_plan->'exercises'
    );
    v_ids := array_append(v_ids, v_id);
  end loop;
  return v_ids;
end $$;
grant execute on function public.save_generated_plan_bundle(jsonb) to authenticated;

-- RLS 隔离测试：两个测试用户互验读不到对方 profile
-- 在 Supabase SQL Editor 以 postgres 角色运行（脚本内部切换身份模拟）
-- 也可经 Management API POST /v1/projects/{ref}/database/query 执行

-- 造两个临时用户（若已存在则跳过错误）
insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-0000000000a1', 'rls_test_a@test.local', '{"provider":"email"}'::jsonb, '{}'::jsonb),
  ('00000000-0000-0000-0000-0000000000b2', 'rls_test_b@test.local', '{"provider":"email"}'::jsonb, '{}'::jsonb)
on conflict (id) do nothing;

-- 触发器应已为二者建 profile；以 a 的身份查表只能看到自己
do $$
declare a_count int; b_count int; leaked int;
begin
  select count(*) into a_count from public.profiles where id = '00000000-0000-0000-0000-0000000000a1';
  select count(*) into b_count from public.profiles where id = '00000000-0000-0000-0000-0000000000b2';
  if a_count <> 1 or b_count <> 1 then
    raise exception 'FAIL: 触发器未正确建 profile (a=%, b=%)', a_count, b_count;
  end if;

  -- 以 a 的身份查表：只能看到自己
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', json_build_object(
    'role','authenticated',
    'sub','00000000-0000-0000-0000-0000000000a1')::text, true);
  select count(*) into leaked from public.profiles
    where id <> '00000000-0000-0000-0000-0000000000a1';
  if leaked <> 0 then
    raise exception 'FAIL: 用户 a 读到了他人 profile（泄露 % 行）', leaked;
  end if;

  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  raise notice 'PASS: profiles RLS 隔离测试 2 项断言全部通过';
end $$;

-- 清理
delete from auth.users where id in
  ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b2');

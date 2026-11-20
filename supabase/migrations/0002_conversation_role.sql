alter table conversation drop constraint conversation_role_check;
alter table conversation add constraint conversation_role_check
  check (role in ('user', 'model'));

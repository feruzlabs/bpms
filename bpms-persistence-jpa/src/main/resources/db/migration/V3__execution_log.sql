-- Execution diagnostics: connector/gateway/instance events (plan 19).
create table execution_log (
  id           bigserial primary key,
  instance_id  varchar(64) not null,
  token_id     varchar(64),
  node_id      varchar(255),
  node_type    varchar(64),
  connector_id varchar(255),
  event_type   varchar(32) not null,
  status       varchar(32),
  message      text,
  details      jsonb,
  duration_ms  integer,
  created_at   timestamptz not null
);
create index ix_execution_log_instance on execution_log(instance_id, created_at);

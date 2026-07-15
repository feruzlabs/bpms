create table process_definition (
 id varchar(64) primary key, process_key varchar(255) not null, name varchar(255), version integer not null,
 source_format varchar(32) not null, bpmn_xml text not null, parsed_json jsonb not null, created_at timestamptz not null);
create unique index ix_process_definition_key_version on process_definition(process_key, version);
create table process_instance (
 id varchar(64) primary key, definition_id varchar(64) not null references process_definition(id), business_key varchar(255),
 status varchar(32) not null, created_at timestamptz not null, ended_at timestamptz);
create index ix_process_instance_business_key on process_instance(business_key);
create table execution_token (
 id varchar(64) primary key, instance_id varchar(64) not null references process_instance(id), current_node_id varchar(255) not null,
 status varchar(32) not null, parent_multi_instance_id varchar(64));
create index ix_execution_token_instance_status on execution_token(instance_id,status);
create table token_variable (
 id bigserial primary key, instance_id varchar(64) not null references process_instance(id), name varchar(255) not null,
 type varchar(16) not null, value_text text, value_json jsonb, unique(instance_id,name));
create table job (
 id varchar(64) primary key, instance_id varchar(64) not null, token_id varchar(64) not null, type varchar(64) not null,
 payload jsonb, status varchar(32) not null, attempts integer not null default 0, run_at timestamptz not null);
create table user_task (
 id varchar(64) primary key, instance_id varchar(64) not null references process_instance(id), token_id varchar(64) not null,
 node_id varchar(255) not null, name varchar(255), completed boolean not null, created_at timestamptz not null, completed_at timestamptz);

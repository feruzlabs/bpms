-- XML is the canonical definition source; parsed model lives in DefinitionRegistry (heap).
alter table process_definition drop column if exists parsed_json;

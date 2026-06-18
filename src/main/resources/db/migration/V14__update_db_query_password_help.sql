UPDATE custom_methods
SET parameter_definitions_json =
    '[{"name":"connectionString","type":"string","description":"JDBC connection URL e.g. jdbc:postgresql://host:5432/db or jdbc:oracle:thin:@//host:1521/service","required":true},
      {"name":"username","type":"string","description":"Database username","required":true},
      {"name":"password","type":"string","description":"Database password. Test runs accept plain text; stored bindings may use /methods/encrypt-password.","required":true},
      {"name":"query","type":"string","description":"SELECT query to execute (SELECT only)","required":true}]'
WHERE type = 'BUILTIN'
  AND builtin_type = 'DB_QUERY';

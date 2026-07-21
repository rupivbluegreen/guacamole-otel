-- THROWAWAY Phase 0: pre-seed one SSH connection so Gate 0.3/0.4 tunnel connect
-- is deterministic. Target = the sshtarget service (linuxserver/openssh-server,
-- listens on 2222). Grant READ to the default guacadmin user.

INSERT INTO guacamole_connection (connection_name, protocol)
VALUES ('probe-ssh', 'ssh');

INSERT INTO guacamole_connection_parameter (connection_id, parameter_name, parameter_value)
SELECT connection_id, 'hostname', 'sshtarget' FROM guacamole_connection WHERE connection_name = 'probe-ssh'
UNION ALL
SELECT connection_id, 'port', '2222' FROM guacamole_connection WHERE connection_name = 'probe-ssh'
UNION ALL
SELECT connection_id, 'username', 'guac' FROM guacamole_connection WHERE connection_name = 'probe-ssh'
UNION ALL
SELECT connection_id, 'password', 'guacpass' FROM guacamole_connection WHERE connection_name = 'probe-ssh';

INSERT INTO guacamole_connection_permission (entity_id, connection_id, permission)
SELECT e.entity_id, c.connection_id, 'READ'
FROM guacamole_entity e, guacamole_connection c
WHERE e.name = 'guacadmin' AND e.type = 'USER' AND c.connection_name = 'probe-ssh';

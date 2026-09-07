\c claw;
CREATE SCHEMA claw;
SET search_path TO claw;
ALTER USER {dbUser} SET search_path TO claw;
ALTER DATABASE claw OWNER TO {dbUser};
ALTER SCHEMA claw OWNER TO {dbUser};
GRANT all privileges ON DATABASE claw TO {dbUser};

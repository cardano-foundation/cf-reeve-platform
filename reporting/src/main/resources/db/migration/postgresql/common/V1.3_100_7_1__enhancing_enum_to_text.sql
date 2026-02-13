-- Overload to allow calling enum_to_text() with plain text/varchar columns too.
-- Postgres won't automatically cast varchar -> anyenum, so without this you'll get:
--   ERROR: function enum_to_text(character varying) does not exist
CREATE OR REPLACE FUNCTION enum_to_text(text)
    RETURNS text
    LANGUAGE SQL
    IMMUTABLE
    PARALLEL SAFE
AS $$
SELECT $1;
$$;

CREATE OR REPLACE FUNCTION enum_to_text(varchar)
    RETURNS text
    LANGUAGE SQL
    IMMUTABLE
    PARALLEL SAFE
AS $$
SELECT $1::text;
$$;

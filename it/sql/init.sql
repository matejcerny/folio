-- folio-it integration test schema
-- Table mirrors Row (it/src/test/scala/folio/it/Rows.scala).
--
-- Postgres runs this file only when the data directory is empty, so after changing it recreate the container:
--   docker compose down && docker compose up -d postgres

CREATE TABLE IF NOT EXISTS rows (
  id          bigint      NOT NULL,
  name        text        NOT NULL,
  created_at  timestamptz NOT NULL,
  description text        NOT NULL,
  last_seen   timestamptz,
  group_id    bigint      NOT NULL,  -- non-unique bigint: the filtered suite paginates within one group
  payload     text        NOT NULL   -- in the user's SELECT + decoder, but NOT in RowField/KeysetField
);

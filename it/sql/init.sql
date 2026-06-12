-- folio-it integration test schema
-- Table mirrors IntegrationSuite.Row (it/src/test/scala/folio/it/IntegrationSuite.scala).

CREATE TABLE IF NOT EXISTS rows (
  id          bigint      NOT NULL,
  name        text        NOT NULL,
  created_at  timestamptz NOT NULL,
  description text        NOT NULL,
  last_seen   timestamptz,
  payload     text        NOT NULL   -- in the user's SELECT + decoder, but NOT in RowField/KeysetField
);

-- folio-it integration test schema
-- Table mirrors the oracle in core/src/test/scala/folio/TestFixtures.scala
-- Column types will be finalised during implementation (§14 of folio-skunk-design.md)

CREATE TABLE IF NOT EXISTS rows (
  id          bigint      NOT NULL,
  name        text        NOT NULL,
  created_at  timestamptz NOT NULL,
  description text        NOT NULL,
  last_seen   timestamptz
);

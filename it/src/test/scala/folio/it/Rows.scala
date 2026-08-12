package folio.it

import java.time.{ OffsetDateTime, ZoneOffset }
import java.time.temporal.ChronoUnit

import skunk.{ AppliedFragment, Codec, Command, Void }
import skunk.codec.all.{ int8, text, timestamptz }
import skunk.implicits.*

import folio.*

// The `rows` table (it/sql/init.sql) as folio sees it: the row model, its field enum, the keyset registration, and the
// SQL the integration suites reuse.
//
// Shared by IntegrationSuite and FilteredIntegrationSuite so both exercise one schema and one SELECT. Each suite owns
// its dataset: unfiltered cases need distinct values to pin a total order, filtered cases need repeated ones.

final case class Row(
    id: Long,
    name: String,
    createdAt: OffsetDateTime,
    description: String,
    lastSeen: Option[OffsetDateTime],
    groupId: Long,
    payload: String // decoded into the row, invisible to folio
)

enum RowField derives FieldSchema.SnakeCase:
  case Id, Name, CreatedAt, Description, LastSeen, GroupId // deliberately NO Payload case
// SnakeCase => id, name, created_at, description, last_seen, group_id — matches those columns.

// LastSeen is registered via the `T => Option[V]` overload, marking it absentable.
//
// Description and GroupId are deliberately not registered: ordering by either forces the offset branch, while filtering
// by group_id still paginates by keyset — filters need no registration.
given KeysetField[RowField, Row] =
  KeysetField
    .uniqueBy(RowField.Id, (row: Row) => row.id)
    .withField(RowField.Name, _.name)
    .withField(RowField.CreatedAt, _.createdAt)
    .withField(RowField.LastSeen, _.lastSeen)

// Whole-second UTC timestamps so `timestamptz` round-trips losslessly and decoded rows equal the inserted literals.
private val base: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

def at(seconds: Long): OffsetDateTime = base.plusSeconds(seconds).truncatedTo(ChronoUnit.SECONDS)

// Projection order matches Row's field order. The first six columns match their FieldSchema names (ADR 0004: the inner
// SELECT must expose every filter, order, and keyset column under that name); `payload` is extra and folio-invisible.
// One Codec[Row] serves both the SELECT decoder and the INSERT encoder.
val rowCodec: Codec[Row] =
  (int8 *: text *: timestamptz *: text *: timestamptz.opt *: int8 *: text).to[Row]

val select: AppliedFragment =
  sql"SELECT id, name, created_at, description, last_seen, group_id, payload FROM rows".apply(Void)

val insert: Command[Row] =
  sql"INSERT INTO rows (id, name, created_at, description, last_seen, group_id, payload) VALUES ($rowCodec)".command

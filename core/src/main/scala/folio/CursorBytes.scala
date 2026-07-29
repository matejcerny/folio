package folio

import folio.FolioError.*

import java.nio.charset.StandardCharsets
import java.time.{ Instant, OffsetDateTime, ZoneOffset }
import scala.annotation.tailrec
import scala.util.Try

private[folio] object CursorBytes:

  case class ReaderState(bytes: Array[Byte], index: Int)

  case class Read[A](run: ReaderState => Either[CursorDecodingError, (ReaderState, A)]):
    def map[B](transform: A => B): Read[B] =
      Read: state =>
        run(state).map: (nextState, value) =>
          (nextState, transform(value))

    def flatMap[B](transform: A => Read[B]): Read[B] =
      Read: state =>
        run(state).flatMap: (nextState, value) =>
          transform(value).run(nextState)

    def runA(initialState: ReaderState): Either[CursorDecodingError, A] =
      run(initialState).map(_._2)

  object Read:
    def pure[A](value: A): Read[A] = Read(state => Right((state, value)))

  extension [A](either: Either[CursorDecodingError, A])
    def liftRead: Read[A] = Read(state => either.map(value => (state, value)))

  type EncodedBytes = Vector[Byte]

  private val empty: EncodedBytes = Vector.empty

  /** Type tags for [[FieldValue]] variants plus [[AnchorValue.Absent]]. Cursor anchor slots and the canonical filter
    * fingerprint share one tag space, so a value encodes to the same bytes whichever of the two carries it.
    */
  val tagIntV: Byte = 0x01
  val tagLongV: Byte = 0x02
  val tagStringV: Byte = 0x03
  val tagTimestampV: Byte = 0x04
  val tagAbsent: Byte = 0x05

  /** Self-delimiting encoding of a present field value: type tag followed by the variant's payload. */
  def fieldValueBytes(value: FieldValue): EncodedBytes = value match
    case FieldValue.IntV(intValue)             => byte(tagIntV) ++ intBytes(intValue)
    case FieldValue.LongV(longValue)           => byte(tagLongV) ++ longBytes(longValue)
    case FieldValue.StringV(stringValue)       => byte(tagStringV) ++ stringBytes(stringValue)
    case FieldValue.TimestampV(timestampValue) => byte(tagTimestampV) ++ timestampBytes(timestampValue)

  def byte(value: Byte): EncodedBytes = Vector(value)

  def intBigEndian(value: Int): EncodedBytes =
    Vector(
      ((value >>> 24) & 0xff).toByte,
      ((value >>> 16) & 0xff).toByte,
      ((value >>> 8) & 0xff).toByte,
      (value & 0xff).toByte
    )

  def unsignedVarint(value: Long): EncodedBytes =
    @tailrec def loop(remaining: Long, accumulated: EncodedBytes): EncodedBytes =
      if (remaining & ~0x7fL) == 0L then accumulated :+ (remaining & 0x7f).toByte
      else loop(remaining >>> 7, accumulated :+ ((remaining & 0x7f) | 0x80).toByte)
    loop(value, empty)

  def zigzagEncode(value: Long): Long = (value << 1) ^ (value >> 63)
  def zigzagDecode(value: Long): Long = (value >>> 1) ^ -(value & 1L)

  def intBytes(value: Int): EncodedBytes = unsignedVarint(zigzagEncode(value.toLong))

  def readInt: Read[Int] =
    readUnsignedVarint.flatMap: encoded =>
      val decoded = zigzagDecode(encoded)
      Either
        .cond(
          decoded >= Int.MinValue.toLong && decoded <= Int.MaxValue.toLong,
          decoded.toInt,
          CursorDecodingError.MalformedCursor("integer out of range")
        )
        .liftRead

  def longBytes(value: Long): EncodedBytes = unsignedVarint(zigzagEncode(value))

  def readLong: Read[Long] =
    readUnsignedVarint.map(zigzagDecode)

  def stringBytes(value: String): EncodedBytes =
    val utf8 = value.getBytes(StandardCharsets.UTF_8)
    unsignedVarint(utf8.length.toLong) ++ utf8

  def readString: Read[String] =
    for
      lengthLong <- readUnsignedVarint
      length <- narrowStringLength(lengthLong).liftRead
      bytes <- readBytes(length)
    yield String(bytes, StandardCharsets.UTF_8)

  private def narrowStringLength(length: Long): Either[CursorDecodingError, Int] =
    Either.cond(
      length >= 0L && length <= Int.MaxValue.toLong,
      length.toInt,
      CursorDecodingError.MalformedCursor("invalid string length")
    )

  def timestampBytes(value: OffsetDateTime): EncodedBytes =
    longBytes(value.toEpochSecond) ++
      unsignedVarint(value.getNano.toLong) ++
      longBytes(value.getOffset.getTotalSeconds.toLong)

  def readTimestamp: Read[OffsetDateTime] =
    for
      epochSecond <- readLong
      nano <- readNano
      offsetSeconds <- readOffsetSeconds
      value <- buildTimestamp(epochSecond, nano, offsetSeconds).liftRead
    yield value

  private def readNano: Read[Int] =
    readUnsignedVarint.flatMap: nano =>
      Either
        .cond(
          nano >= 0L && nano <= 999_999_999L,
          nano.toInt,
          CursorDecodingError.MalformedCursor("invalid timestamp field")
        )
        .liftRead

  private def readOffsetSeconds: Read[Int] =
    readLong.flatMap: offset =>
      Either
        .cond(
          offset >= -18L * 3600 && offset <= 18L * 3600,
          offset.toInt,
          CursorDecodingError.MalformedCursor("invalid timestamp field")
        )
        .liftRead

  private def buildTimestamp(
      epochSecond: Long,
      nano: Int,
      offsetSeconds: Int
  ): Either[CursorDecodingError, OffsetDateTime] =
    Try(
      OffsetDateTime
        .ofInstant(Instant.ofEpochSecond(epochSecond, nano.toLong), ZoneOffset.ofTotalSeconds(offsetSeconds))
    ).toEither.left
      .map(_ => CursorDecodingError.MalformedCursor("malformed timestamp"))

  def readByte: Read[Byte] =
    Read: state =>
      Either.cond(
        state.index < state.bytes.length,
        (state.copy(index = state.index + 1), state.bytes(state.index)),
        CursorDecodingError.MalformedCursor("truncated")
      )

  def readBytes(count: Int): Read[Array[Byte]] =
    Read: state =>
      Either.cond(
        count >= 0 && count <= state.bytes.length - state.index,
        (state.copy(index = state.index + count), state.bytes.slice(state.index, state.index + count)),
        CursorDecodingError.MalformedCursor("truncated")
      )

  def readHash: Read[Int] =
    readBytes(4).map: hashBytes =>
      ((hashBytes(0) & 0xff) << 24) | ((hashBytes(1) & 0xff) << 16) | ((hashBytes(2) & 0xff) << 8) | (hashBytes(
        3
      ) & 0xff)

  def readUnsignedVarint: Read[Long] =
    Read: initialState =>
      @tailrec def loop(
          state: ReaderState,
          shift: Int,
          acc: Long,
          byteCount: Int
      ): Either[CursorDecodingError, (ReaderState, Long)] =
        if byteCount >= 10 then Left(CursorDecodingError.MalformedCursor("malformed varint"))
        else if state.index >= state.bytes.length then Left(CursorDecodingError.MalformedCursor("truncated"))
        else
          val nextByte = state.bytes(state.index)
          val advanced = state.copy(index = state.index + 1)
          val updated = acc | ((nextByte & 0x7fL) << shift)
          if (nextByte & 0x80) == 0 then Right((advanced, updated))
          else loop(advanced, shift + 7, updated, byteCount + 1)
      loop(initialState, 0, 0L, 0)

  def requireExhausted: Read[Unit] =
    Read: state =>
      val remaining = state.bytes.length - state.index
      Either.cond(remaining == 0, (state, ()), CursorDecodingError.MalformedCursor("trailing data after parse"))

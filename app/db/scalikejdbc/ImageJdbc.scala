package db.scalikejdbc

import db.ImageDao
import org.intracer.wmua._
import org.scalawiki.dto.Page
import scalikejdbc._

object ImageJdbc extends SQLSyntaxSupport[Image] with ImageDao {

  implicit def session: DBSession = autoSession

  private val isNotDeleted = sqls.isNull(Selection.s.deletedAt)

  def fromPage(page: Page, contest: ContestJury): Option[Image] = {
    try {
      for (imageInfo <- page.images.headOption)
        yield new Image(page.id.get, contest.id, page.title, imageInfo.url.get, imageInfo.pageUrl.get, imageInfo.width.get, imageInfo.height.get, None)
    } catch {
      case e: Throwable =>
        println(e)
        throw e
    }
  }

  override val tableName = "images"

  val c = ImageJdbc.syntax("c")

  def apply(c: SyntaxProvider[Image])(rs: WrappedResultSet): Image = apply(c.resultName)(rs)

  def apply(c: ResultName[Image])(rs: WrappedResultSet): Image = new Image(
    pageId = rs.long(c.pageId),
    contest = rs.long(c.contest),
    title = rs.string(c.title),
    url = rs.string(c.url),
    pageUrl = rs.string(c.pageUrl),
    width = rs.int(c.width),
    height = rs.int(c.height),
    monumentId = rs.stringOpt(c.monumentId)
  )

  def batchInsert(images: Seq[Image]) {
    val column = ImageJdbc.column
    DB localTx { implicit session =>
      val batchParams: Seq[Seq[Any]] = images.map(i => Seq(
        i.pageId,
        i.contest,
        i.title,
        i.url,
        i.pageUrl,
        i.width,
        i.height,
        i.monumentId
      ))
      withSQL {
        insert.into(ImageJdbc).namedValues(
          column.pageId -> sqls.?,
          column.contest -> sqls.?,
          column.title -> sqls.?,
          column.url -> sqls.?,
          column.pageUrl -> sqls.?,
          column.width -> sqls.?,
          column.height -> sqls.?,
          column.monumentId -> sqls.?
        )
      }.batch(batchParams: _*).apply()
    }
  }

  def updateResolution(pageId: Long, width: Int, height: Int): Unit = withSQL {
    update(ImageJdbc).set(
      column.width -> width,
      column.height -> height
    ).where.eq(column.pageId, pageId)
  }.update().apply()

  def findAll(): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      //      .where.append(isNotDeleted)
      .orderBy(c.pageId)
  }.map(ImageJdbc(c)).list().apply()

  def findByContest(contest: Long): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      .where //.append(isNotDeleted)
      // .and
      .eq(c.contest, contest)
      .orderBy(c.pageId)
  }.map(ImageJdbc(c)).list().apply()

  def findByMonumentId(monumentId: String): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      .where
      .eq(c.monumentId, monumentId)
      .orderBy(c.pageId)
  }.map(ImageJdbc(c)).list().apply()

  def find(id: Long): Option[Image] = withSQL {
    select.from(ImageJdbc as c).where.eq(c.pageId, id) //.and.append(isNotDeleted)
  }.map(ImageJdbc(c)).single().apply()

  def bySelection(round: Long): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.round, round)
  }.map(ImageJdbc(c)).list().apply()

  def bySelectionNotSelected(round: Long): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.round, round)
      .and
      .eq(Selection.s.rate, 0)
  }.map(ImageJdbc(c)).list().apply()

  def bySelectionSelected(round: Long): List[Image] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.round, round).and
      .ne(Selection.s.rate, 0)
  }.map(ImageJdbc(c)).list().apply()

  def byUser(user: User, roundId: Long): Seq[Image] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.juryId, user.id).and
      .eq(Selection.s.round, roundId).and
      .append(isNotDeleted)
  }.map(ImageJdbc(c)).list().apply()

  def byUserSelected(user: User, roundId: Long): Seq[Image] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.juryId, user.id).and
      .eq(Selection.s.round, roundId).and
      .ne(Selection.s.rate, 0).and
      .append(isNotDeleted)
  }.map(ImageJdbc(c)).list().apply()

  def findWithSelection(id: Long, roundId: Long): Seq[ImageWithRating] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(c.pageId, id).and
      .eq(Selection.s.round, roundId)
  }.map(rs => (ImageJdbc(c)(rs), Selection(Selection.s)(rs))).list().apply().map { case (i, s) => ImageWithRating(i, Seq(s)) }

  def byUserImageWithRating(user: User, roundId: Long): Seq[ImageWithRating] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.juryId, user.id).and
      .eq(Selection.s.round, roundId)
      .and.append(isNotDeleted)
  }.map(rs => (ImageJdbc(c)(rs), Selection(Selection.s)(rs))).list().apply().map { case (i, s) => ImageWithRating(i, Seq(s)) }

  def byRating(roundId: Long, rate: Int): Seq[ImageWithRating] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.rate, rate).and
      .eq(Selection.s.round, roundId)
      .and.append(isNotDeleted)
  }.map(rs => (ImageJdbc(c)(rs), Selection(Selection.s)(rs))).list().apply().map { case (i, s) => ImageWithRating(i, Seq(s)) }

  def byRatingGE(roundId: Long, rate: Int): Seq[ImageWithRating] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.ge(Selection.s.rate, rate).and
      .eq(Selection.s.round, roundId)
      .and.append(isNotDeleted)
  }.map(rs => (ImageJdbc(c)(rs), Selection(Selection.s)(rs))).list().apply().map { case (i, s) => ImageWithRating(i, Seq(s)) }


  def byRound(roundId: Long): Seq[ImageWithRating] = withSQL {
    select.from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.round, roundId)
      .and.append(isNotDeleted)
  }.map(rs => (ImageJdbc(c)(rs), Selection(Selection.s)(rs))).list().apply().map { case (i, s) => ImageWithRating(i, Seq(s)) }

  def byRatingMerged(rate: Int, round: Int): Seq[ImageWithRating] = {
    val raw = ImageJdbc.byRating(round, rate)
    val merged = raw.groupBy(_.pageId).mapValues(iws => new ImageWithRating(iws.head.image, iws.map(_.selection.head)))
    merged.values.toSeq
  }

  def byRatingGEMerged(rate: Int, round: Int): Seq[ImageWithRating] = {
    val raw = ImageJdbc.byRatingGE(round, rate)
    val merged = raw.groupBy(_.pageId).mapValues(iws => new ImageWithRating(iws.head.image, iws.map(_.selection.head)))
    merged.values.toSeq
  }

  def byRoundMerged(round: Int): Seq[ImageWithRating] = {
    val raw = ImageJdbc.byRound(round)
    val merged = raw.groupBy(_.pageId).mapValues(iws => new ImageWithRating(iws.head.image, iws.map(_.selection.head)))
    merged.values.toSeq
  }

  import SQLSyntax.{sum, count}

  def byRoundSummed(roundId: Long): Seq[ImageWithRating] = withSQL {
    select(sum(Selection.s.rate), count(Selection.s.rate), c.result.*).from(ImageJdbc as c)
      .innerJoin(Selection as Selection.s).on(c.pageId, Selection.s.pageId)
      .where.eq(Selection.s.round, roundId)
      .and.gt(Selection.s.rate, 0)
      .and.append(isNotDeleted).groupBy(Selection.s.pageId)
  }.map(rs => (ImageJdbc(c)(rs), rs.int(1), rs.int(2))).list().apply().map {
    case (i, sum, count) => ImageWithRating(i, Seq(new Selection(0, i.pageId, sum, 0, roundId)), count)
  }
}

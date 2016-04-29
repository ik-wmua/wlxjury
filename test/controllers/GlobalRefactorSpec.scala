package controllers

import db.{ImageDao, ContestJuryDao}
import db.scalikejdbc.{ImageJdbc, ContestJuryJdbc}
import org.intracer.wmua.Image
import org.scalawiki.MwBot
import org.scalawiki.dto.{Revision, Page, Namespace}
import org.scalawiki.query.SinglePageQuery
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.test.FakeApplication
import play.api.test.Helpers._

import scala.concurrent.Future

class GlobalRefactorSpec extends Specification with Mockito {

  sequential

  val contestDao: ContestJuryDao = ContestJuryJdbc
  val imageDao: ImageDao = ImageJdbc

  def inMemDbApp[T](block: => T): T = {
    running(FakeApplication(additionalConfiguration = inMemoryDatabase()))(block)
  }

  def contestImage(id: Long, contest: Long) =
    Image(id, contest, s"File:Image$id.jpg", s"url$id", s"pageUrl$id", 640, 480, Some(s"12-345-$id"))

  def imageInfo(id: Long) = new Page(Some(id), Namespace.FILE, s"File:Image$id.jpg", images = Seq(
    new org.scalawiki.wlx.dto.Image(s"File:Image$id.jpg", Some(s"url$id"), Some(s"pageUrl$id"), Some(1234), Some(640), Some(480))
  ))

  def revision(id: Long, text: String) = new Page(Some(id), Namespace.FILE, s"File:Image$id.jpg", revisions = Seq(
    new Revision(Some(id + 100), Some(id), content = Some(text))
  ))

  "appendImages" should {
    "get images empty" in {
      inMemDbApp {
        val category = "Category:Category Name"
        val contestId = 13
        val imageId = 11
        val images = Seq.empty[Image]
        val imageInfos = Seq.empty[Page]

        val query = mock[SinglePageQuery]

        query.imageInfoByGenerator(
          "categorymembers", "cm", namespaces = Set(Namespace.FILE), props = Set("timestamp", "user", "size", "url"), titlePrefix = None
        ) returns Future.successful(imageInfos)

        query.revisionsByGenerator("categorymembers", "cm",
          Set.empty, Set("content", "timestamp", "user", "comment"), limit = "50", titlePrefix = None
        ) returns Future.successful(imageInfos)

        val commons = mock[MwBot]
        commons.page(category) returns query

        val g = new GlobalRefactor(commons)

        val contest = contestDao.create(Some(contestId), "WLE", 2015, "Ukraine", Some(category), 0, None)

        g.appendImages(category, contest)

        imageDao.findByContest(contestId) === images
      }
    }

    "get images one image with text" in {
      inMemDbApp {
        val category = "Category:Category Name"
        val contestId = 13
        val imageId = 11
        val images = Seq(contestImage(imageId, contestId).copy(description = Some("descr"), monumentId = Some("")))
        val imageInfos = Seq(imageInfo(imageId))
        val revisions = Seq(revision(imageId, "{{Information|description=descr}}"))

        val query = mock[SinglePageQuery]

        query.imageInfoByGenerator(
          "categorymembers", "cm", namespaces = Set(Namespace.FILE), props = Set("timestamp", "user", "size", "url"), titlePrefix = None
        ) returns Future.successful(imageInfos)

        query.revisionsByGenerator("categorymembers", "cm",
          Set.empty, Set("content", "timestamp", "user", "comment"), limit = "50", titlePrefix = None
        ) returns Future.successful(revisions)

        val commons = mock[MwBot]
        commons.page(category) returns query

        val g = new GlobalRefactor(commons)

        val contest = contestDao.create(Some(contestId), "WLE", 2015, "Ukraine", Some(category), 0, None)

        g.appendImages(category, contest)

        eventually {
          imageDao.findByContest(contestId) === images
        }
      }
    }

    "get images one image with descr and monumentId" in {
      inMemDbApp {
        val category = "Category:Category Name"
        val idTemplate = "monumentId"
        val contestId = 13
        val imageId = 11
        val descr = s"descr. {{$idTemplate|12-345-$imageId}}"
        val images = Seq(contestImage(imageId, contestId).copy(description = Some(descr)))
        val imageInfos = Seq(imageInfo(imageId))
        val revisions = Seq(revision(imageId, s"{{Information|description=$descr}}"))

        val query = mock[SinglePageQuery]

        query.imageInfoByGenerator(
          "categorymembers", "cm", namespaces = Set(Namespace.FILE), props = Set("timestamp", "user", "size", "url"), titlePrefix = None
        ) returns Future.successful(imageInfos)

        query.revisionsByGenerator("categorymembers", "cm",
          Set.empty, Set("content", "timestamp", "user", "comment"), limit = "50", titlePrefix = None
        ) returns Future.successful(revisions)

        val commons = mock[MwBot]
        commons.page(category) returns query

        val g = new GlobalRefactor(commons)

        val contest = contestDao.create(Some(contestId), "WLE", 2015, "Ukraine", Some(category), 0, Some(idTemplate))

        g.appendImages(category, contest)

        eventually {
          imageDao.findByContest(contestId) === images
        }
      }
    }

  }
}
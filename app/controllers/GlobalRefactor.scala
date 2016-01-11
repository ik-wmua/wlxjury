package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.intracer.wmua.cmd.{ImageEnricher, ImageTextFromCategory, ImageInfoFromCategory}
import org.joda.time.DateTime
import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.{Generator, Query}
import org.scalawiki.dto.{Namespace, Page}
import org.scalawiki.query.{DslQuery, SinglePageQuery}
import org.scalawiki.wikitext.TemplateParser
import org.scalawiki.wlx.dto.Contest
import org.scalawiki.wlx.query.MonumentQuery

import scala.concurrent.Future

class GlobalRefactor(val commons: MwBot) {

  //  val commons = MwBot.get(MwBot.commons)

  import scala.concurrent.ExecutionContext.Implicits.global

  def initContest(category: String, contest: ContestJury): Any = {
    val images = ImageJdbc.findByContest(contest.id.get)

    if (images.isEmpty) {
      initImagesFromCategory(contest, category, Seq.empty)
    } else {
      //        initContestFiles(contest, images)
      //createJury()
    }
  }

  def appendImages(category: String, contest: ContestJury, idsFilter: Set[String] = Set.empty): Any = {
    val images = ImageJdbc.findByContest(contest.id.get)

    initImagesFromCategory(contest, category, images, idsFilter)
  }

  def createJury() {
    val selection = SelectionJdbc.findAll()
    if (selection.isEmpty) {
    }
  }

  def initLists(contest: Contest) = {

    if (MonumentJdbc.findAll().isEmpty) {
      val ukWiki = MwBot.get("uk.wikipedia.org")

      //    listsNew(system, http, ukWiki)

      val monumentQuery = MonumentQuery.create(contest)
      val monuments = monumentQuery.byMonumentTemplate()

      val fromDb = MonumentJdbc.findAll
      val inDbIds = fromDb.map(_.id).toSet

      val newMonuments = monuments.filterNot(m => inDbIds.contains(m.id))

      MonumentJdbc.batchInsert(newMonuments)
    }

  }

  def initImagesFromCategory(
                              contest: ContestJury,
                              category: String,
                              existing: Seq[Image],
                              idsFilter: Set[String] = Set.empty) = {
    val existingPageIds = existing.map(_.pageId).toSet

    val imageInfos = ImageInfoFromCategory(category, contest, commons).apply()
    val revInfo = ImageTextFromCategory(category, contest, contest.monumentIdTemplate, commons).apply()

    for (images <- ImageEnricher.zipWithRevData(imageInfos, revInfo)) {
      saveNewImages(contest, images)
    }
  }

  def updateMonuments(query: SinglePageQuery, contest: ContestJury) = {

    val future = query.imageInfoByGenerator("categorymembers", "cm",
      props = Set("timestamp", "user", "size", "url"),
      titlePrefix = None)


    future.map {
      //bot.page("User:***REMOVED***/embeddedin").imageInfoByGenerator("images", "im", props = Set("timestamp", "user", "size", "url"), titlePrefix = Some(""))
      //    query.imageInfoByGenerator("categorymembers", "cm", props = Set("timestamp", "user", "size", "url"), titlePrefix = None).map {
      filesInCategory =>
        val newImagesOrigIds: Seq[Image] = filesInCategory.flatMap(page => ImageJdbc.fromPage(page, contest)).sortBy(_.pageId)


        query.revisionsByGenerator("categorymembers", "cm",
          Set.empty, Set("content", "timestamp", "user", "comment"), limit = "50", titlePrefix = None) map {
          pages =>

            val idRegex = """(\d\d)-(\d\d\d)-(\d\d\d\d)"""
            val ids: Seq[String] = monumentIds(pages, Set.empty, "Monument Ukraine")

            val imagesWithIds = newImagesOrigIds.zip(ids).map {
              case (image, id) => image.copy(monumentId = Some(id))
            }.filter(_.monumentId.isDefined)

            imagesWithIds.foreach {
              image =>
                ImageJdbc.updateMonumentId(image.pageId, image.monumentId.get)
            }
        }
    }
  }

  def defaultParam(text: String, templateName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt("1"))

  def namedParam(text: String, templateName: String, paramName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt(paramName))

  def monumentIds(pages: Seq[Page], existingPageIds: Set[Long], monumentIdTemplate: String): Seq[String] = {

    //    pages.flatMap {
    //      page =>
    //        page.text.flatMap(text => defaultParam(text, monumentIdTemplate))
    //          .map(id => page.id.get -> (if (id.length < 100) id else id.substring(0, 100)))
    //    }.toMap

    pages.sortBy(_.id).filterNot(i => existingPageIds.contains(i.id.get)).map {
      page =>
        page.text.flatMap(text => defaultParam(text, monumentIdTemplate))
          //                .map(id => if (id.matches(idRegex)) Some(id) else Some(id))
          .map(id => if (id.length < 100) id else id.substring(0, 100)).getOrElse("")
    }
  }

  def descriptions(pages: Seq[Page], existingPageIds: Set[Long]): Seq[String] = {
    pages.sortBy(_.id).filterNot(i => existingPageIds.contains(i.id.get)).map {
      page =>
        page.text.flatMap(text => namedParam(text, "Information", "description")).getOrElse("")
    }
  }


  def saveNewImages(contest: ContestJury, imagesWithIds: Seq[Image]) = {
    println("saving images: " + imagesWithIds.size)
    ImageJdbc.batchInsert(imagesWithIds)
    println("saved images")
    //createJury()
    //    initContestFiles(contest, imagesWithIds)
  }

  def initUrls() {
    KOATUU.load()
  }

  def getCategories(parent: String): Future[Seq[Page]] = {

    val action = Action(Query(
      Generator(ListArgs.toDsl("categorymembers", Some(parent), None, Set(Namespace.CATEGORY), Some("max")))
    ))
    new DslQuery(action, commons).run()
  }

  def addContestCategories(contestName: String, year: Int) = {
    val parent = s"Category:Images from $contestName $year"
    getCategories(parent).map {
      categories =>
        val contests = categoriesToContests(contestName, year, parent, categories)
        //        val ids = ContestJuryJdbc.batchInsert(contests)
        //        val rounds =  ids.map (id => Round(None, 1, Some("Round 1"), id))
        //
        //        rounds.foreach {
        //          round =>
        //            val roundId = RoundJdbc.create(round).id
        //            ContestJuryJdbc.setCurrentRound(round.contest, roundId.get)
        //        }
        // Await.result(commons.login("***REMOVED***", "***REMOVED***"), 1.minute)

        contests.foreach {
          contest =>
            val dbContest = ContestJuryJdbc.byCountry(contest.country).filter(c => c.year == year && c.name == contestName).head
            //GlobalRefactor.appendImages(contest.images.get, dbContest)
            addAdmin(dbContest)
        }
    }
  }

  def rateByCategory(category: String, juryId: Long, roundId: Long, rate: Int) = {

    val future = commons.page(category).imageInfoByGenerator("categorymembers", "cm",
      props = Set("timestamp", "user", "size", "url"),
      titlePrefix = None)

    future.map {
      filesInCategory =>

        filesInCategory.foreach {
          page =>
            SelectionJdbc.rate(page.id.get, juryId, roundId, rate)
        }
    }
  }

  def distributeByCategory(parent: String, contest: ContestJury) = {

    //    val round = Round(None, 1, Some("Round 1"), contest.id.get, distribution = 0, active = true)
    //
    //    val roundId = RoundJdbc.create(round).id
    //    ContestJuryJdbc.setCurrentRound(round.contest, roundId.get)

    val round = RoundJdbc.find(89).get

    getCategories(parent).map {
      categories =>

        val jurors = categories.map("WLMUA2015_" + _.title.split("-")(1).trim.replaceAll(" ", "_"))
        val logins = jurors ++ Seq(contest.name + "OrgCom")
        val passwords = logins.map(s => UserJdbc.randomString(8)) // !! i =>
      val users = logins.zip(passwords).map {
          case (login, password) =>
            UserJdbc.create(
              login,
              login, UserJdbc.sha1(contest.country + "/" + password),
              if //(login.contains("Jury"))
              (jurors.contains(login))
                Set("jury")
              else Set("organizer"),
              contest.id.get,
              Some("uk"))
        }

        logins.zip(passwords).foreach {
          case (login, password) =>
            println(s"$login / $password")
        }

        val jurorsDb = users.init

        categories.zip(jurorsDb).map {
          case (category, juror) =>

            val future = commons.page(category.title).imageInfoByGenerator("categorymembers", "cm",
              props = Set("timestamp", "user", "size", "url"),
              titlePrefix = None)

            future.map {
              filesInCategory =>

                val selection = filesInCategory.map(img => new Selection(0, img.id.get, 0, juror.id.get, round.id.get, DateTime.now))
                SelectionJdbc.batchInsert(selection)
            }
        }
    }
  }

  def addAdmin(contest: ContestJury): Unit = {
    val shortContest = contest.name.split(" ").map(_.head).mkString("")
    val shortCountry = contest.country.replaceFirst("the ", "").replaceFirst(" & Nagorno-Karabakh", "").split(" ").mkString("")

    val name = shortContest + contest.year + shortCountry + "Admin"

    val password = UserJdbc.randomString(8)
    val hash = UserJdbc.sha1(contest.country + "/" + password)
    val user = User(name, name, None, Set("admin"), Some(hash), contest.id.get, Some("en"))

    println(s"admin user: $name / $password")
    UserJdbc.create(user)
  }


  def categoriesToContests(contest: String, year: Int, parent: String, categories: Seq[Page]): Seq[ContestJury] = {

    val imageCategories = categories.filter(_.title.startsWith(parent + " in "))
    val contests = imageCategories.map {
      imageCategory =>
        val title = imageCategory.title
        val country = title.replaceFirst(parent + " in ", "")
        ContestJury(None, contest, year, country, Some(title), 0, None)
    }
    contests
  }
}

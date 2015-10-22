package org.intracer.wmua

import akka.actor.ActorSystem
import db.scalikejdbc._
import org.scalawiki.dto.Namespace
import org.scalawiki.http.HttpClientImpl
import org.scalawiki.wlx.dto.{SpecialNomination, Contest}
import org.scalawiki.wlx.query.MonumentQuery
import org.scalawiki.MwBot
import controllers.GlobalRefactor
import org.joda.time.DateTime
import scalikejdbc.{ConnectionPool, GlobalSettings, LoggingSQLAndTimeSettings}

import scala.concurrent.Await

object Tools {

  //  db.default.driver=com.mysql.jdbc.Driver
  //  db.default.url="jdbc:mysql://localhost/wlxjury"
  //  db.default.user="***REMOVED***"
  //  db.default.password="***REMOVED***"

  val regions = Set(
    //    "01", // Crimea
    //    "05", // Vinnitsa
    //    "07", // Volyn
    //    "12", // Dnipro
    //    "18", // Dnipro
    //    "23", // Zaporizzia
    //     "26", // IF
    //     "32",  // Kyivska
    //    "35",  // Kirovohradska,
    //    "48", // Mykolaivska
    //    "53", // Poltavska
    //    "56", // RIvnenska
    //    "61", //Ternopilska
    //      "65", //Khersonska
    //    "71", //Khersonska
    //    "85" //Sevastopol
    "44" // Luhanska
  )


  def main(args: Array[String]) {
    Class.forName("com.mysql.jdbc.Driver")
    ConnectionPool.singleton("jdbc:mysql://jury.wikilovesearth.org.ua/wlxjury", "***REMOVED***", "***REMOVED***")
    //ConnectionPool.singleton("jdbc:mysql://localhost/wlxjury", "***REMOVED***", "***REMOVED***")

    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
      enabled = true,
      singleLineMode = false,
      printUnprocessedStackTrace = false,
      stackTraceDepth = 15,
      logLevel = 'info,
      warningEnabled = false,
      warningThresholdMillis = 3000L,
      warningLogLevel = 'warn
    )

    //users()

    //    GlobalRefactor.addContestCategories("Wiki Loves Monuments", 2015)
    val contest = ContestJuryJdbc.find(52).get

    // GlobalRefactor.rateByCategory("Category:Obviously ineligible submissions for WLM 2015 in Ukraine", 491, 89, -1)

    //val query = GlobalRefactor.commons.page(contest.images.get)
    //GlobalRefactor.updateMonuments(query, contest)
    //GlobalRefactor.distributeByCategory("Category:WLM 2015 in Ukraine Round Zero", contest.get)
//    addUsers(contest, 8)

        val round = RoundJdbc.find(107)
        ImageDistributor.distributeImages(contest, round.get)
     //initImages()
    //internationalRound()
    // val wlmContest = Contest.WLMUkraine(2014, "09-15", "10-15")
    //wooden(wlmContest)

    //    GlobalRefactor.initLists(wlmContest)
    //    return

    //    ConnectionPool.singleton("jdbc:mysql://localhost/wlxjury", "***REMOVED***", "***REMOVED***")

    //    for (contest <- ContestJury.findAll()) {
    //
    //      if (contest.country == "Ghana") {
    //        println(contest)

    //                controllers.GlobalRefactor.initContest("Category:Images from Wiki Loves Earth 2014 in " + contest.country,  contest)

    //roundAndUsers(contest)

    //updateResolution(contest)

    //Admin.distributeImages(contest, Round.findByContest(contest.id).head)
    //        createNextRound()
    //      }
    //    }
  }

  def roundAndUsers(contest: ContestJury) {
    val rounds = RoundJdbc.findByContest(contest.id.get)
    val newRoundNum = 2
    val round = if (rounds.size < newRoundNum) {
      val r = RoundJdbc.create(newRoundNum, Some(""), contest.id.get, "jury", 0, 10, Some(1), Some(1), None)
      ContestJuryJdbc.setCurrentRound(contest.id.get, r.id.get)
      r
    } else {
      rounds.find(_.number == newRoundNum).head
    }

    if (round.jurors.isEmpty) {
      for (i <- 1 to 10) {
        val login = contest.country.replaceAll("[ \\-\\&]", "")
        UserJdbc.create(
          "Test user " + contest.country + i,
          login + i + "@test", UserJdbc.sha1(contest.country + "/" + "123"),
          Set("jury"),
          contest.id.get,
          Some("en"))
      }
    }

    val jurors = round.jurors

    ContestJuryJdbc.setCurrentRound(contest.id.get, round.id.get)

    createNextRound(round, jurors, rounds.find(_.number == newRoundNum - 1).get)
  }

  def createNextRound(round: Round, jurors: Seq[User], prevRound: Round) = {
    val newImages = ImageJdbc.byRatingMerged(0, round.id.get)
    if (newImages.isEmpty) {

      //      val selectedRegions = Set("01", "07", "14", "21", "26", "44", "48", "74")
      //
      val images = ImageJdbc.byRatingMerged(1, prevRound.id.get).toArray
      //        ImageJdbc.byRoundMerged(prevRound.id.get).sortBy(-_.totalRate(prevRound))//.filter(_.image.region.exists(regions.contains)).sortBy(-_.totalRate)
      //      .toArray.take(29)

      //      ImageJdbc.findAll().filter(_.region == Some("44"))

      val imagesByJurors = images /*.filter {
        iwr => iwr.selection.exists {
          s => jurors.exists(j => j.id.contains(s.juryId))
        }
      }*/
      //
      val selection = jurors.flatMap { juror =>
          imagesByJurors.map(img => new Selection(0, img.pageId, 0, juror.id.get, round.id.get, DateTime.now))
        }

      //      val images = ImageJdbc.byRoundMerged(prevRound.id.toInt).sortBy(-_.totalRate).take(21)
      //      val selection = images.flatMap(_.selection).map(_.copy(id = 0, round = round.id))

      SelectionJdbc.batchInsert(selection)
    }
  }

  def insertMonumentsWLM() = {

    val wlmContest = Contest.WLMUkraine(2014, "09-15", "10-15")

    val monumentQuery = MonumentQuery.create(wlmContest)
    val allMonuments = monumentQuery.byMonumentTemplate(wlmContest.listTemplate)
    println(allMonuments.size)
  }


  def updateResolution(contest: ContestJury) = {

    val system = ActorSystem()
    val http = new HttpClientImpl(system)

    import system.dispatcher

    val commons = new MwBot(http, system, controllers.Global.COMMONS_WIKIMEDIA_ORG, None)

    import scala.concurrent.duration._

    Await.result(commons.login("***REMOVED***", "***REMOVED***"), 1.minute)

    val category = "Category:Images from Wiki Loves Earth 2014 in Ghana"
    val query = commons.page(category)

    query.imageInfoByGenerator("categorymembers", "cm", Set(Namespace.FILE)).map {
      filesInCategory =>
        val newImages = filesInCategory.flatMap(page => ImageJdbc.fromPage(page, contest)).groupBy(_.pageId)
        val existing = ImageJdbc.findAll().toSet

        for (i1 <- existing;
             i2 <- newImages.get(i1.pageId).map(seq => seq.head)
             if i1.width != i2.width || i1.height != i2.height) {
          println(s"${i2.pageId} ${i1.title}  ${i1.width}x${i1.height} -> ${i2.width}x${i2.height}")
          ImageJdbc.updateResolution(i1.pageId, i2.width, i2.height)
        }
    }
  }

  def users() = {
    for (i <- 1 to 12) {
      val password = UserJdbc.randomString(8)
      val hash = UserJdbc.sha1("Ukraine/" + password)

      println(s"pw: $password, hash: $hash")

    }

  }

  def initImages(): Unit = {

    val contest = ContestJuryJdbc.find(70L).get

    //    val category: String = "User:***REMOVED***/files" // "Commons:Wiki Loves Earth 2014/Finalists"
    GlobalRefactor.appendImages(contest.images.get, contest)

    val prevRound = RoundJdbc.find(89L).get
    val round = RoundJdbc.find(104L).get

    //    val selection = Selection.byRound(22L)

    // ImageDistributor.distributeImages(contest, round)

    createNextRound(round, round.jurors, prevRound)
  }

  def wooden(wlmContest: Contest) = {
    val page = "Commons:Images from Wiki Loves Monuments 2014 in Ukraine special nomination Пам'ятки дерев'яної архітектури України"
    val monumentQuery = MonumentQuery.create(wlmContest)
    val nomination = SpecialNomination.wooden
    val monumentsListsId = monumentQuery.byPage(nomination.pages.head, nomination.listTemplate).map(_.id).toSet
    val nanaRound = RoundJdbc.find(27L).get
    val monumentIdsByNana = ImageJdbc.byRatingMerged(1, nanaRound.id.get).flatMap(_.image.monumentId).toSet

    val onlyNana = monumentIdsByNana -- monumentsListsId

    val allIds = monumentIdsByNana ++ monumentsListsId

    val category = "Category:Images from Wiki Loves Monuments 2014 in Ukraine"
    val contest = ContestJuryJdbc.find(20L).get
    GlobalRefactor.appendImages(category, contest, allIds)

  }

  def internationalRound() = {
    //GlobalRefactor.commons.page("Commons:Wiki Loves Earth 2015/Winners").
    val contest = ContestJuryJdbc.find(37L).get

    GlobalRefactor.appendImages("Commons:Wiki Loves Earth 2015/Winners", contest)
  }

  def addUsers(contest: ContestJury, number: Int) = {
    val country = contest.country.replaceAll("[ \\-\\&]", "")
    val jurors = (1 to number).map(i => country + "Round2_Jury" + i)
//      Seq("tomasz@twkozlowski.com",
//        "pocoapocowiki@gmail.com",
//        "habib.mhenni@gmail.com",
//        "zharkikh6@gmail.com",
//        "Julie2393@gmail.com",
//        "mik-kiev@yandex.ua",
//        "alexprosecutor@hotmail.com",
//        "***REMOVED***",
//        "***REMOVED***",
//        "dmytruk_v@ukr.net",
//        "solochubay@gmail.com",
//        "lshyamal@gmail.com",
//        "UkraineJuror14"
//      )


    //val orgCom = Seq("arjan@arjandenboer.nl", "vandepas@wikimedia.nl", "meijer@wikimedia.nl")
    val orgCom = Seq(country + "OrgCom")
    //
    val logins = //Seq.empty
      jurors //++ orgCom

    //  (1 to number).map(i => country + "Jury" + i) ++ Seq(country + "OrgCom")
    val passwords = logins.map(s => UserJdbc.randomString(8)) // !! i =>

    logins.zip(passwords).foreach {
      case (login, password) =>
        UserJdbc.create(
          login,
          login, UserJdbc.sha1(contest.country + "/" + password),
          if //(login.contains("Jury"))
          (jurors.contains(login))
            Set("jury")
          else Set("organizer"),
          contest.id.get,
          Some("en"))
    }

//    val round = Round(None, 1, Some("Round 1"), contest.id.get, distribution = 0, active = true, rates = Round.ratesById(10))
//
//    val roundId = RoundJdbc.create(round).id
//    ContestJuryJdbc.setCurrentRound(round.contest, roundId.get)
//
//    val dbRound = round.copy(id = roundId)

    //        val dbRound = RoundJdbc.find(94).get

    logins.zip(passwords).foreach {
      case (login, password) =>
        println(s"$login / $password")
    }

    //    ImageDistributor.distributeImages(contest, dbRound)
    //
    //    logins.zip(passwords).foreach {
    //      case (login, password) =>
    //        println(s"$login / $password")
    //    }
  }

}

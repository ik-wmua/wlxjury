package controllers

import java.io.{File, FileReader}
import java.util.Properties

import client.dto._
import client.wlx.Monument
import client.{HttpClientImpl, MwBot}
import org.intracer.wmua._
import play.Play
import play.api._
import play.api.libs.concurrent.Akka
import scalikejdbc.{GlobalSettings, LoggingSQLAndTimeSettings}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}


object Global {
  final val COMMONS_WIKIMEDIA_ORG = "commons.wikimedia.org"

  var galleryUrls = collection.mutable.Map[Long, String]()
  var largeUrls = collection.mutable.Map[Long, String]()
  var thumbUrls = collection.mutable.Map[Long, String]()

  val projectRoot = Play.application().path()

  //  initUrls()

  import play.api.Play.current
  import play.api.libs.concurrent.Execution.Implicits._

  val http = new HttpClientImpl(Akka.system)

  val commons = new MwBot(http, Akka.system, COMMONS_WIKIMEDIA_ORG)

  var contestByCountry: Map[String, Seq[Contest]] = Map.empty


  def onStart(app: Application) {
    Logger.info("Application has started")

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


    contestByCountry = Contest.byCountry

    KOATUU.load()
    contestImages()

  }


  def contestImages() {
    import scala.concurrent.duration._
    Await.result(commons.login("***REMOVED***", "***REMOVED***"), 1.minute)

    initContestFiles(Image.findAll())

//    commons.categoryMembers(PageQuery.byTitle("Category:Images from Wiki Loves Earth 2014"), Set(Namespace.CATEGORY_NAMESPACE)) flatMap {
//      categories =>
//        val filtered = categories.filter(c => c.title.startsWith("Category:Images from Wiki Loves Earth 2014 in "))
//        Future.traverse(filtered) {
//          category =>
//
//            if (category.title.contains("Ukraine"))
//              initUkraine("Category:WLE 2014 in Ukraine Round One", Some("Ukraine"))
//            else
//              initUkraine(category.title)
//
//            //    initLists()
//
//            Future(1)
//        }
//    }
  }

  def initUkraine(category: String, countryOpt: Option[String]) = {
    val country = countryOpt.fold(category.replace("Category:Images from Wiki Loves Earth 2014 in ", ""))(identity)

    //"Ukraine"
    val contestOpt = contestByCountry.get(country).flatMap(_.headOption)

    for (contest <- contestOpt) {
      val images = Image.findByContest(contest.id)

      if (images.isEmpty) {
        val query: SinglePageQuery = PageQuery.byTitle(category)
        //PageQuery.byId(category.pageid)
        initImagesFromCategory(contest, query)
      } else {
//        initContestFiles(contest, images)
        createJury()
      }
    }
  }

  def createJury() {
//    val matt = User.findByEmail("***REMOVED***")
//
//    if (matt.isEmpty) {
//      for (user <- UkrainianJury.users) {
//        Admin.createNewUser(User.wmuaUser, user)
//      }
//    }
    val selection = Selection.findAll()
    if (selection.isEmpty) {
      Admin.distributeImages(Contest.byId(14).get)
    }
  }

  def initLists() = {

    if (MonumentJdbc.findAll().isEmpty) {
      val ukWiki = new MwBot(http, Akka.system, "uk.wikipedia.org")

      Await.result(ukWiki.login("***REMOVED***", "***REMOVED***"), http.timeout)
      //    listsNew(system, http, ukWiki)
      Monument.lists(ukWiki, "ВЛЗ-рядок").foreach {
        monuments =>

          MonumentJdbc.batchInsert(monuments)

      }
    }

  }

  def initImagesFromCategory(contest: Contest, query: SinglePageQuery): Future[Unit] = {
    commons.imageInfoByGenerator("categorymembers", "cm", query, Set(Namespace.FILE_NAMESPACE)).map {
      filesInCategory =>
        val newImages: Seq[Image] = filesInCategory.flatMap(page => Image.fromPage(page, contest)).sortBy(_.pageId)

        contest.monumentIdTemplate.fold(saveNewImages(contest, newImages)) { monumentIdTemplate =>
          commons.revisionsByGenerator("categorymembers", "cm", query,
            Set.empty, Set("content", "timestamp", "user", "comment")) map {
            pages =>

              val idRegex = """(\d\d)-(\d\d\d)-(\d\d\d\d)"""
              val ids: Seq[Option[String]] = pages.sortBy(_.pageid)
                .flatMap(_.text.map(Template.getDefaultParam(_, monumentIdTemplate)))
//                .map(id => if (id.matches(idRegex)) Some(id) else Some(id))
              .map(id => if (id.size < 100) Some(id) else None)

              val imagesWithIds = newImages.zip(ids).map {
                case (image, Some(id)) => image.copy(monumentId = Some(id))
                case (image, None) => image
              }
              saveNewImages(contest, imagesWithIds)
          }
        }
    }
  }

  def saveNewImages(contest: Contest, imagesWithIds: Seq[Image]) = {
    Image.batchInsert(imagesWithIds)
    createJury()
//    initContestFiles(contest, imagesWithIds)
  }

  def initContestFiles(filesInCategory: Seq[Image]) {

    val gallerySize = 300
    val thumbSize = 150
    val largeSize = 1280
    for (file <- filesInCategory) {
      val galleryUrl = resizeTo(file, gallerySize)
      val thumbUrl = resizeTo(file, thumbSize)
      val largeUrl = resizeTo(file, largeSize)

      galleryUrls(file.pageId) = galleryUrl
      thumbUrls(file.pageId) = thumbUrl
      largeUrls(file.pageId) = largeUrl
    }
  }

  def resizeTo(info: Image, resizeTo: Int) = {
    val h = info.height
    val w = info.width

    val px = if (w >= h) Math.min(w, resizeTo)
    else {
      if (h > resizeTo) {
        val ratio = h.toFloat / resizeTo
        w / ratio
      } else {
        w
      }
    }

    val isPdf = info.title.toLowerCase.endsWith(".pdf")

    val url = info.url
    if (px < w || isPdf) {
      val lastSlash = url.lastIndexOf("/")
      url.replace("//upload.wikimedia.org/wikipedia/commons/", "//upload.wikimedia.org/wikipedia/commons/thumb/") + "/" + (if (isPdf) "page1-" else "") +
        px.toInt + "px-" + url.substring(lastSlash + 1) + (if (isPdf) ".jpg" else "")
    } else {
      url
    }
  }


  def initUrls() {

    //    val galleryUrlsFiles = (1 to 10).map(i => new File(s"${projectRoot.getAbsolutePath}/conf/urls/galleryUrls${i}.txt"))
    //    val largeUrlsFiles = (1 to 10).map(i => new File(s"${projectRoot.getAbsolutePath}/conf/urls/largeUrls${i}.txt"))
    //    val thumbsUrlsFiles = (1 to 10).map(i => new File(s"${projectRoot.getAbsolutePath}/conf/urls/thumbUrls${i}.txt"))
    //
    //    Logger.info("galleryUrlsFiles" + galleryUrlsFiles)
    //    Logger.info("largeUrlsFiles" + largeUrlsFiles)
    //    Logger.info("thumbsUrlsFiles" + thumbsUrlsFiles)
    //
    //    galleryUrls = galleryUrlsFiles.map(loadFileCache).fold(Map[String, String]())(_ ++ _)
    //    largeUrls = largeUrlsFiles.map(loadFileCache).fold(Map[String, String]())(_ ++ _)
    //    thumbUrls = thumbsUrlsFiles.map(loadFileCache).fold(Map[String, String]())(_ ++ _)

    //files = SortedSet[String](galleryUrls.keySet.toSeq:_*).toSeq.slice(0, 1500)

    //    for (file <- files) {
    //      thumbUrls.put(file, w.getImageUrl(file, 150, 120))
    //      galleryUrls.put(file, w.getImageUrl(file, 300, 200))
    //      largeUrls.put(file, w.getImageUrl(file, 1280, 1024))
    //    }

    KOATUU.load()
  }

  def loadFileCache(file: File): Map[String, String] = {
    val galleryUrlsProps = new Properties
    galleryUrlsProps.load(new FileReader(file))

    Logger.info("loadFileCache file " + file)
    Logger.info("loadFileCache size " + galleryUrlsProps.size())
    Logger.info("loadFileCache head " + galleryUrlsProps.asScala.head)

    galleryUrlsProps.asScala.toMap
  }

}
package controllers

import java.net.URLEncoder

import _root_.db.scalikejdbc.ContestJuryJdbc
import com.codahale.metrics.{JmxReporter, MetricRegistry}
import org.intracer.wmua._
import org.scalawiki.MwBot
import play.Play
import play.api._

object Global {
  final val COMMONS_WIKIMEDIA_ORG = "commons.wikimedia.org"

  val gallerySizeX = 300
  val gallerySizeY = 250
  val gallerySizeXDouble = 600
  val gallerySizeYDouble = 400
  val thumbSizeX = 150
  val thumbSizeY = 120
  val largeSizeX = 1280
  val largeSizeY = 1100

  private val galleryUrls = collection.mutable.Map[Long, String]()
  private val largeUrls = collection.mutable.Map[Long, String]()
  private val thumbUrls = collection.mutable.Map[Long, String]()

  val metrics = new MetricRegistry()

  lazy val commons = MwBot.fromHost(COMMONS_WIKIMEDIA_ORG)

  def initCountry(category: String, countryOpt: Option[String]) = {
    val country = countryOpt.fold(category.replace("Category:Images from Wiki Loves Earth 2014 in ", ""))(identity)

    //"Ukraine"
    val contestOpt = ContestJuryJdbc.where('country -> country).apply().headOption

    for (contest <- contestOpt) {
      globalRefactor.initContest(category, contest)
    }
  }

  def globalRefactor = {
    val commons = MwBot.fromHost(MwBot.commons)
    new GlobalRefactor(commons)
  }

  def initContestFiles(filesInCategory: Seq[Image]) {

    for (file <- filesInCategory) {
      val galleryUrl = resizeTo(file, gallerySizeX, gallerySizeY)
      val thumbUrl = resizeTo(file, thumbSizeX, thumbSizeY)
      val largeUrl = resizeTo(file, largeSizeX, largeSizeY)

      galleryUrls(file.pageId) = galleryUrl
      thumbUrls(file.pageId) = thumbUrl
      largeUrls(file.pageId) = largeUrl
    }
  }

  def resizeTo(info: Image, resizeToWidth: Int, resizeToHeight: Int): String = {

    val px = info.resizeTo(resizeToWidth, resizeToHeight)

    val isPdf = info.title.toLowerCase.endsWith(".pdf")
    val isTif = info.title.toLowerCase.endsWith(".tif")
    val isSvg = info.title.toLowerCase.endsWith(".svg")

    if (px < info.width || isPdf || isTif || isSvg) {
      val file = URLEncoder.encode(info.title.replaceFirst("File:", ""), "UTF-8")
      s"https://commons.wikimedia.org/w/thumb.php?f=$file&w=$px"
    } else {
      info.url.getOrElse("")
    }
  }

  def resizeTo(info: Image, resizeToHeight: Int): String = {

    val px = info.resizeTo(resizeToHeight)

    val isPdf = info.title.toLowerCase.endsWith(".pdf")
    val isTif = info.title.toLowerCase.endsWith(".tif")
    val isSvg = info.title.toLowerCase.endsWith(".svg")

    if (px < info.width || isPdf || isTif || isSvg) {
      val file = URLEncoder.encode(info.title.replaceFirst("File:", "").replace(" ", "_"), "UTF-8")
      s"https://commons.wikimedia.org/w/thumb.php?f=$file&w=$px"
    } else {
      info.url.getOrElse("")
    }
  }

  def srcSet(image: Image, resizeToWidth: Int, resizeToHeight: Int) = {
    s"${resizeTo(image, (resizeToWidth*1.5).toInt, (resizeToHeight*1.5).toInt)} 1.5x, ${resizeTo(image, resizeToWidth*2, resizeToHeight*2)} 2x"
  }
}
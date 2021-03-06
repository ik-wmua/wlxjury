package controllers

import db.scalikejdbc.MonumentJdbc
import org.scalawiki.wlx.dto.{Contest, Monument}
import org.scalawiki.wlx.query.MonumentQuery
import play.api.Play.current
import play.api.i18n.Messages.Implicits._
import play.api.mvc.{Action, Controller}

object Monuments extends Controller {

  def list = Action {
    implicit request =>

      val monuments = MonumentJdbc.findAll(Some(20))
      Ok(views.html.monuments(monuments))
  }

  def byId(id: String) = Action {
    implicit request =>

      val monuments = MonumentJdbc.find(id).toSeq
      Ok(views.html.monuments(monuments))
  }

  def updateLists(contest: Contest) = {
    val monumentQuery = MonumentQuery.create(contest)
    val monuments = monumentQuery.byMonumentTemplate()

    val fromDb = MonumentJdbc.findAll()
    val inDbIds = fromDb.map(_.id).toSet

    def truncate(monument: Monument, field: String, max: Int, copy: String => Monument): Monument = {
      if (field.length > max) {
        copy(field.substring(0, max))
      } else monument
    }

    def truncOpt(monument: Monument, field: Option[String], max: Int, copy: Option[String] => Monument): Monument = {
      if (field.exists(_.length > max)) {
        copy(field.map(_.substring(0, max)))
      } else monument
    }

    val newMonuments = monuments.view
      .filterNot(m => inDbIds.contains(m.id))
      .map(m => truncate(m, m.name, 512, s => m.copy(name = s)))
      .map(m => truncOpt(m, m.typ, 255, s => m.copy(typ = s)))
      .map(m => truncOpt(m, m.subType, 255, s => m.copy(subType = s)))
      .map(m => truncOpt(m, m.year, 255, s => m.copy(year = s)))
      .map(m => truncOpt(m, m.city, 255, s => m.copy(city = s)))

    MonumentJdbc.batchInsert(newMonuments)
  }

}

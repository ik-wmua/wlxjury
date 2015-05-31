package org.intracer.wmua

import controllers.Gallery
import org.joda.time.DateTime

import scala.collection.mutable

case class User(fullname: String,
                email: String,
                id: Option[Long],
                roles: Set[String] = Set.empty,
                password: Option[String] = None,
                contest: Int,
                lang: Option[String] = None,
                files: mutable.Buffer[ImageWithRating] = mutable.Buffer.empty,
                createdAt: DateTime = DateTime.now,
                deletedAt: Option[DateTime] = None) {

  def emailLo = email.trim.toLowerCase

  def roundFiles(roundId: Long) = Gallery.userFiles(this, roundId)

  //    def withFiles(files: Seq[ImageWithRating]) = this.copy(files = files)

  //def roles = Seq("jury")

  def canViewOrgInfo(round: Round) =
    roles.intersect(Set("organizer", "admin")).nonEmpty || (roles.contains("jury") && round.juryOrgView)

}

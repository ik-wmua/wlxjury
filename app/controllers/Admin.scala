package controllers

import db.scalikejdbc.{ContestJuryJdbc, RoundJdbc, UserJdbc}
import org.intracer.wmua._
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.Messages.Implicits._
import play.api.i18n.{Lang, Messages}
import play.api.mvc.Results._
import play.api.mvc.{Controller, Result}

import scala.collection.immutable.ListMap
import scala.util.Try

object Admin extends Controller with Secured {

  val sendMail = new SendMail

  def users(contestIdParam: Option[Long] = None) = withAuth({
    user =>
      implicit request =>
        val usersView = for (contestId <- user.currentContest.orElse(contestIdParam);
                             contest <- ContestJuryJdbc.byId(contestId)) yield {
          val users = UserJdbc.findByContest(contestId)
          Ok(views.html.users(user, users, editUserForm.copy(data = Map("roles" -> "jury")), contest))
        }
        usersView.getOrElse(Redirect(routes.Login.index())) // TODO message
  }, User.ADMIN_ROLES)

  def havingEditRights(currentUser: User, otherUser: User)(block: => Result): Result = {
    if (!currentUser.canEdit(otherUser)) {
      Redirect(routes.Login.index()) // TODO message
    } else {
      block
    }
  }

  def editUser(id: Long) = withAuth({
    user =>
      implicit request =>

        val editedUser = UserJdbc.find(id).get

        havingEditRights(user, editedUser) {

          val filledForm = editUserForm.fill(editedUser)

          Ok(views.html.editUser(user, filledForm, RoundJdbc.current(user), user.currentContest))
        }
  }, Set(User.ADMIN_ROLE, User.ROOT_ROLE, s"USER_ID_$id"))

  def saveUser() = withAuth({
    user =>
      implicit request =>

        editUserForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(
              views.html.editUser(
                user,
                formWithErrors,
                RoundJdbc.current(user),
                contestId = Some(formWithErrors.data("contest").toLong)
              )
            ),
          formUser => {
            havingEditRights(user, formUser) {

              val userId = formUser.id.get
              val count = UserJdbc.countByEmail(userId, formUser.email)
              if (count > 0) {
                BadRequest(
                  views.html.editUser(
                    user,
                    editUserForm.fill(formUser).withError("email", "email should be unique"),
                    RoundJdbc.current(user),
                    contestId = formUser.contest
                  )
                )
              } else {
                if (userId == 0) {
                  createNewUser(user, formUser)
                } else {

                  // only admin can update roles
                  val newRoles = if (user.hasAnyRole(User.ADMIN_ROLES)) {
                    if (!user.hasRole(User.ROOT_ROLE) && formUser.roles.contains(User.ROOT_ROLE)) {
                      originalRoles(formUser)
                    } else {
                      formUser.roles
                    }
                  } else {
                    originalRoles(formUser)
                  }

                  UserJdbc.updateUser(userId, formUser.fullname, formUser.email, newRoles, formUser.lang)

                  for (password <- formUser.password) {
                    val hash = UserJdbc.hash(formUser, password)
                    UserJdbc.updateHash(userId, hash)
                  }
                }

                val result = Redirect(routes.Admin.users(formUser.contest))
                val lang = for (lang <- formUser.lang; if formUser.id == user.id) yield lang

                lang.fold(result)(l => result.withLang(Lang(l)))
              }
            }
          }
        )
  })

  def originalRoles(formUser: User): Set[String] = {
    val origUser = UserJdbc.find(formUser.id.get).get
    origUser.roles
  }

  def showImportUsers(contestIdParam: Option[Long]) = withAuth({
    user =>
      implicit request =>

        val contestId = contestIdParam.orElse(user.currentContest).get
        Ok(views.html.importUsers(user, importUsersForm, contestId))

  }, Set(User.ADMIN_ROLE, User.ROOT_ROLE))

  def importUsers(contestIdParam: Option[Long] = None) = withAuth({
    user =>
      implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        importUsersForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(views.html.importUsers(user, importUsersForm, contestId)),
          formUsers => {
            val contest = ContestJuryJdbc.byId(contestId)
            val parsed = User.parseList(formUsers)
              .map(_.copy(
                lang = user.lang,
                contest = Some(contestId)
              ))

            val results = parsed.map(pu => Try(createUser(user, pu, contest)))

            Redirect(routes.Admin.users(Some(contestId)))
          })

  }, Set(User.ADMIN_ROLE, User.ROOT_ROLE))

  def appConfig = play.Play.application.configuration

  def editGreeting(contestIdParam: Option[Long]) = withAuth({
    user =>
      implicit request =>

        val contestId = contestIdParam.orElse(user.currentContest).get
        val contest = ContestJuryJdbc.byId(contestId).get

        val greeting = getGreeting(contest)

        val recipient = new User(fullname = "Recipient Full Name", email = "Recipient email", id = None, contest = contest.id)
        Ok(views.html.greetingTemplate(
          user, greetingTemplateForm.fill(greeting), contestId, variables(contest, user, recipient)
        ))

  }, Set(User.ADMIN_ROLE, User.ROOT_ROLE))


  def getGreeting(contest: ContestJury): Greeting = {
    val defaultGreeting = appConfig.getString("wlxjury.greeting")

    contest.greeting.text.fold(contest.greeting.copy(text = Some(defaultGreeting)))(_ => contest.greeting)
  }

  def fillGreeting(template: String, contest: ContestJury, sender: User, user: User) = {
    variables(contest, sender, user).foldLeft(template) {
      case (s, (k, v)) =>
        s.replace(k, v)
    }
  }

  def variables(contest: ContestJury, sender: User, recipient: User): Map[String, String] = {
    val host = appConfig.getString("wlxjury.host")

    ListMap(
      "{{ContestType}}" -> contest.name,
      "{{ContestYear}}" -> contest.year.toString,
      "{{ContestCountry}}" -> contest.country,
      "{{ContestCountry}}" -> contest.country,
      "{{JuryToolLink}}" -> host,
      "{{AdminName}}" -> sender.fullname,
      "{{RecipientName}}" -> recipient.fullname,
      "{{Login}}" -> recipient.email,
      "{{Password}}" -> recipient.password.getOrElse("")
    )
  }

  def saveGreeting(contestIdParam: Option[Long] = None) = withAuth({
    user =>
      implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        greetingTemplateForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(views.html.importUsers(user, importUsersForm, contestId)),
          formGreeting => {

            ContestJuryJdbc.updateGreeting(contestId, formGreeting)
            Redirect(routes.Admin.users(Some(contestId)))
          })

  }, Set(User.ADMIN_ROLE, User.ROOT_ROLE))


  def createNewUser(user: User, formUser: User): Unit = {
    val contest: Option[ContestJury] = formUser.currentContest.flatMap(ContestJuryJdbc.byId)
    createUser(user, formUser, contest)
  }

  def createUser(creator: User, formUser: User, contestOpt: Option[ContestJury]) = {
    val password = UserJdbc.randomString(12)
    val hash = UserJdbc.hash(formUser, password)
    val createdUser = UserJdbc.create(formUser.fullname, formUser.email, hash, formUser.roles, formUser.contest, formUser.lang)

    contestOpt.foreach { contest =>
      if (contest.greeting.use) {
        sendMail(creator, createdUser, contest, password)
      }
    }
    createdUser
  }

  def sendMail(creator: User, recipient: User, contest: ContestJury, password: String): String = {
    val greeting = getGreeting(contest)
    val subject = Messages("welcome.subject", contest.name)
    val message = fillGreeting(greeting.text.get, contest, creator, recipient)
    sendMail.sendMail(
      fromName = creator.fullname,
      fromEmail =  creator.email,
      to = Seq(recipient.email),
      bcc = Seq(creator.email),
      subject = subject,
      message = message)
  }

  val editUserForm = Form(
    mapping(
      "id" -> longNumber,
      "fullname" -> nonEmptyText,
      "email" -> email,
      "password" -> optional(text),
      "roles" -> optional(text),
      "contest" -> optional(longNumber),
      "lang" -> optional(text)
    )(User.applyEdit)(User.unapplyEdit)
  )

  val importUsersForm = Form(
    single(
      "userstoimport" -> nonEmptyText
    )
  )

  val greetingTemplateForm = Form(
    mapping(
      "greetingtemplate" -> optional(text),
      "use" -> boolean
    )(Greeting.apply)(Greeting.unapply)
  )

  def resetPassword(id: Long) = withAuth({
    user =>
      implicit request =>
        val editedUser = UserJdbc.find(id).get

        val password = UserJdbc.randomString(8)
        val contest: Option[ContestJury] = editedUser.currentContest.flatMap(ContestJuryJdbc.byId)
        val contestName = contest.fold("")(_.name)
        val hash = UserJdbc.hash(editedUser, password)

        UserJdbc.updateHash(editedUser.id.get, hash)

        val juryhome = "http://localhost:9000"
        //        User.updateUser(formUser.fullname, formUser.email, hash, formUser.roles, formUser.contest)
        val subject: String = s"Password changed for $contestName jury"
        val message: String = s"Password changed for $contestName jury\n" +
          s" Please login to our jury tool $juryhome \nwith login: ${editedUser.email} and password: $password\n" +
          s"Regards, ${user.fullname}"
        // sendMail.sendMail(from = (user.fullname, user.email), to = Seq(user.email), bcc = Seq(user.email), subject = subject, message = message)

        Redirect(routes.Admin.editUser(id)).flashing("password-reset" -> s"Password reset. New Password sent to ${editedUser.email}")

  }, User.ADMIN_ROLES)


}

case class Greeting(text: Option[String], use: Boolean)

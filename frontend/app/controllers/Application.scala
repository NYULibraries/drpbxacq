package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.db.slick._
import play.api.Play.current


import com.dropbox.core._
import java.util.Locale
import java.security.MessageDigest

import models._

object Application extends Controller {

  val key = Play.current.configuration.getString("drpbx.key").get
  val secret = Play.current.configuration.getString("drpbx.secret").get
  val appInfo = new DbxAppInfo(key, secret)
  val dbConfig = new DbxRequestConfig("DLTS", Locale.getDefault().toString)
  val webAuth = new DbxWebAuthNoRedirect(dbConfig, appInfo)
  val url = webAuth.start

  def index = Action { Ok(views.html.login(loginForm)) }

  def create = Action {    
    Ok(views.html.create(keyForm, url))
  }

  def login = Action { request =>  
    val email = request.body.asFormUrlEncoded.get("email").head
    val hash = new String(MessageDigest.getInstance("MD5").digest(request.body.asFormUrlEncoded.get("password").head.getBytes))
    Redirect(routes.Application.validateLogin(email, hash))
  }

  def logout = Action { request =>
    Redirect(routes.Application.index).withNewSession
  }

  def validateLogin(email: String, hash: String) = DBAction { implicit rs =>
    Users.validateLogin(email, hash) match {
      case Some(user) => Redirect(routes.Application.user).withSession("email" -> user.email)
      case None => Redirect(routes.Application.index)
    }
  }

  def user = Action { request =>
    request.session.get("email") match {
      case Some(user) => Ok(views.html.home())
      case None => Redirect(routes.Application.index)
    }
  }

  def transfer = Action { request =>
    request.session.get("email") match {
      case Some(email) => {
        val user = DB.withSession{ implicit session => Users.findByEmail(email)}
        val client = new DbxClient(dbConfig, user.token)
        val listing = client.getMetadataWithChildren("/")
        Ok(views.html.user(listing.children, listing.entry))
      } 
      case None => Redirect(routes.Application.index)
    } 
  }

  def save = DBAction { implicit rs =>
    keyForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.create(keyForm, url)),
      user => {
        val md5 = new String(MessageDigest.getInstance("MD5").digest(user.passMd5.getBytes))
        val code = new String(new sun.misc.BASE64Encoder().encodeBuffer(md5.getBytes))
        val user2 = new User(user.id, user.email, code, webAuth.finish(user.token).accessToken)        
        Users.insert(user2)
        Redirect(routes.Application.user).withSession("token" -> user2.token)
      }
    )
  }

  /**
   *Form for key
   */
  
  val keyForm = Form(
    mapping(
      "id" -> optional(longNumber),
      "email" -> nonEmptyText,
      "password" -> nonEmptyText, 
      "token" -> nonEmptyText
    )(User.apply)(User.unapply)
  )

  val loginForm = Form(
    mapping(
      "email" -> nonEmptyText,
      "password" -> nonEmptyText
    )(Login.apply)(Login.unapply)
  )
}

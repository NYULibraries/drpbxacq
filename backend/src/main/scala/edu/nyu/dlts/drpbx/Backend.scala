package edu.nyu.dlts.drpbx

import _root_.akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import org.json4s.{ DefaultFormats, Formats }
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.json._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration._
import edu.nyu.dlts.drpbx.backend.domain.DrpbxDbSupport
import edu.nyu.dlts.drpbx.backend.domain.DBProtocol._

import org.apache.commons.codec.binary.Hex
import java.security.MessageDigest  
import java.util.UUID

import edu.nyu.dlts.drpbx.backend.serializers._



class Backend(system: ActorSystem ) extends DrpbxBackendStack with JacksonJsonSupport with FutureSupport with Serializers {
  
  val logger: Logger = LoggerFactory.getLogger("drpbx.rest")
  implicit val timeout = new Timeout(5 seconds)
  protected implicit def executor: ExecutionContext = system.dispatcher

  val adminActor = system.actorOf(Props[AdminActor], name = "admin")
  val dnrActor = system.actorOf(Props[DonorActor], name = "donor")
  val fileActor = system.actorOf(Props[FileActor], name = "file")
  val transferActor = system.actorOf(Props[TransferActor], name="transfer")
  val dlActor = system.actorOf(Props[DownloadActor], "downloadactor")

  //REST Interface
  before() {
    contentType = formats("json")
  }

  get("/") {
    "nothing here"
  }

  get("/admin") {
    Map("admin" -> true)
  }

  get("/admin/create") {
    adminActor ! Create
    Map("admin" -> Map("create" -> true))
  }

  get("/admin/drop") {
    adminActor ! Drop
    Map("admin" -> Map("drop" -> true))
  }

  get("/admin/purge") {
    adminActor ! Purge
    Map("admin" -> Map("purge" -> true))
  }

  get("/admin/insert") {
    val uuid = UUID.randomUUID
    val md5 = MessageDigest.getInstance("MD5").digest(params("password").getBytes)
    val md5Hex = new String(Hex.encodeHexString(md5))
    val admin = new Admin(uuid, params("email"), md5Hex)
    adminActor ! admin
    admin
  }

  get("/admin/login") {
    implicit val timeout = Timeout(5 seconds)
    val md5 = MessageDigest.getInstance("MD5").digest(params("password").getBytes)
    val md5Hex = new String(Hex.encodeHexString(md5))
    val login = new Login(params("email"), md5Hex)
    val future = adminActor ? login    
    val result = Await.result(future, timeout.duration).asInstanceOf[Option[Admin]]
    
    result match {
      case Some(a) => { Map("result" -> true, "name" -> a.name) }
      case None => Map("result" -> false)
    }
  }

  post("/donor/create") {
    val md5 = MessageDigest.getInstance("MD5").digest(params("password").getBytes)
    val md5Hex = new String(Hex.encodeHexString(md5))
    val donor = new Donor(UUID.randomUUID, params("email"), params("name"), params("org"), md5Hex, params("token"))
    dnrActor ! donor
    Map("result" -> true, "donor" -> donor.email)
  }

  get("/donor/login") {
    val md5 = MessageDigest.getInstance("MD5").digest(params("password").getBytes)
    val md5Hex = new String(Hex.encodeHexString(md5))
    val login = new Login(params("email"), md5Hex)
    val future = dnrActor ? login    
    val result = Await.result(future, timeout.duration).asInstanceOf[Option[Donor]]
    
    result match {
      case Some(donor) => { 
        logger.info("login validated: " + donor.email )
        Map("result" -> true, "id" -> donor.id.toString) 
      }
      case None => Map("result" -> false)
    }
  }

  get("/donor/:email/validate") {
    val email = new EmailReq(params("email"))
    val future = dnrActor ? email
    val result = Await.result(future, timeout.duration).asInstanceOf[Option[Donor]]

    result match {
      case Some(donor) => { 
        Map("result" -> true, "id" -> donor.id.toString)
      }
      case None => Map("result" -> false)
    }
  }

  get("/donor/:id/token") {
    val tokenReq = new TokenReq(UUID.fromString(params("id")))
    val future = dnrActor ? tokenReq
    val result = Await.result(future, timeout.duration).asInstanceOf[Option[String]] 

    result match {
      case Some(token) => Map("result" -> true, "token" -> token) 
      case None => Map("result" -> false)
    }
  }

  post("/transfer") {
    val xfer = parsedBody.extract[TransferReq]
    val future = transferActor ? xfer
    val result = Await.result(future, timeout.duration).asInstanceOf[TransferResponse] 
    result.result match {
      case true => Map("result" -> true, "count" -> result.count)
      case false => Map("result" -> false)
    }
  }

  get("/transfers") {
    val future = transferActor ? TransferAll
    val result = Await.result(future, timeout.duration)
    Map("result" -> true, "transfers" -> result)
  }

  get("/transfer/:id") {
    val future = transferActor ? new TransferId(UUID.fromString(params("id")))
    val result = Await.result(future, timeout.duration)
    result match {
      case Some(transfer) => {
        transfer
      }
      case None => Map("result" -> false)
    }
  }

  get("/transfer/:id/download") {
    val xid = new TransferId(UUID.fromString(params("id")))
    logger.info(s"transfer request $xid.id")
    val future = transferActor ? new TransferStatusUpdate(xid.id, 3)
    val result = Await.result(future, timeout.duration)
    dlActor ! xid
    result match {
      case true => { Map("result" -> true) }
      case false => { Map("result" -> false) }
    }
  }

  post("/transfer/approve") {
    val transfer = parsedBody.extract[TransferApproveReq]
    val future = transferActor ? transfer
    Await.result(future, timeout.duration)
  }

  post("/transfer/cancel"){
    val transfer = parsedBody.extract[TransferCancelReq]
    val future = transferActor ? transfer
    val result = Await.result(future, timeout.duration)
    result match {
      case Some(transfer) => {
        transfer
      }
      case None => Map("result" -> false)
    }
  }

  get("/donor/:id/transfers") {
    val transfer = new DonorTransfersReq(UUID.fromString(params("id")))
    val future = transferActor ? transfer  
    val result = Await.result(future, timeout.duration)
    result match {
      case Some(xfers) => Map("result" -> true, "transfers" -> xfers) 
      case None => Map("result" -> false) 
    } 
  }

  get("/file/:id/show") {
    val file = new FileReq(UUID.fromString(params("id")))
    val future = fileActor ? file
    Await.result(future, timeout.duration) match {
      case Some(file) => Map("result" -> true, "file" -> file) 
      case None => Map("result" -> false) 
    }
  }

  get("/file/:id/download") {
    val file = new FileDLReq(UUID.fromString(params("id")))
    val future = fileActor ? file
    Await.result(future, timeout.duration) match {
      case Some(map) => map
      case None => Map("result" -> false) 
    }
  }
}

class FileActor extends Actor with DrpbxDbSupport {
  val logger: Logger = LoggerFactory.getLogger("drpbx.fileactor")
  def receive = {
    case req: FileReq => {
      sender ! m.getFileById(req)
    }

    case req: FileDLReq => {
      val file = m.getFileById(new FileReq(req.id))
      file match {
        case Some(f) => {
          val xferId = UUID.fromString(f.xferId)
          val transReq = new TransReq(xferId)
          val donorId = m.getDonorId(transReq).get
          val tokenReq = new TokenReq(donorId)
          val token = m.getDonorToken(tokenReq)
          sender ! Some(Map("result" -> true, "file" -> f, "token" -> token.getOrElse(null)))
        }
        case None => sender ! Some(Map("result" -> false))
      }
    }
  }
}

//Actors
class AdminActor extends Actor with DrpbxDbSupport {
  val logger: Logger = LoggerFactory.getLogger("drpbx.adminActor")
  def receive = {
  	
    case Create => {
      logger.info("CREATE MESSAGE RECEIVED")
      m.createDB
      logger.info("DB CREATED")
    }

    case Drop => {
      logger.info("DROP MESSAGE RECEIVED")
      m.dropDB
      logger.info("DB DROPPED")
    }

    case Purge => {
      logger.info("PURGE MESSAGE RECIEVED")
      m.dropDB
      m.createDB
      logger.info("DB PURGED")
    }

    case admin: Admin => {
      logger.info("INSERT MESSAGE RECEIVED")
      m.insertAdmin(admin)
      logger.info("ADMIN INSERTED")
    }

    case login: Login => {
      logger.info("ADMIN LOGIN MESSAGE RECEIVED")
      sender ! m.loginAdmin(login)
    }
  }
}

class DownloadActor extends Actor with DrpbxDbSupport {
  val logger: Logger = LoggerFactory.getLogger("drpbx.dlActor")
  def receive = {
    case req: TransferId => {
      logger.info("DOWNLOADING FILES")
      m.downloadTransfer(req)
    }
  }
}

class TransferActor extends Actor with DrpbxDbSupport {
  val logger: Logger = LoggerFactory.getLogger("drpbx.transferActor")
  
  def receive = {
    case req: TransferApproveReq => {
      logger.info("TRANSFER APPROVE MESSAGE RECEIVED")
      m.approveTransferRequest(req) match {
        case true => {
          logger.info("SENDING TRUE RESULT")
          sender ! Map("result" -> true)
          
        }
        case false => sender ! Map("result" -> false)
      }

      /*
      val dlActor = context.actorOf(Props[DownloadActor], "downloadactor")
      dlActor ! new TransferId(UUID.fromString(req.transferId))
      */
    }

    case req: TransferCancelReq => {
      logger.info("TRANSFER CANCEL MESSAGE RECEIVED")
      m.cancelTransferRequest(req) match {
        case true => sender ! Some(Map("result" -> true))
        case false => sender ! None
      }
    }

    case TransferAll => {
      logger.info("ALL TRANSFERS REQUEST RECEIVED")
      sender ! m.getTransfers
    }

    case req: TransferId => {
      logger.info("TRANSFER INFO REQUEST RECEIVED\t" + req.id)
      val transfer = m.getTransferById(req)

      transfer match {
        case Some(xfer) => {
          val files = m.getFilesByTransId(req)
          logger.info("TRANSFER INFO REQUEST SERVED\t" + req.id)
          sender ! Some(Map("result" -> true, "transfer" -> xfer, "files" -> files, "donor" -> m.getDonorWeb(UUID.fromString(xfer.donorId))))
        }
        case None => {
          logger.error("TRANSFER NOT FOUND\t" + req.id)
          sender ! None
        }
      } 
    }

    case req: DonorTransfersReq => {
      logger.info("DONOR TRANSFER REQUEST RECEIVED")
      sender ! m.getDonorTransfers(req)
    }

    case req: TransferReq => {
      logger.info("TRANSFER REQUEST RECEIVED")
      sender ! m.insertTransfer(req)
    }

    case req: TransferStatusUpdate => {
      logger.info("TRANSFER STATUS UPDATE RECEIVED")
      sender ! m.updateTransferStatus(req)
    }
  }
}

class DonorActor extends Actor with DrpbxDbSupport {
  val logger: Logger = LoggerFactory.getLogger("drpbx.DonorActor")
  def receive = {
    case donor: Donor => {
      logger.info("DONOR INSERT MSG RECEIVED")
      m.insertDonor(donor)
      logger.info("DONOR INSERTED")
    }

    case login: Login => {
      logger.info("LOGIN MSG RECEIVED")
      sender ! m.loginDonor(login)
    }

    case req: EmailReq => {
      logger.info("Email Request Received")
      sender ! m.getDonor(req.email)
    }

    case req: TokenReq => {
      logger.info("TOKEN REQUEST RECEIVED")
      sender ! m.getDonorToken(req)
    }
  }
}
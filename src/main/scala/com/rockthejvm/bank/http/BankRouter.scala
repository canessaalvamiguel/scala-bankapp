package com.rockthejvm.bank.http

import akka.http.scaladsl.server.Directives._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import com.rockthejvm.bank.actors.PersistentBankAccount.{Command, Response}
import com.rockthejvm.bank.actors.PersistentBankAccount.Response._
import com.rockthejvm.bank.actors.PersistentBankAccount.Command._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class BankAccountCreationRequest(user: String, currency: String, balance: Double){
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo )
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

case class FailureResponse(reason: String)

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  /*
  POST /bank/
    Payload: bank account creation request as Json
    Response:
      201 Created
      Location: /bank/uuid

   GET /bank/uuid
       Response:
         200 OK
         JSON repr of bank account details
         404 Not found

    PUT /bank/uuid
       Payload: (currency, amount) as JSON
       Response:
         1)  200 OK
             Payload: new bank details as JSON
         2)  404 Not found if something wrong
   */
  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[BankAccountCreationRequest]) { request =>
            /*
            - convert the request into a Command for the bank actor
            - send the command to the bank
            - expect a reply
            - send back an HTTP response
             */
            onSuccess(createBankAccount(request)) {
              case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
            }
          }
        }
      } ~
        path(Segment) {
          id =>
            /*
            - send command to the bank
            - expect a reply
            - send back the HTTP response
             */
            get {
              onSuccess(getBankAccount(id)) {
                case GetBankAccountResponse(Some(account)) =>
                  complete(account) // 200 OK
                case GetBankAccountResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found."))
              }
            } ~
            put {
              entity(as[BankAccountUpdateRequest]) { request =>
                // validation
               /* validateRequest(request)*/ {
                  /*
                - transform the request to a Command
                - send the command to the bank
                - expect a reply
                - send HTTP response
               */
                  onSuccess(updateBankAccount(id, request)) {
                    case BankAccountBalanceUpdatedResponse(Some(account)) =>
                      complete(account)
                    case BankAccountBalanceUpdatedResponse(None) =>
                      complete(StatusCodes.BadRequest, FailureResponse(s"Something went wrong"))
                  }
                }
              }
            }
        }
    }
}

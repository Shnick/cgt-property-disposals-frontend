/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers

import java.util.UUID

import cats.data.EitherT
import cats.instances.future._
import cats.instances.uuid._
import cats.syntax.eq._
import shapeless.{Lens, lens}
import com.google.inject.{Inject, Singleton}
import play.api.mvc.Results.SeeOther
import play.api.mvc.{Action, AnyContent, Call, MessagesControllerComponents, Result}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.config.{ErrorHandler, ViewConfig}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.actions.{AuthenticatedAction, RequestWithSessionData, RequestWithSessionDataAndRetrievedData, SessionDataAction, WithAuthAndSessionDataAction}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.SubscriptionStatus.{InsufficientConfidenceLevel, SubscriptionComplete, SubscriptionMissingData, SubscriptionReady}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.UserType.Individual
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.repos.SessionStore
import uk.gov.hmrc.cgtpropertydisposalsfrontend.services.EmailVerificationService
import uk.gov.hmrc.cgtpropertydisposalsfrontend.services.EmailVerificationService.EmailVerificationResponse._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.util.Logging._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.util.{Logging, toFuture}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.views
import uk.gov.hmrc.play.bootstrap.controller.FrontendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EmailController @Inject()(
                                 val authenticatedAction: AuthenticatedAction,
                                 val sessionDataAction: SessionDataAction,
                                 sessionStore: SessionStore,
                                 emailVerificationService: EmailVerificationService,
                                 uuidGenerator: UUIDGenerator,
                                 errorHandler: ErrorHandler,
                                 cc: MessagesControllerComponents,
                                 enterEmailPage: views.html.subscription.enter_email,
                                 checkYourInboxPage: views.html.subscription.check_your_inbox,
                                 emailVerifiedPage: views.html.subscription.email_verified
                               )(implicit viewConfig: ViewConfig, ec: ExecutionContext)
  extends FrontendController(cc)
    with WithAuthAndSessionDataAction
    with Logging
    with SessionUpdates
    with DefaultRedirects {

  def withSubscriptionData(requestWithSessionData: RequestWithSessionData[_])(
    f: (SessionData, Either[SubscriptionMissingData, SubscriptionReady]) => Future[Result]
  ): Future[Result] =
    (requestWithSessionData.sessionData,
      requestWithSessionData.sessionData.flatMap(_.subscriptionStatus)
    ) match {
      case (Some(d), Some(s: SubscriptionMissingData))  => f(d, Left(s))
      case (Some(d), Some(s: SubscriptionReady))        => f(d, Right(s))
      case (_, s)                                       => defaultRedirect(s)
    }

  def changeEmail(): Action[AnyContent] = enterOrChangeEmail(Some(routes.SubscriptionController.checkYourDetails))

  def enterEmail(): Action[AnyContent] = enterOrChangeEmail(None)

  private def enterOrChangeEmail(backLink: Option[Call]): Action[AnyContent] =
    authenticatedActionWithSessionData.async { implicit request =>
      withSubscriptionData(request) {
        case (sessionData, subscriptionStatus) =>
          val form = sessionData.emailToBeVerified.fold(Email.form)(e => Email.form.fill(e.email))
          Ok(enterEmailPage(form, subscriptionStatus.isRight, backLink))
      }
    }

  def changeEmailSubmit(): Action[AnyContent] = enterOrChangeEmailSubmit(Some(routes.SubscriptionController.checkYourDetails))

  def enterEmailSubmit(): Action[AnyContent] = enterOrChangeEmailSubmit(None)

  private def enterOrChangeEmailSubmit(backLink: Option[Call]): Action[AnyContent] =
    authenticatedActionWithSessionData.async { implicit request =>
      withSubscriptionData(request) {
        case (sessionData, subscriptionStatus) =>
          Email.form
            .bindFromRequest()
            .fold(
              formWithErrors => BadRequest(enterEmailPage(formWithErrors, subscriptionStatus.isRight, backLink)), { email =>
                val emailToBeVerified = sessionData.emailToBeVerified match {
                  case Some(e) if e.email === email => e
                  case _                            => EmailToBeVerified(email, uuidGenerator.nextId(), verified = false)
                }

                val name =
                  subscriptionStatus.fold(
                    s => s.name,
                    s => Name(s.subscriptionDetails.forename, s.subscriptionDetails.surname)
                  )

                val result = for {
                  _ <- EitherT(
                    updateSession(sessionStore, request)(_.copy(emailToBeVerified = Some(emailToBeVerified))))
                  result <- emailVerificationService.verifyEmail(email, emailToBeVerified.id, name)
                } yield result

                result.value.map {
                  case Left(e) =>
                    logger.warn("Could not verify email", e)
                    errorHandler.errorResult()

                  case Right(EmailAlreadyVerified) =>
                    SeeOther(
                      routes.EmailController.verifyEmail(emailToBeVerified.id).url
                    )

                  case Right(EmailVerificationRequested) =>
                    SeeOther(routes.EmailController.checkYourInbox().url)
                }
              }
            )
      }
    }

  def checkYourInbox(): Action[AnyContent] =
    authenticatedActionWithSessionData.async { implicit request =>
      withSubscriptionData(request) {
        case (sessionData, subscriptionStatus) =>
          val backLink = subscriptionStatus.fold(
            _ => routes.EmailController.enterEmail(),
            _ => routes.EmailController.changeEmail()
          )
          sessionData.emailToBeVerified.fold(
            SeeOther(routes.SubscriptionController.checkYourDetails().url)
          )(emailToBeVerified => Ok(checkYourInboxPage(emailToBeVerified.email, backLink)))
      }
    }

  def verifyEmail(p: UUID): Action[AnyContent] =
    authenticatedActionWithSessionData.async { implicit request =>
      withSubscriptionData(request) {
        case (sessionData, subscriptionStatus) =>
          sessionData.emailToBeVerified.fold[Future[Result]](
            SeeOther(routes.SubscriptionController.checkYourDetails().url)
          ) { emailToBeVerified =>
            if (emailToBeVerified.id =!= p) {
              logger.warn(
                s"Received verify email request where id sent ($p) did not match the id in session (${emailToBeVerified.id})")
              errorHandler.errorResult()
            } else {
              if (emailToBeVerified.verified) {
                SeeOther(routes.EmailController.emailVerified().url)
              } else {
                updateSession(sessionStore, request){
                  s =>
                    val email = emailToBeVerified.email.value
                    val updatedSubscriptionStatus =
                      subscriptionStatus.fold[SubscriptionStatus](
                        subscriptionMissingDataEmailLens.set(_)(Some(email)),
                        subscriptionReadyEmailLens.set(_)(email)
                      )
                    s.copy(
                      subscriptionStatus = Some(updatedSubscriptionStatus),
                      emailToBeVerified = Some(emailToBeVerified.copy(verified = true))
                    )
                }.map(
                  _.fold(
                    { e =>
                      logger.warn("Could not store email verified result", e)
                      errorHandler.errorResult()
                    }, { _ =>
                      SeeOther(routes.EmailController.emailVerified().url)
                    }
                  )
                )
              }
            }
          }
      }
    }



  def emailVerified(): Action[AnyContent] =
    authenticatedActionWithSessionData.async { implicit request =>
      withSubscriptionData(request) {
        case (sessionData, subscriptionStatus) =>
          sessionData.emailToBeVerified.fold(
            SeeOther(routes.SubscriptionController.checkYourDetails().url)
          ) { emailToBeVerified =>
            if (emailToBeVerified.verified) {
              val continueCall = subscriptionStatus.fold(
                _ => routes.StartController.start(),
                _ => routes.SubscriptionController.checkYourDetails()
              )

              Ok(emailVerifiedPage(emailToBeVerified.email, continueCall))
            } else {
              logger.warn(
                "Email verified endpoint called but email was not verified"
              )
              errorHandler.errorResult()
            }
          }
      }
    }

  val subscriptionMissingDataEmailLens: Lens[SubscriptionMissingData, Option[String]] =
    lens[SubscriptionMissingData].businessPartnerRecord.emailAddress

  val subscriptionReadyEmailLens: Lens[SubscriptionReady, String] =
    lens[SubscriptionReady].subscriptionDetails.emailAddress


}

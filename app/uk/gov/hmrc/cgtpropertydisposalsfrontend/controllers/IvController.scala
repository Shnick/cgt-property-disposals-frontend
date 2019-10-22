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

import cats.instances.future._
import cats.syntax.eq._
import com.google.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.config.{ErrorHandler, ViewConfig}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.actions.{AuthenticatedAction, SessionDataAction, WithAuthAndSessionDataAction}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.SessionData
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.iv.IvErrorStatus
import uk.gov.hmrc.cgtpropertydisposalsfrontend.repos.SessionStore
import uk.gov.hmrc.cgtpropertydisposalsfrontend.services.IvService
import uk.gov.hmrc.cgtpropertydisposalsfrontend.util.Logging._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.util.{Logging, toFuture}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.views
import uk.gov.hmrc.play.bootstrap.controller.FrontendController

import scala.concurrent.ExecutionContext

@Singleton
class IvController @Inject()(
  sessionStore: SessionStore,
  val authenticatedAction: AuthenticatedAction,
  val sessionDataAction: SessionDataAction,
  val errorHandler: ErrorHandler,
  val config: Configuration,
  ivService: IvService,
  cc: MessagesControllerComponents,
  failedIvPage: views.html.iv.failed_iv,
  failedMatchingPage: views.html.iv.failed_matching,
  insufficientEvidencePage: views.html.iv.insufficient_evidence,
  lockedOutPage: views.html.iv.locked_out,
  preconditionFailedPage: views.html.iv.precondition_failed,
  technicalIssuesPage: views.html.iv.technical_iv_issues,
  timeoutPage: views.html.iv.time_out,
  userAbortedPage: views.html.iv.user_aborted
)(implicit ec: ExecutionContext, viewConfig: ViewConfig)
    extends FrontendController(cc)
    with WithAuthAndSessionDataAction
    with Logging
    with SessionUpdates
    with IvBehaviour {

  def ivSuccessCallback(): Action[AnyContent] = authenticatedActionWithSessionData.async { implicit request =>
    if (request.sessionData.forall(_ === SessionData.empty)) {
      SeeOther(routes.StartController.start().url)
    } else {
      updateSession(sessionStore, request)(_ => SessionData.empty).map {
        case Left(e) =>
          logger.warn("Could not clear session after IV success", e)
          errorHandler.errorResult()

        case Right(_) =>
          SeeOther(routes.StartController.start().url)
      }
    }
  }

  def retry():  Action[AnyContent] = authenticatedActionWithSessionData.async {
    implicit request => redirectToIv
  }

  def ivFailureCallback(journeyId: UUID): Action[AnyContent] = authenticatedActionWithSessionData.async {
    implicit request =>
      ivService
        .getFailedJourneyStatus(journeyId)
        .fold(
          { e =>
            logger.warn("Could not check IV journey error status", e)
            Redirect(routes.IvController.getTechnicalIssue())
          }, {
            case IvErrorStatus.Incomplete           => Redirect(routes.IvController.getTechnicalIssue())
            case IvErrorStatus.FailedMatching       => Redirect(routes.IvController.getFailedMatching())
            case IvErrorStatus.FailedIV             => Redirect(routes.IvController.getFailedIV())
            case IvErrorStatus.InsufficientEvidence => Redirect(routes.IvController.getInsufficientEvidence())
            case IvErrorStatus.LockedOut            => Redirect(routes.IvController.getLockedOut())
            case IvErrorStatus.UserAborted          => Redirect(routes.IvController.getUserAborted())
            case IvErrorStatus.Timeout              => Redirect(routes.IvController.getTimedOut())
            case IvErrorStatus.TechnicalIssue       => Redirect(routes.IvController.getTechnicalIssue())
            case IvErrorStatus.PreconditionFailed   => Redirect(routes.IvController.getPreconditionFailed())
            case IvErrorStatus.Unknown(value) =>
              logger.warn(s"Received unknown error response status from IV: $value")
              Redirect(routes.IvController.getTechnicalIssue())
          }
        )
  }

  def getFailedMatching: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(failedMatchingPage())
  }

  def getFailedIV: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(failedIvPage())
  }

  def getInsufficientEvidence: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(insufficientEvidencePage())
  }

  def getLockedOut: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(lockedOutPage())
  }

  def getUserAborted: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(userAbortedPage())
  }

  def getTimedOut: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(timeoutPage())
  }

  def getTechnicalIssue: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(technicalIssuesPage())
  }
  def getPreconditionFailed: Action[AnyContent] = authenticatedActionWithSessionData { implicit r ⇒
    Ok(preconditionFailedPage())
  }

}

/*
 * Copyright 2020 HM Revenue & Customs
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

/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.returns

import java.time.LocalDate

import org.jsoup.Jsoup.parse
import org.jsoup.nodes.Document
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.i18n.MessagesApi
import play.api.inject.bind
import play.api.inject.guice.GuiceableModule
import play.api.mvc.{Call, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.onboarding.RedirectToStartBehaviour
import uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.{AuthSupport, ControllerSpec, SessionSupport}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.Generators._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.JourneyStatus.FillingOutReturn
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.{LocalDateUtils, SessionData}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.address.Address.UkAddress
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.address.Country
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.finance.AmountInPence
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.AcquisitionDetailsAnswers.{CompleteAcquisitionDetailsAnswers, IncompleteAcquisitionDetailsAnswers}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.DisposalDetailsAnswers.{CompleteDisposalDetailsAnswers, IncompleteDisposalDetailsAnswers}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.{AcquisitionDate, AssetType, SingleDisposalDraftReturn, Source}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.ExemptionAndLossesAnswers.{CompleteExemptionAndLossesAnswers, IncompleteExemptionAndLossesAnswers}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.SingleDisposalTriageAnswers.{CompleteSingleDisposalTriageAnswers, IncompleteSingleDisposalTriageAnswers}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.ReliefDetailsAnswers.{CompleteReliefDetailsAnswers, IncompleteReliefDetailsAnswers}
import uk.gov.hmrc.cgtpropertydisposalsfrontend.models.returns.YearToDateLiabilityAnswers.CalculatedYearToDateLiabilityAnswers._
import uk.gov.hmrc.cgtpropertydisposalsfrontend.repos.SessionStore
import uk.gov.hmrc.cgtpropertydisposalsfrontend.views.returns.TaskListStatus

import scala.concurrent.Future

class TaskListControllerSpec
    extends ControllerSpec
    with AuthSupport
    with SessionSupport
    with ScalaCheckDrivenPropertyChecks
    with RedirectToStartBehaviour {

  override val overrideBindings =
    List[GuiceableModule](
      bind[AuthConnector].toInstance(mockAuthConnector),
      bind[SessionStore].toInstance(mockSessionStore)
    )

  lazy val controller = instanceOf[TaskListController]

  implicit lazy val messagesApi: MessagesApi = controller.messagesApi

  "TaskListController" when {

    "handling requests to display the task list page" must {

      def performAction(): Future[Result] = controller.taskList()(FakeRequest())

      behave like redirectToStartWhenInvalidJourney(
        performAction, {
          case _: FillingOutReturn => true
          case _                   => false
        }
      )

      def testStateOfSection(draftReturn: SingleDisposalDraftReturn)(
        sectionLinkId: String,
        sectionLinkText: String,
        sectionLinkHref: Call,
        sectionsStatus: TaskListStatus,
        extraChecks: Document => Unit = _ => ()
      ): Unit = {
        val fillingOutReturn = sample[FillingOutReturn].copy(draftReturn = draftReturn)

        inSequence {
          mockAuthWithNoRetrievals()
          mockGetSession(
            SessionData.empty.copy(
              journeyStatus = Some(fillingOutReturn)
            )
          )
        }

        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("service.title"), { doc =>
            sectionsStatus match {
              case TaskListStatus.CannotStart =>
                doc.select(s"li#$sectionLinkId > span").text shouldBe sectionLinkText

              case _ =>
                doc.select(s"li#$sectionLinkId > a").text         shouldBe sectionLinkText
                doc.select(s"li#$sectionLinkId > a").attr("href") shouldBe sectionLinkHref.url
            }

            doc.select(s"li#$sectionLinkId > strong").text shouldBe messageFromMessageKey(s"task-list.$sectionsStatus")
            extraChecks(doc)
          }
        )
      }

      def testSectionNonExistent(draftReturn: SingleDisposalDraftReturn)(
        sectionLinkId: String
      ): Unit = {
        val fillingOutReturn = sample[FillingOutReturn].copy(draftReturn = draftReturn)

        inSequence {
          mockAuthWithNoRetrievals()
          mockGetSession(
            SessionData.empty.copy(
              journeyStatus = Some(fillingOutReturn)
            )
          )
        }

        checkPageIsDisplayed(
          performAction(),
          messageFromMessageKey("service.title"), { doc =>
            doc.select(s"li#$sectionLinkId > span").isEmpty shouldBe true
          }
        )
      }

      "display the page with the proper triage section status" when {

        "the session data indicates that they are filling in a return and the triage section is incomplete" in {
          val incompleteTriage =
            sample[SingleDisposalDraftReturn].copy(triageAnswers = sample[IncompleteSingleDisposalTriageAnswers])
          testStateOfSection(
            incompleteTriage
          )(
            "canTheyUseOurService",
            messageFromMessageKey("task-list.triage.link"),
            triage.routes.SingleDisposalsTriageController.checkYourAnswers(),
            TaskListStatus.InProgress,
            _.select("div.notice").contains(messageFromMessageKey("task-list.incompleteTriage"))
          )

        }

        "the session data indicates Enter initial gain or loss" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(
                triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                  .copy(assetType = AssetType.Residential)
                  .copy(countryOfResidence = Country("TR", Some("Turkey"))),
                disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers]),
                acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers])
                  .map(answers => answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2010, 10, 1))))
              )
              .copy(initialGainOrLoss = None)
          )(
            "initialGainOrLoss",
            messageFromMessageKey("task-list.enter-initial-gain-or-loss.link"),
            initialgainorloss.routes.InitialGainOrLossController.enterInitialGainOrLoss(),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates Submit initial gain or loss " in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(
                triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                  .copy(assetType = AssetType.Residential)
                  .copy(countryOfResidence = Country("TR", Some("Turkey"))),
                disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers]),
                acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers])
                  .map(answers => answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2010, 10, 1))))
              )
              .copy(initialGainOrLoss = Some(AmountInPence(0)))
          )(
            "initialGainOrLoss",
            messageFromMessageKey("task-list.enter-initial-gain-or-loss.link"),
            initialgainorloss.routes.InitialGainOrLossController.enterInitialGainOrLoss(),
            TaskListStatus.Complete
          )
        }

        "the session data indicates that they are filling in a return and the triage section is complete" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn].copy(triageAnswers = sample[CompleteSingleDisposalTriageAnswers])
          )(
            "canTheyUseOurService",
            messageFromMessageKey("task-list.triage.link"),
            triage.routes.SingleDisposalsTriageController.checkYourAnswers(),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper Enter property address section status" when {

        "the session data indicates that they are filling in a return and enter property address is todo" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(triageAnswers = sample[CompleteSingleDisposalTriageAnswers], propertyAddress = None)
          )(
            "propertyAddress",
            messageFromMessageKey("task-list.enter-property-address.link"),
            address.routes.PropertyDetailsController.checkYourAnswers(),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that they are filling in a return and enter property address is complete" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers   = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress = Some(sample[UkAddress])
            )
          )(
            "propertyAddress",
            messageFromMessageKey("task-list.enter-property-address.link"),
            address.routes.PropertyDetailsController.checkYourAnswers(),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper disposal details section status" when {

        "the session data indicates that they are filling in a return and the section has not been started yet is todo" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(triageAnswers = sample[CompleteSingleDisposalTriageAnswers], disposalDetailsAnswers = None)
          )(
            "disposalDetails",
            messageFromMessageKey("task-list.disposals-details.link"),
            disposaldetails.routes.DisposalDetailsController.checkYourAnswers(),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that they are filling in a return and they have started the section but not complete it yet" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(
                triageAnswers          = sample[CompleteSingleDisposalTriageAnswers],
                disposalDetailsAnswers = Some(sample[IncompleteDisposalDetailsAnswers])
              )
          )(
            "disposalDetails",
            messageFromMessageKey("task-list.disposals-details.link"),
            disposaldetails.routes.DisposalDetailsController.checkYourAnswers(),
            TaskListStatus.InProgress
          )
        }

        "the session data indicates that they are filling in a return and they have completed the section" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers          = sample[CompleteSingleDisposalTriageAnswers],
              disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers])
            )
          )(
            "disposalDetails",
            messageFromMessageKey("task-list.disposals-details.link"),
            disposaldetails.routes.DisposalDetailsController.checkYourAnswers(),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper acquisition details section status" when {

        "the session data indicates that they are filling in a return and the section has not been started yet is todo" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(triageAnswers = sample[CompleteSingleDisposalTriageAnswers], acquisitionDetailsAnswers = None)
          )(
            "acquisitionDetails",
            messageFromMessageKey("task-list.acquisition-details.link"),
            acquisitiondetails.routes.AcquisitionDetailsController.checkYourAnswers(),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that they are filling in a return and they have started the section but not complete it yet" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(
                triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
                acquisitionDetailsAnswers = Some(sample[IncompleteAcquisitionDetailsAnswers])
              )
          )(
            "acquisitionDetails",
            messageFromMessageKey("task-list.acquisition-details.link"),
            acquisitiondetails.routes.AcquisitionDetailsController.checkYourAnswers(),
            TaskListStatus.InProgress
          )
        }

        "the session data indicates that they are filling in a return and they have completed the section" in {
          testStateOfSection(
            sample[SingleDisposalDraftReturn]
              .copy(
                triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
                acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers])
              )
          )(
            "acquisitionDetails",
            messageFromMessageKey("task-list.acquisition-details.link"),
            acquisitiondetails.routes.AcquisitionDetailsController.checkYourAnswers(),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper reliefs details section status" when {

        def test(draftReturn: SingleDisposalDraftReturn, expectedStatus: TaskListStatus) =
          testStateOfSection(draftReturn)(
            "reliefDetails",
            messageFromMessageKey("task-list.relief-details.link"),
            reliefdetails.routes.ReliefDetailsController.checkYourAnswers(),
            expectedStatus
          )

        "the session data indicates that the property address has not been entered in" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = None,
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = None,
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.CannotStart
          )
        }

        "the session data indicates that the disposal details section is not yet complete" in {
          List(None, Some(sample[IncompleteDisposalDetailsAnswers])).foreach { disposalDetailsState =>
            test(
              sample[SingleDisposalDraftReturn].copy(
                triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
                propertyAddress           = Some(sample[UkAddress]),
                disposalDetailsAnswers    = disposalDetailsState,
                acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
                reliefDetailsAnswers      = None,
                exemptionAndLossesAnswers = None
              ),
              TaskListStatus.CannotStart
            )
          }
        }

        "the session data indicates that the acuquisition details section is not yet complete" in {
          List(None, Some(sample[IncompleteAcquisitionDetailsAnswers])).foreach { acquisitionDetailsAnswers =>
            test(
              sample[SingleDisposalDraftReturn].copy(
                triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
                propertyAddress           = Some(sample[UkAddress]),
                disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
                acquisitionDetailsAnswers = acquisitionDetailsAnswers,
                reliefDetailsAnswers      = None,
                exemptionAndLossesAnswers = None
              ),
              TaskListStatus.CannotStart
            )
          }
        }

        "the property address, disposal details and acquisition details section have all " +
          "been completed and the reliefs section has not been started yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = None,
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.ToDo
          )
        }

        "the property address, disposal details and acquisition details section have all " +
          "been completed and the reliefs section has been started but not completed yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[IncompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.InProgress
          )
        }

        "the session data indicates that they are filling in a return and they have completed the section" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper exemptions and losses section status" when {

        def test(draftReturn: SingleDisposalDraftReturn, expectedStatus: TaskListStatus) =
          testStateOfSection(draftReturn)(
            "exemptionsAndLosses",
            messageFromMessageKey("task-list.exemptions-and-losses.link"),
            exemptionandlosses.routes.ExemptionAndLossesController.checkYourAnswers(),
            expectedStatus
          )

        "the session data indicates that the reliefs section is has not yet been started" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = None,
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.CannotStart
          )
        }

        "the session data indicates that the reliefs section is has not yet been completed" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[IncompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.CannotStart
          )
        }

        "the reliefs section has been completed and the section has not been started yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = None
            ),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that they are filling in a return and they have started the section but not complete it yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = Some(sample[IncompleteExemptionAndLossesAnswers])
            ),
            TaskListStatus.InProgress
          )
        }

        "the session data indicates that they are filling in a return and they have completed the section" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers             = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress           = Some(sample[UkAddress]),
              disposalDetailsAnswers    = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers      = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers = Some(sample[CompleteExemptionAndLossesAnswers])
            ),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the proper year to date liability section status" when {

        def test(draftReturn: SingleDisposalDraftReturn, expectedStatus: TaskListStatus) =
          testStateOfSection(draftReturn)(
            "enterCgtLiability",
            messageFromMessageKey("task-list.enter-cgt-liability.link"),
            yeartodatelliability.routes.YearToDateLiabilityController.checkYourAnswers(),
            expectedStatus
          )

        "the session data indicates that the exemptions and losses section has not yet been started" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers              = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress            = Some(sample[UkAddress]),
              disposalDetailsAnswers     = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers  = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers       = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers  = None,
              yearToDateLiabilityAnswers = None
            ),
            TaskListStatus.CannotStart
          )
        }

        "the session data indicates that the reliefs section is has not yet been completed" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers              = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress            = Some(sample[UkAddress]),
              disposalDetailsAnswers     = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers  = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers       = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers  = Some(sample[IncompleteExemptionAndLossesAnswers]),
              yearToDateLiabilityAnswers = None
            ),
            TaskListStatus.CannotStart
          )
        }

        "the reliefs section has been completed and the section has not been started yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers              = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress            = Some(sample[UkAddress]),
              disposalDetailsAnswers     = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers  = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers       = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers  = Some(sample[CompleteExemptionAndLossesAnswers]),
              yearToDateLiabilityAnswers = None
            ),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that they are filling in a return and they have started the section but not complete it yet" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers              = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress            = Some(sample[UkAddress]),
              disposalDetailsAnswers     = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers  = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers       = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers  = Some(sample[CompleteExemptionAndLossesAnswers]),
              yearToDateLiabilityAnswers = Some(sample[IncompleteCalculatedYearToDateLiabilityAnswers])
            ),
            TaskListStatus.InProgress
          )
        }

        "the session data indicates that they are filling in a return and they have completed the section" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers              = sample[CompleteSingleDisposalTriageAnswers],
              propertyAddress            = Some(sample[UkAddress]),
              disposalDetailsAnswers     = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers  = Some(sample[CompleteAcquisitionDetailsAnswers]),
              reliefDetailsAnswers       = Some(sample[CompleteReliefDetailsAnswers]),
              exemptionAndLossesAnswers  = Some(sample[CompleteExemptionAndLossesAnswers]),
              yearToDateLiabilityAnswers = Some(sample[CompleteCalculatedYearToDateLiabilityAnswers])
            ),
            TaskListStatus.Complete
          )
        }
      }

      "display the page with the PROPER initial gains and losses section STATUS" when {

        def test(draftReturn: SingleDisposalDraftReturn, expectedStatus: TaskListStatus) =
          testStateOfSection(draftReturn)(
            "initialGainOrLoss",
            messageFromMessageKey("task-list.enter-initial-gain-or-loss.link"),
            initialgainorloss.routes.InitialGainOrLossController.enterInitialGainOrLoss(),
            expectedStatus
          )

        "the session data indicates that the country of residence is United Kingdom" in {

          testSectionNonExistent(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                .copy(assetType = AssetType.Residential, countryOfResidence = Country("GB", Some("United Kingdom"))),
              reliefDetailsAnswers = Some(sample[IncompleteReliefDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]).map(answers =>
                answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2014, 10, 1)))
              )
            )
          )(
            "initialGainOrLoss"
          )
        }

        "the session data indicates that the country of residence is NOT United Kingdom and RESIDENTIAL property was bought BEFORE 01/04/2015" in {
          test(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                .copy(assetType = AssetType.Residential)
                .copy(countryOfResidence = Country("TR", Some("Turkey"))),
              disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]).map(answers =>
                answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2014, 10, 1)))
              ),
              initialGainOrLoss = None
            ),
            TaskListStatus.ToDo
          )
        }

        "the session data indicates that the country of residence is NOT United Kingdom and NON-RESIDENTIAL property was bought" in {
          testSectionNonExistent(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                .copy(assetType = AssetType.NonResidential)
                .copy(countryOfResidence = Country("TR", Some("Turkey"))),
              disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]).map(answers =>
                answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2014, 10, 1)))
              ),
              initialGainOrLoss = None
            )
          )("initialGainOrLoss")
        }

        "the session data indicates that the country of residence is NOT United Kingdom but acquisition date is AFTER 01/04/2015 for RESIDANTAL property" in {
          testSectionNonExistent(
            sample[SingleDisposalDraftReturn].copy(
              triageAnswers = sample[CompleteSingleDisposalTriageAnswers]
                .copy(assetType = AssetType.Residential)
                .copy(countryOfResidence = Country("TR", Some("Turkey"))),
              disposalDetailsAnswers = Some(sample[CompleteDisposalDetailsAnswers]),
              acquisitionDetailsAnswers = Some(sample[CompleteAcquisitionDetailsAnswers]).map(answers =>
                answers.copy(acquisitionDate = AcquisitionDate(LocalDate.of(2020, 10, 1)))
              )
            )
          )("initialGainOrLoss")
        }
      }

      "display the page with Save and come back later link" when {
        "the session data indicates that they are filling in a return" in {
          inSequence {
            mockAuthWithNoRetrievals()
            mockGetSession(
              SessionData.empty.copy(
                journeyStatus = Some(sample[FillingOutReturn].copy(draftReturn = sample[SingleDisposalDraftReturn]))
              )
            )
          }

          val result = performAction()
          status(result) shouldBe OK

          val doc: Document = parse(contentAsString(result))
          doc.select("h1").text shouldBe messageFromMessageKey("service.title")
          doc
            .select("a#saveAndComeBackLater")
            .attr("href") shouldBe uk.gov.hmrc.cgtpropertydisposalsfrontend.controllers.returns.routes.ConfirmDraftReturnController
            .confirmDraftReturn()
            .url
        }
      }
    }
  }
}

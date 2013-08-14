package com.lucidchart.open.nark.controllers

import com.lucidchart.open.nark.request.{AppFlash, AppAction, AuthAction}
import java.util.UUID
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._

object SubscriptionsController extends AppController {
	private case class SubscribeFormSubmission(
		ignored: String
	)

	private val subscribeForm = Form(
		mapping(
			"ignored" -> text
		)(SubscribeFormSubmission.apply)(SubscribeFormSubmission.unapply)
	)

	/**
	 * Subscribe to an alert
	 * @param id the id of the alert to subscribe to
	 */
	def subscribe(id: UUID) = AuthAction.authenticatedUser { implicit user =>
		AppAction { implicit request =>
			subscribeForm.bindFromRequest().fold(
				formWithErrors => {
					Redirect(routes.AlertsController.view(id)).flashing(AppFlash.error("Unable to subscribe to this alert."))
				},
				data => {
					Redirect(routes.AlertsController.view(id)).flashing(AppFlash.success("Successfully subscribed to this alert."))
				}
			)
		}
	}
}
package com.lucidchart.open.nark.controllers

import com.lucidchart.open.nark.request.{AppAction, AuthAction}
import com.lucidchart.open.nark.models.{DynamicAlertModel, DynamicAlertTagModel, DynamicAlertTagSubscriptionModel, TagConverter}
import com.lucidchart.open.nark.models.records.{DynamicAlert, TagMap, Pagination}

import com.lucidchart.open.nark.views
import play.api.libs.json.Json

object DynamicAlertTagsController extends DynamicAlertTagsController
class DynamicAlertTagsController extends AppController {
	
	/*
	 * Get tag and all the dynamic alerts it is associated with.
	 * @param tag the tag to look for
	 */
	def tag(tag: String) = AuthAction.maybeAuthenticatedUser { implicit user =>
		AppAction { implicit request =>
			val alertIds = DynamicAlertTagModel.findAlertsByTag(tag).map(_.recordId)
			val alerts = DynamicAlertModel.findDynamicAlertByID(alertIds)
			val subscriptions = DynamicAlertTagSubscriptionModel.getSubscriptionsByTag(tag)
			Ok(views.html.datags.tag(tag, alerts, subscriptions))
		}
	}

	/**
	 * Search for a specific tag
	 * @param term the search term
	 * @param page the page of search results to show
	 */
	def search(term: String, page: Int) = AuthAction.maybeAuthenticatedUser { implicit user =>
		AppAction { implicit request =>
			val realPage = page.max(1)
			val (found, tags) = DynamicAlertTagModel.search(term, realPage - 1)
			val alertTags = DynamicAlertTagModel.findAlertsByTag(tags.map{_.tag})
			val alerts = DynamicAlertModel.findDynamicAlertByID(alertTags.map(_.recordId).distinct).filter(!_.deleted)
			Ok(views.html.datags.search(term, Pagination[TagMap[DynamicAlert]](realPage, found, DynamicAlertTagModel.configuredLimit, List(TagConverter.toTagMap[DynamicAlert](alertTags, alerts)))))
		}
	}

	/**
	 * Search tags by name. Returns json formatted for jquery-tokeninput.
	 */
	def searchToJson(term: String) = AuthAction.maybeAuthenticatedUser { implicit user =>
		AppAction { implicit request =>
			val (found, matches) = DynamicAlertTagModel.search(term + "%", 0)
			Ok(Json.toJson(matches.map{ m =>
				Json.obj("id" -> m.recordId.toString, "name" -> m.tag)
			}))
		}
	}
}
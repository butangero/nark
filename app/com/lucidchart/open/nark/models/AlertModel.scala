package com.lucidchart.open.nark.models

import anorm._
import anorm.SqlParser._
import AnormImplicits._
import com.lucidchart.open.nark.models.records.{Alert, AlertState, AlertStatus, Comparisons}
import java.math.BigDecimal
import java.util.{Date, UUID}
import play.api.Logger
import play.api.Play.current
import play.api.Play.configuration
import play.api.db.DB
import java.sql.Connection

object AlertModel extends AlertModel
class AlertModel extends AppModel {
	private val maxConsecutiveFailures = configuration.getInt("alerts.maxConsecutiveFailures").get
	private val consecutiveFailuresMultiplier = configuration.getInt("alerts.consecutiveFailuresMultiplier").get

	protected val alertsRowParser = {
		get[UUID]("id") ~ 
		get[String]("name") ~
		get[UUID]("user_id") ~
		get[String]("target") ~
		get[Int]("comparison") ~
		get[Option[UUID]]("dynamic_alert_id") ~
		get[Boolean]("active") ~
		get[Boolean]("deleted") ~
		get[Date]("created") ~
		get[Option[UUID]]("thread_id") ~
		get[Option[Date]]("thread_start") ~
		get[Date]("last_checked") ~
		get[Date]("next_check") ~
		get[Int]("frequency") ~
		get[BigDecimal]("warn_threshold") ~
		get[BigDecimal]("error_threshold") ~
		get[Int]("worst_state") ~
		get[Int]("consecutive_failures") map {
			case id ~ name ~ user_id ~ target ~ comparison ~ dynamic_alert_id ~ active ~ deleted ~ created ~ thread_id ~ thread_start ~ last_checked ~ next_check ~ frequency ~ warn_threshold ~ error_threshold ~ worst_state ~ consecutive_failures =>
				new Alert(id, name, user_id, target, Comparisons(comparison), dynamic_alert_id, active, deleted, created, thread_id, thread_start, last_checked, next_check, frequency, warn_threshold, error_threshold, AlertState(worst_state), consecutive_failures)
		}
	}

	/**
	 * Create a new alert using all the details from the alert object
	 * throws an exception on failure
	 *
	 * @param alert the Alert to create
	 */
	def createAlert(alert: Alert) {
		DB.withConnection("main") { connection =>
			SQL("""
				INSERT INTO `alerts` (`id`, `name`, `user_id`, `target`, `comparison`, `dynamic_alert_id`, `active`, `deleted`, `created`, `thread_id`, `thread_start`, `last_checked`, `next_check`, `frequency`, `warn_threshold`, `error_threshold`, `worst_state`, `consecutive_failures`)
				VALUES ({id}, {name}, {user_id}, {target}, {comparison}, {dynamic_alert_id}, {active}, {deleted}, {created}, {thread_id}, {thread_start}, {last_checked}, {next_check}, {frequency}, {warn_threshold}, {error_threshold}, {worst_state}, {consecutive_failures})
			""").on(
				"id"                    -> alert.id,
				"name"                  -> alert.name,
				"user_id"               -> alert.userId,
				"target"                -> alert.target,
				"comparison"            -> alert.comparison.id,
				"dynamic_alert_id"      -> alert.dynamicAlertId,
				"active"                -> alert.active,
				"deleted"               -> alert.deleted,
				"created"               -> alert.created,
				"thread_id"             -> alert.threadId,
				"thread_start"          -> alert.threadStart,
				"last_checked"          -> alert.lastChecked,
				"next_check"            -> alert.nextCheck,
				"frequency"             -> alert.frequency,
				"warn_threshold"        -> alert.warnThreshold,
				"error_threshold"       -> alert.errorThreshold,
				"worst_state"           -> alert.worstState.id,
				"consecutive_failures"  -> alert.consecutiveFailures
			).executeUpdate()(connection)
		}
	}

	/**
	 * Get the alert specified by the uuid
	 * @param id the uuid of the alert to get
	 * @return the requested alert
	 */
	def findAlertByID(id: UUID): Option[Alert] = {
		findAlertByID(List(id)).headOption
	}

	/**
	 * Get the alerts specified by the uuids passed in
	 * @param ids the ids of the alerts to get
	 * @return the requested alerts
	 */
	def findAlertByID(ids: List[UUID]): List[Alert] = {
		if (ids.isEmpty) {
			Nil
		}
		else {
			DB.withConnection("main") { connection =>
				RichSQL("""
					SELECT *
					FROM `alerts`
					WHERE `id` IN ({ids})
				""").onList(
					"ids" -> ids
				).toSQL.as(alertsRowParser *)(connection)
			}
		}
	}

	/**
	 * Search for alert records by a search term
	 * @param name the search term to search by
	 * @return a List of Alert records
	 */
	def search(name: String, page: Int) = {
		DB.withConnection("main") { connection =>
			val found = SQL("""
				SELECT COUNT(1) FROM `alerts`
				WHERE `name` LIKE {name} AND `deleted` = FALSE
			""").on(
				"name" -> name
			).as(scalar[Long].single)(connection)

			val matches = SQL("""
				SELECT * FROM `alerts`
				WHERE `name` LIKE {name} AND `deleted` = FALSE
				ORDER BY `name` ASC
				LIMIT {limit} OFFSET {offset}
			""").on(
				"name" -> name,
				"limit" -> configuredLimit,
				"offset" -> configuredLimit * page
			).as(alertsRowParser *)(connection)

			(found, matches)
		}
	}

	/**
	 * Edit an alert in the database
	 * @param alert the edited values
	 */
	def editAlert(alert: Alert) = {
		DB.withConnection("main") { connection =>
			SQL("""
				UPDATE `alerts`
				SET name={name}, target={target}, comparison={comparison}, active = {active}, deleted = {deleted}, 
					frequency={frequency}, error_threshold={error_threshold}, warn_threshold={warn_threshold}
				WHERE id={id}
			""").on(
				"id"                    -> alert.id,
				"name"                  -> alert.name,
				"target"                -> alert.target,
				"comparison"            -> alert.comparison.id,
				"active"                -> alert.active,
				"deleted"               -> alert.deleted,
				"frequency"             -> alert.frequency,
				"warn_threshold"        -> alert.warnThreshold,
				"error_threshold"       -> alert.errorThreshold
			).executeUpdate()(connection)
		}
	}

	private def handleAlertErrors(threadId: UUID, alerts: List[Alert])(implicit connection: Connection) {
		RichSQL("""
			UPDATE `alerts`
			SET
				`consecutive_failures` = LEAST(`consecutive_failures` + 1, {max_failures}),
				`thread_id` = NULL,
				`thread_start` = NULL,
				`last_checked` = NOW(),
				`next_check` = DATE_ADD(NOW(), INTERVAL (`frequency` * `consecutive_failures` * {failure_multiplier}) SECOND)
			WHERE `id` IN ({ids}) AND `thread_id` = {thread_id}
		""").onList(
			"ids" -> alerts.map(_.id)
		).toSQL.on(
			"thread_id"          -> threadId,
			"max_failures"       -> maxConsecutiveFailures,
			"failure_multiplier" -> consecutiveFailuresMultiplier
		).executeUpdate()(connection)
	}

	/**
	 * Used by the alerting job to take the next alert(s) that needs to be checked, check the alert.
	 * @param threadId the thread of the worker checking the alerts
	 * @param limit the max number of alerts to take
	 * @param worker handles the alerts to be checked and returns the status (success or failure)
	 */
	def takeNextAlertsToCheck(threadId: UUID, limit: Integer)(worker: (List[Alert]) => Map[Alert, AlertStatus.Value]) = {
		val start = new Date()
		val alertsForWorker = DB.withConnection("main") { connection =>
			val updated = SQL("""
				UPDATE `alerts`
				SET `thread_id` = {thread_id},
					`thread_start` = {thread_start}
				WHERE `active` = 1
				AND `deleted` = 0
				AND `thread_id` IS NULL
				AND `next_check` <= NOW()
				ORDER BY `next_check` ASC, `id` ASC
				LIMIT {limit}
			""").on(
				"thread_id" -> threadId,
				"thread_start" -> start,
				"limit" -> limit
			).executeUpdate() (connection)

			if (updated == 0) {
				Nil
			}
			else {
				SQL("""
					SELECT * FROM `alerts`
					WHERE `thread_id` = {thread_id}
					AND `thread_start` = {thread_start}
				""").on(
					"thread_id" -> threadId,
					"thread_start" -> start
				).as(alertsRowParser *)(connection)
			}
		}

		try {
			val completedAlertStatus = worker(alertsForWorker)
			require(completedAlertStatus.size == alertsForWorker.size)

			if (!alertsForWorker.isEmpty) {
				DB.withConnection("main"){ connection =>
					completedAlertStatus.groupBy { case (alert, status) => status }.map { case (status, records) =>
						val alerts = records.map(_._1).toList
						status match {
							case AlertStatus.success => {
								alerts.groupBy { alert => alert.worstState }.map { case (worstState, sameAlerts) =>
									RichSQL("""
										UPDATE `alerts` 
										SET
											`consecutive_failures` = 0,
											`worst_state` = {worst_state},
											`thread_id` = NULL,
											`thread_start` = NULL,
											`last_checked` = NOW(),
											`next_check` = DATE_ADD(NOW(), INTERVAL `frequency` SECOND)
										WHERE `id` IN ({ids}) AND `thread_id` = {thread_id}
									""").onList(
										"ids" -> sameAlerts.map(_.id)
									).toSQL.on(
										"thread_id"   -> threadId,
										"worst_state" -> worstState.id
									).executeUpdate()(connection)
								}
							}
							case AlertStatus.failure => {
								handleAlertErrors(threadId, alerts)(connection)
							}
						}
					}
				}
			}

			completedAlertStatus
		}
		catch {
			case e: Exception => {
				Logger.error("Error occurred while processing alerts.", e)

				if (!alertsForWorker.isEmpty) {
					DB.withConnection("main"){ connection =>
						handleAlertErrors(threadId, alertsForWorker)(connection)
					}
				}

				throw e
			}
		}
	}

	/**
	 * Clean up after broken alert threads
	 */
	def cleanAlertThreadsBefore(date: Date) {
		DB.withConnection("main") { connection =>
			SQL("""
				UPDATE `alerts` 
				SET `thread_id` = NULL,
					`thread_start` = NULL
				WHERE `thread_id` IS NOT NULL
				AND `thread_start` < {date}
			""").on(
				"date" -> date
			).executeUpdate() (connection)
		}
	}
}

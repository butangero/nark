package com.lucidchart.open.nark.controllers

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

import scala.collection.JavaConversions
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

import org.openid4java.OpenIDException
import org.openid4java.consumer.ConsumerManager
import org.openid4java.message.AuthSuccess
import org.openid4java.message.ParameterList
import org.openid4java.message.ax.AxMessage
import org.openid4java.message.ax.FetchRequest
import org.openid4java.message.ax.FetchResponse

import com.lucidchart.open.nark.models.UserModel
import com.lucidchart.open.nark.models.records.User
import com.lucidchart.open.nark.openid.CustomAssociationStore
import com.lucidchart.open.nark.openid.CustomNonceGenerator
import com.lucidchart.open.nark.openid.CustomNonceVerifier
import com.lucidchart.open.nark.openid.DiscoveryInformationConverter
import com.lucidchart.open.nark.request.AppAction
import com.lucidchart.open.nark.request.AppFlash
import com.lucidchart.open.nark.request.AuthAction
import com.lucidchart.open.nark.utils.Auth
import com.lucidchart.open.nark.utils.StatsD
import com.lucidchart.open.nark.views

import akka.actor.Actor
import akka.actor.Props
import play.api.Logger
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.Crypto
import play.api.libs.concurrent.Akka
import play.api.mvc.BodyParsers
import play.api.mvc.Cookie
import play.api.mvc.DiscardingCookie
import play.api.mvc.RequestHeader
import play.api.mvc.Result

class AuthController extends AppController {
	protected val openIDManager = new ConsumerManager()
	protected val openIDNonceVerifier = new CustomNonceVerifier
	protected val openIDNonceGenerator = new CustomNonceGenerator
	protected val openIDAssociationStore = new CustomAssociationStore
	
	openIDManager.setNonceVerifier(openIDNonceVerifier)
	openIDManager.setConsumerNonceGenerator(openIDNonceGenerator)
	openIDManager.setAssociations(openIDAssociationStore)
	
	protected class OpenIDCleaner extends Actor {
		def receive = {
			case _ => {
				openIDNonceVerifier.cleanup
				openIDAssociationStore.cleanup
			}
		}
	}
	
	protected val openIDCleaner = Akka.system.actorOf(Props(new OpenIDCleaner), name = "OpenIDCleaner")
	Akka.system.scheduler.schedule(Random.nextInt(300).seconds, 30.minutes, openIDCleaner, "go")
	
	protected val openIDDiscoveryURL = "https://www.google.com/accounts/o8/id"
	
	protected def loginRedirect(userId: UUID, rememberme: Boolean)(implicit request: RequestHeader) = {
		val authCookie = Auth.generateAuthCookie(userId, rememberme, request.headers.get("User-Agent").getOrElse(""))
		val origDestCookie = DiscardingCookie("origdest")
		val destination = request.cookies.get("origdest").map(_.value).getOrElse(routes.HomeController.index().url)
		Redirect(destination).withCookies(authCookie).discardingCookies(origDestCookie)
	}
	
	/**
	 * Begin an OpenID login
	 */
	def openidLogin = AuthAction.loggedOut {
		AppAction { implicit request =>
			// this code was taken, almost copy/paste, from
			// https://code.google.com/p/openid4java/wiki/SampleConsumer
			val discoveries = openIDManager.discover(openIDDiscoveryURL)
			val discovered = openIDManager.associate(discoveries)
			
			val discoveredAsString = DiscoveryInformationConverter.infoToString(discovered)
			val discoveredCookie = Cookie(
				"openid-discovery",
				URLEncoder.encode(Crypto.sign(discoveredAsString) + "|" + discoveredAsString, "UTF-8"),
				None
			)
			
			val openIDReturn = routes.AuthController.openidCallback().absoluteURL()
			val authRequest = openIDManager.authenticate(discoveries, openIDReturn)
			
			val fetch = FetchRequest.createFetchRequest()
			fetch.addAttribute("email", "http://axschema.org/contact/email",    true)
			fetch.addAttribute("first", "http://axschema.org/namePerson/first", true)
			fetch.addAttribute("last",  "http://axschema.org/namePerson/last",  true)
			authRequest.addExtension(fetch)
			
			// Because of the 2kiB limit on urls, this may need to be an auto-submitted form in the future
			val simpleRedirect = Redirect(authRequest.getDestinationUrl(true)).withCookies(discoveredCookie)

			val referer = request.headers.get("Referer").getOrElse("")
			if (!referer.isEmpty && request.cookies.get("origdest").isEmpty) {
				simpleRedirect.withCookies(Cookie("origdest", referer))
			}
			else {
				simpleRedirect
			}
		}
	}
	
	/**
	 * User has come back after an OpenID redirect.
	 */
	def openidCallback = AuthAction.loggedOut {
		AppAction { implicit request =>
			val discardDiscovery = DiscardingCookie("openid-discovery")
			
			request.cookies.get("openid-discovery") match {
				case None => Redirect(routes.HomeController.index()).flashing(AppFlash.error("The OpenID request failed. Are your cookies turned on?", "OpenID Failure"))
				case Some(discoveredCookie) => {
					val discoveredOption = try {
						val parts = URLDecoder.decode(discoveredCookie.value, "UTF-8").split("\\|", 2).toList
						val cookieSignature = parts(0)
						val discoveredAsString = parts(1)
						
						val serverSignature = Crypto.sign(discoveredAsString)
						if (cookieSignature != serverSignature) {
							throw new Exception()
						}
						
						val info = DiscoveryInformationConverter.stringToInfo(discoveredAsString, openIDManager)
						Some(info)
					}
					catch {
						case e: Exception => None
					}
					
					discoveredOption match {
						case None => Redirect(routes.HomeController.index()).discardingCookies(discardDiscovery) // they tampered with their cookie
						case Some(discovered) => {
							val receivingURL = "http://" + request.host + request.request.uri.toString
							val response = new ParameterList(JavaConversions.mapAsJavaMap(request.queryString.map { case (k,v) => (k, v.toArray) }))
							val verification = openIDManager.verify(receivingURL, response, discovered)
							
							verification.getVerifiedId() match {
								case null => BadRequest("invalid").discardingCookies(discardDiscovery)
								case verifiedId => {
									val authSuccess = verification.getAuthResponse().asInstanceOf[AuthSuccess]
									val fetchResponse = authSuccess.getExtension(AxMessage.OPENID_NS_AX).asInstanceOf[FetchResponse]
									
									val email     = fetchResponse.getAttributeValue("email")
									val firstName = fetchResponse.getAttributeValue("first")
									val lastName  = fetchResponse.getAttributeValue("last")
									
									val user = UserModel.findUserByEmail(email) match {
										case Some(user) => user
										case None => {
											val user = new User(email, firstName + " " + lastName)
											UserModel.createUser(user)
											UserModel.findUserByEmail(email).get
										}
									}
									
									loginRedirect(user.id, false).discardingCookies(discardDiscovery)
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * User logout
	 */
	def logout = AuthAction.loggedIn {
		AppAction { implicit request =>
			Redirect(routes.HomeController.index()).discardingCookies(Auth.discardingCookie).flashing(AppFlash.success("You have been logged out successfully.", "Logged Out"))
		}
	}
}

object AuthController extends AuthController

package com.linagora.openpaas.gatling.core.authentication.pkceWithCas

import java.net.URLEncoder
import com.google.common.base.Charsets
import com.linagora.openpaas.gatling.Configuration._
import com.linagora.openpaas.gatling.provisionning.SessionKeys._
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import io.gatling.http.request.builder.HttpRequestBuilder
import com.linagora.openpaas.gatling.core.authentication.lemonldap.LemonLdapTemplateRequestsList._
import io.gatling.http.client.ahc.uri.Uri
import io.gatling.http.cookie.{CookieJar, CookieSupport}

import java.util.UUID
import scala.collection.JavaConverters._

object PKCEWithCasSteps {

  private val loginCasAssets = List(
    "/webjars/jquery/3.3.1-1/jquery.min.js",
    "/webjars/zxcvbn/4.3.0/zxcvbn.js",
    "/webjars/jquery-ui/1.12.1/jquery-ui.min.js",
    "/webjars/jquery-cookie/1.4.1-1/jquery.cookie.js",
    "/webjars/bootstrap/4.1.3/js/bootstrap.bundle.min.js",
    "/webjars/headjs/1.0.3/head.min.js",
    "/js/cas.js",
    "/images/cas-logo.png",
    "/images/cas_cas.png",
    "/images/logo2.png",
    "/favicon.ico",
  )
  private val logoutCasAssets = List(
    "/webjars/headjs/1.0.3/head.min.js",
    "/js/cas.js",
    "/webjars/jquery/3.3.1-1/jquery.min.js",
    "/images/cas-logo.png",
    "/images/cas_cas.png",
    "/images/logo2.png",
    "/webjars/zxcvbn/4.3.0/zxcvbn.js",
    "/webjars/jquery-ui/1.12.1/jquery-ui.min.js",
    "/webjars/jquery-cookie/1.4.1-1/jquery.cookie.js",
    "/webjars/bootstrap/4.1.3/js/bootstrap.bundle.min.js",
    "/favicon.ico"
  )

  def loadLoginTemplates: ChainBuilder =
    loadAssets("Load LemonLDAP authentication portal", authLoginPageTemplates.toList, LemonLDAPPortalUrl)

  def loadLoginCasTemplates: ChainBuilder =
    loadAssets("Load CAS authentication portal", logoutCasAssets, CasBaseUrl)

  def loadLogoutCasTemplates: ChainBuilder =
    loadAssets("Load CAS disconnected portal", loginCasAssets, CasBaseUrl)

  private def loadAssets(groupName: String, assets: List[String], baseUrl: String): ChainBuilder = {
    val counterName = "counter_"  + UUID.randomUUID().toString
    group(groupName) {
      repeat(assets.length, counterName) {
        exec(session => {
          val index = session(counterName).as[Int]
          val resourceURL = assets(index)
          session.set("resourceURL", resourceURL)
        })
          .exec(http(s"Load $${resourceURL}")
            .get(s"${baseUrl}$${resourceURL}")
            .check(status in(200, 304)))
      }.exec(session => session.remove(counterName))
    }
  }

  def getLemonPage: HttpRequestBuilder =
    http("Get LemonLDAP login page")
      .get(LemonLDAPPortalUrl + s"/oauth2/authorize?client_id=${OidcClient}&redirect_uri=${URLEncoder.encode(OidcCallback, Charsets.UTF_8)}&response_type=code&scope=openid%20email%20profile&state=$${oidc_state}&code_challenge=$${pkce_code_challenge}&code_challenge_method=${PkceCodeChallengeMethod}&response_mode=query")
      .disableFollowRedirect
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
      ))
      .check(status in (200),
        css("input[name='RelayState']", "value").saveAs("cas_relay_state"),
        css("input[name='SAMLRequest']", "value").saveAs("cas_saml_request")
      )

  def casSSO: HttpRequestBuilder =
    http("get CAS SSO")
      .post(CasBaseUrl + "/idp/profile/SAML2/POST/SSO")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1",
        "Content-Type" -> "application/x-www-form-urlencoded"
      ))
      .formParam("RelayState", "${cas_relay_state}")
      .formParam("SAMLRequest", "${cas_saml_request}")
      .disableFollowRedirect
      .check(status.is(302), header("Location")
        .saveAs("cas_login_page"))


  def casLoginPage: HttpRequestBuilder =  http("get CAS Login Page")
    .get("${cas_login_page}")
    .check(status.is(200), css("input[name='execution']", "value").saveAs("cas_execution"))


  def login: HttpRequestBuilder =
    http("Login through CAS")
      .post("${cas_login_page}")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1",
        "Content-Type" -> "application/x-www-form-urlencoded"))
      .formParam("execution", "${cas_execution}")
      .formParam("geolocation", "")
      .formParam("_eventId", "submit")
      .formParam("username", s"$${$UsernameSessionParam}")
      .formParam("password", s"$${$PasswordSessionParam}")
      .disableFollowRedirect
      .check(status.is(302),
        header("Location").saveAs("cas_profile"))

  def casProfile: HttpRequestBuilder =
    http("CAS profile")
      .get("${cas_profile}")
      .disableFollowRedirect
      .check(status.is(200),
        css("input[name='RelayState']", "value").saveAs("cas_relay_state_response"),
        css("input[name='SAMLResponse']", "value").saveAs("cas_saml_response"))

  def casProxySSO: HttpRequestBuilder =
    http("CAS proxy single sign on post")
      .post(LemonLDAPPortalUrl + "/saml/proxySingleSignOnPost")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1",
        "Content-Type" -> "application/x-www-form-urlencoded"))
      .formParam("RelayState", "${cas_relay_state_response}")
      .formParam("SAMLResponse", "${cas_saml_response}")
      .disableFollowRedirect
      .check(status.is(302),
        header("Location").saveAs("cas_profile"))

  def obtainAuthorizationCode: HttpRequestBuilder =
    http("CAS profile")
      .get(LemonLDAPPortalUrl + "//oauth2")
      .disableFollowRedirect
      .check(status.is(302),
        header("Location")
          .transform(extractAuthorizationCodeFromLocation _)
          .saveAs("authorization_code"))

  private val CookieBackup = CookieSupport.CookieJarAttributeName + "_backup"

  def getToken: ChainBuilder = exec(session => {
    session
      .set(CookieBackup, CookieSupport.cookieJar(session).get)
      .remove(CookieSupport.CookieJarAttributeName)}
  )
    .exec(http("get token")
      .post(LemonLDAPPortalUrl + "/oauth2/token")
      .formParam("client_id", OidcClient)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .formParam("code", "${authorization_code}")
      .formParam("redirect_uri", OidcCallback)
      .formParam("code_verifier", "${pkce_code_verifier}")
      .formParam("grant_type", "authorization_code")
      .check(status.is(200),
        jsonPath("$.access_token").find.saveAs("access_token"),
        jsonPath("$.refresh_token").find.saveAs("refresh_token"),
        jsonPath("$.id_token").find.saveAs("id_token")
      ))
    .exec(exec(session => {
      session
        .set(CookieSupport.CookieJarAttributeName, session(CookieBackup).asOption[CookieJar].get)
        .remove(CookieBackup)}
    ))

  def renewAccessToken: HttpRequestBuilder =
    http("get token")
      .post(LemonLDAPPortalUrl + "/oauth2/token")
      .formParam("client_id", OidcClient)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .formParam("refresh_token", "${refresh_token}")
      .formParam("request_type", "si:s")
      .formParam("grant_type", "refresh_token")
      .check(status.is(200),
        jsonPath("$.access_token").find.saveAs("access_token")
      )

  private def extractAuthorizationCodeFromLocation(locationUrl: String): String = {
    Uri.create(locationUrl.replace("/#/","/"))
      .getEncodedQueryParams.asScala.find(_.getName == "code").get.getValue
  }

  def goToOpenPaaSApplication: HttpRequestBuilder =
    http("Go to OpenPaaS application")
      .get("/")
      .check(status in (200, 304))

  def logout: ChainBuilder = {
    exec(logoutGoToConfirmationPage)
      .exec(logoutConfirmation)
      .exec(logoutCasSLO)
      .exec(logoutCasLandingPage)
      .exec(loadLogoutCasTemplates)
  }

  private def logoutCasLandingPage = {
    http("logout CAS landing page")
      .get(CasBaseUrl + "/logout")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1"
      ))
      .formParam("SAMLRequest", "${cas_logout_saml_request}")
      .check(status.is(200))
  }

  private def logoutCasSLO = {
    http("logout CAS SLO")
      .post(CasBaseUrl + "/idp/profile/SAML2/POST/SLO")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1",
        "Content-Type" -> "application/x-www-form-urlencoded"
      ))
      .formParam("SAMLRequest", "${cas_logout_saml_request}")
      .disableFollowRedirect
      .check(status.is(302), header("Location")
        .saveAs("cas_login_page"))
  }

  private def logoutConfirmation = {
    http("Logout confirmation")
      .post(LemonLDAPPortalUrl + "/oauth2/logout")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1",
        "Content-Type" -> "application/x-www-form-urlencoded"))
      .queryParam("id_token_hint", "${id_token}")
      .queryParam("post_logout_redirect_uri", s"${OpenPaaSBaseUrl}/${InboxSpaPath}")
      .formParam("id_token_hint", "${id_token}")
      .formParam("post_logout_redirect_uri", s"${OpenPaaSBaseUrl}/${InboxSpaPath}")
      .formParam("confirm", "${logout_confirm}")
      .formParam("skin", "bootstrap")
      .check(status is 200, css("input[name='SAMLRequest']", "value").saveAs("cas_logout_saml_request"))
  }

  private def logoutGoToConfirmationPage = {
    http("Logout go to confirmation page")
      .get(LemonLDAPPortalUrl + "/oauth2/logout")
      .queryParam("id_token_hint", "${id_token}")
      .headers(Map(
        "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "upgrade-insecure-requests" -> "1"))
      .queryParam("post_logout_redirect_uri", s"${OpenPaaSBaseUrl}/${InboxSpaPath}")
      .check(status is 200, css("input[name='confirm']", "value").saveAs("logout_confirm"))
  }
}

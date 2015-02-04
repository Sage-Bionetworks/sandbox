package filters

import play.api.http.HeaderNames._
import play.api.http.Status._
import play.api.mvc._
import play.api.mvc.Results._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.http.HeaderNames

import org.sagebionetworks.bridge.BridgeConstants

object CorsFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[SimpleResult])
        (requestHeader: RequestHeader): Future[SimpleResult] = {
    nextFilter(requestHeader) map {result => result.withHeaders(
        ACCESS_CONTROL_ALLOW_ORIGIN -> ("https://" + BridgeConstants.ASSETS_HOST),
        ACCESS_CONTROL_ALLOW_METHODS -> "HEAD, GET, OPTIONS, POST, PUT, DELETE",
        ACCESS_CONTROL_ALLOW_HEADERS -> "*")
    }
  }
}

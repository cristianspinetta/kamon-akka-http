package kamon.testkit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import scala.concurrent.{ ExecutionContextExecutor, Future }

case class WebServer(interface: String, port: Int)
                    (implicit private val system: ActorSystem,
                     private val executor: ExecutionContextExecutor,
                     private val materializer: ActorMaterializer) {

  import WebServerSupport.Endpoints._

  private var bindingFutOpt: Option[Future[Http.ServerBinding]] = None

  private val routes = logRequest("routing-request") {
    get {
      path(traceOk) {
        complete(OK)
      } ~
        path(traceBadRequest) {
          complete(BadRequest)
        } ~
        path(metricsOk) {
          complete(OK)
        } ~
        path(metricsBadRequest) {
          complete(BadRequest)
        }
    }
  }

  def start(): Future[ServerBinding] = {
    bindingFutOpt = Some(Http().bindAndHandle(routes, interface, port))
    bindingFutOpt.get
  }

  def shutdown(): Future[_] = {
    bindingFutOpt
      .map(bindingFut =>
        bindingFut
          .flatMap(binding => binding.unbind())
          .map(_ => system.terminate())
      )
      .getOrElse(Future.successful(Unit))
  }

}

trait WebServerSupport {

  object Endpoints {
    val traceOk: String = "record-trace-metrics-ok"
    val traceBadRequest: String = "record-trace-metrics-bad-request"
    val metricsOk: String = "record-http-metrics-ok"
    val metricsBadRequest: String = "record-http-metrics-bad-request"

    implicit class Converter(endpoint: String) {
      implicit def withSlash: String = "/" + endpoint
    }
  }
}

object WebServerSupport extends WebServerSupport

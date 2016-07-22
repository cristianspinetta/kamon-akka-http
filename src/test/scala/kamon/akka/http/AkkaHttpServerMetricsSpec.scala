/*
 * =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.akka.http

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import kamon.Kamon
import kamon.testkit.{ BaseKamonSpec, WebServer, WebServerSupport }
import org.scalatest.Matchers

import scala.concurrent.duration._
import scala.concurrent._

class AkkaHttpServerMetricsSpec extends BaseKamonSpec with Matchers {

  implicit private val system = ActorSystem()
  implicit private val executor = system.dispatcher
  implicit private val materializer = ActorMaterializer()

  val timeoutStartUpServer = 1 second

  val interface = "0.0.0.0"
  val port = 9005

  val webServer = WebServer(interface, port)

  def url(endpoint: String) = s"http://localhost:$port$endpoint"

  override protected def beforeAll(): Unit = {
    Kamon.start()
    Await.result(webServer.start(), timeoutStartUpServer)
  }

  override protected def afterAll(): Unit = {
    Await.result(webServer.shutdown(), timeoutStartUpServer)
    Kamon.shutdown()
  }

  import WebServerSupport.Endpoints._

  "the Akka Http Server metrics instrumentation" should {
    "record trace metrics for processed requests" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection("localhost", port)

      val okResponsesFut = for (repetition ← 1 to 10) yield {
        Source.single(HttpRequest(uri = WebServerSupport.Endpoints.traceOk.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map { case httpResponse => httpResponse.status shouldBe OK }

      val badRequestResponsesFut = for (repetition ← 1 to 5) yield {
        Source.single(HttpRequest(uri = WebServerSupport.Endpoints.traceBadRequest.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map { case httpResponse => httpResponse.status shouldBe BadRequest }

      Await.result(Future.sequence(okResponsesFut ++ badRequestResponsesFut), timeoutStartUpServer)

      val snapshot = takeSnapshotOf("UnnamedTrace", "trace")
      snapshot.histogram("elapsed-time").get.numberOfMeasurements should be(15)
    }

    "record http server metrics for all the requests" in {

      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http().outgoingConnection("localhost", port)

      // Erase metrics recorder from previous tests.
      takeSnapshotOf("akka-http-server", "akka-http-server")

      val okResponsesFut = for (repetition ← 1 to 10) yield {
        Source.single(HttpRequest(uri = WebServerSupport.Endpoints.metricsOk.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map { case httpResponse => httpResponse.status shouldBe OK }

      val badRequestResponsesFut = for (repetition ← 1 to 5) yield {
        Source.single(HttpRequest(uri = WebServerSupport.Endpoints.metricsBadRequest.withSlash))
          .via(connectionFlow)
          .runWith(Sink.head)
      } map { case httpResponse => httpResponse.status shouldBe BadRequest }

      Await.result(Future.sequence(okResponsesFut ++ badRequestResponsesFut), timeoutStartUpServer)

      val snapshot = takeSnapshotOf("akka-http-server", "akka-http-server")
      snapshot.counter("200_UnnamedTrace").get.count should be(10)
      snapshot.counter("400_UnnamedTrace").get.count should be(5)
      snapshot.counter("UnnamedTrace").get.count should be(15)

      snapshot.minMaxCounter("request-active") should be(defined)
      snapshot.minMaxCounter("connection-open") should be(defined)
    }
  }
}

class TestNameGenerator extends NameGenerator {
  def generateTraceName(request: HttpRequest): String = "UnnamedTrace"
  def generateRequestLevelApiSegmentName(request: HttpRequest): String = "request-level " + request.uri.path.toString()
  def generateHostLevelApiSegmentName(request: HttpRequest): String = "host-level " + request.uri.path.toString()
}

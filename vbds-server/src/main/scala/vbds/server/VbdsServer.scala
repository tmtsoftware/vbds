package vbds.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import vbds.server.actors.AdminData
import vbds.server.controllers.StreamAdmin

import scala.concurrent.Future

class VbdsServer(sharedData: AdminData)(implicit system: ActorSystem) {
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  // XXX TODO: add other routes, possibly based on command line options ...
  val route = new StreamAdmin(sharedData).route

  /**
    * Starts the server on the given host and port
    * @return the server binding
    */
  def start(host: String, port: Int): Future[Http.ServerBinding] =
    Http().bindAndHandle(route, host, port)

  /**
    * Stops the server
    * @param binding the return value from start()
    */
  def stop(binding: Http.ServerBinding): Unit =
    binding.unbind().onComplete(_ => system.terminate())
}

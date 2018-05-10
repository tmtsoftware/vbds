package vbds.server.app

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteConcatenation._
import akka.stream.ActorMaterializer
import vbds.server.actors.SharedDataActor.LocalAddress
import vbds.server.actors.{AccessApiImpl, AdminApiImpl, SharedDataActor, TransferApiImpl}
import vbds.server.routes.{AccessRoute, AdminRoute, TransferRoute}

import scala.concurrent.Future

object VbdsServer {
  /**
    * Starts the server (Assumes that the given ActorSystem is already configured correctly for the cluster)
    */
  def start(httpHost: String, httpPort: Int)(implicit system: ActorSystem): Future[Http.ServerBinding] = {
    import system.dispatcher
    implicit val mat = ActorMaterializer()

    // Initialize the cluster for replicating the data
    val replicator      = DistributedData(system).replicator
    implicit val node   = Cluster(system)
    val sharedDataActor = system.actorOf(SharedDataActor.props(replicator))
    new VbdsServer(sharedDataActor).start(httpHost, httpPort)
  }
}


class VbdsServer(sharedDataActor: ActorRef)(implicit system: ActorSystem, mat: ActorMaterializer) {

  import system.dispatcher

  val adminApi    = new AdminApiImpl(sharedDataActor)
  val accessApi   = new AccessApiImpl(sharedDataActor)
  val transferApi = new TransferApiImpl(sharedDataActor, accessApi)

  val adminRoute    = new AdminRoute(adminApi)
  val accessRoute   = new AccessRoute(adminApi, accessApi)
  val transferRoute = new TransferRoute(adminApi, accessApi, transferApi)
  val route         = adminRoute.route ~ accessRoute.route ~ transferRoute.route

  /**
   * Starts the server on the given host and port
   *
   * @return the server binding
   */
  def start(host: String, port: Int): Future[Http.ServerBinding] = {
    val f = Http().bindAndHandle(route, host, port)
    // Need to know this http server's address when subscribing
    f.foreach(binding => sharedDataActor ! LocalAddress(binding.localAddress))
    f
  }

  /**
   * Stops the server
   *
   * @param binding the return value from start()
   */
  def stop(binding: Http.ServerBinding): Unit =
    binding.unbind().onComplete(_ => system.terminate())

}

package vbds.server.actors

import akka.Done
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorMaterializer
import akka.util.{ByteString, Timeout}
import vbds.server.actors.SharedDataActor.{Publish, SharedDataActorMessages}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Internal data transfer API
 */
trait TransferApi {
  def publish(streamName: String, source: Source[ByteString, Any], dist: Boolean): Future[Done]
}

class TransferApiImpl(sharedDataActor: ActorRef[SharedDataActorMessages], accessApi: AccessApi)(
                                                                       implicit val mat: ActorMaterializer,
                                                                       timeout: Timeout = 5.minutes)
    extends TransferApi {

  def publish(streamName: String, source: Source[ByteString, Any], dist: Boolean): Future[Done] = {
    (sharedDataActor ? Publish(streamName, source, dist)).mapTo[Done]
  }
}

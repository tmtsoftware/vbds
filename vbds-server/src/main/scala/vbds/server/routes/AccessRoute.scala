package vbds.server.routes

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.event.{LogSource, Logging}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives
import akka.stream._
import akka.stream.scaladsl.{Flow, MergeHub, Sink}
import akka.util.ByteString
import vbds.server.actors.{AccessApi, AdminApi}
import vbds.server.models.JsonSupport
import vbds.server.routes.AccessRoute.WebsocketResponseActor


object AccessRoute {

  // Actor to handle ACK responses from websocket clients
  object WebsocketResponseActor {
    // Responds with Ack if there is a response from the ws client
    final case object Get

    // Says there was a response from the ws client
    final case object Put

    // Reponse to Get message
    final case object Ack

    def props(): Props = Props(new WebsocketResponseActor)
  }

  class WebsocketResponseActor extends Actor with ActorLogging {
    import WebsocketResponseActor._
    def receive: Receive = receiveResponses(0, Nil)

    def receiveResponses(responses: Int, senders: List[ActorRef]): Receive = {
      case Put =>
        if (senders.nonEmpty) {
          senders.last ! Ack
          context.become(receiveResponses(responses, senders.dropRight(1)))
        } else {
          context.become(receiveResponses(responses + 1, Nil))
        }

      case Get =>
        if (responses > 0) {
          sender() ! Ack
          context.become(receiveResponses(responses - 1, senders))
        } else {
          context.become(receiveResponses(0, sender() :: senders))
        }
    }
  }

}


/**
 * Provides the HTTP route for the VBDS Access Service.
 *
 * @param adminData used to access the distributed list of streams (using cluster + CRDT)
 */
class AccessRoute(adminData: AdminApi, accessData: AccessApi)(implicit val system: ActorSystem,
                                                              implicit val mat: ActorMaterializer)
    extends Directives
    with JsonSupport {

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName

    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  val log = Logging(system, this)

  val route =
    pathPrefix("vbds" / "access" / "streams") {
      // List all streams: Response: OK: Stream names in JSON; empty document if no streams
      get {
        onSuccess(adminData.listStreams()) { streams =>
          Cors.cors(complete(streams))
        }
        // Create a stream subscription: Response: SwitchingProtocols - Creates a websocket connection to the Access Service
        path(Remaining) { name =>
          log.debug(s"subscribe to stream: $name")
          onSuccess(adminData.streamExists(name)) { exists =>
            if (exists) {
              log.debug(s"subscribe to existing stream: $name")

              // We need a Source for writing to the websocket, but we want a Sink:
              // This provides a Sink that feeds the Source.
              val (sink, source) = MergeHub.source[ByteString].preMaterialize()
              val id             = UUID.randomUUID().toString
                val wsResponseActor = system.actorOf(WebsocketResponseActor.props())

              val inSink = Flow[Message]
                .map { msg =>
                  // Notify this actor that the ws client responded, so that the publisher can check it
                  wsResponseActor ! WebsocketResponseActor.Put
                  msg
                }
                .to(Sink.onComplete[Message] { _ =>
                  log.info(s"Deleting subscription with id $id after client closed websocket connection")
                  accessData.deleteSubscription(id)
                  system.stop(wsResponseActor)
                })

              onSuccess(accessData.addSubscription(name, id, sink, wsResponseActor)) { _ =>
                extractUpgradeToWebSocket { upgrade =>
                  Cors.cors(complete(upgrade.handleMessagesWithSinkSource(inSink, source.map(BinaryMessage(_)))))
                }
              }
            } else {
              Cors.cors(complete(StatusCodes.NotFound -> s"The stream $name does not exists"))
            }
          }
        }
      } ~
      // Deletes a stream subscription: Response: 204 – Success (no content) or 404 – Subscription not found
      delete {
        path(Remaining) { id => // id returned as part of AccessData response to subscription request
          onSuccess(accessData.subscriptionExists(id)) { exists =>
            if (exists) {
              onSuccess(accessData.deleteSubscription(id)) {
                Cors.cors(complete(StatusCodes.Accepted))
              }
            } else {
              Cors.cors(complete(StatusCodes.NotFound -> s"The subscription with the id $id does not exist"))
            }
          }

        }
      }
    }
}

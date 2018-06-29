package vbds.web

import org.scalajs.dom
import org.scalajs.dom.{BlobPropertyBag, Event, MessageEvent}
import org.scalajs.dom.raw.{Blob, WebSocket}

import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import VbdsWebApp._

import upickle.default._

/**
  * A web app that lets you subscribe to vbds images and displays them in a JS9 window on the page.
  */
object VbdsWebApp {

  // Object returns when listing streams
  private case class StreamInfo(name: String)

  private object StreamInfo {
    // JSON support
    implicit def rw: ReadWriter[StreamInfo] = macroRW
  }

  // XXX TODO FIXME: Add feature to enter or discover host and port of vbds-server!
  private val host = "192.168.178.77"
  private val port = 7777

  // vbds server routes
  private val adminRoute                = "/vbds/admin/streams"
  private val accessRoute               = "/vbds/access/streams"
  //  private val transferRoute             = "/vbds/transfer/streams"

  // URI to get a list of streams
  private val listStreamsUri = s"http://$host:$port$adminRoute"

  // URI to subscribe to a stream
  private def subscribeUri(stream: String) = s"ws://$host:$port$accessRoute/$stream"

}

class VbdsWebApp {

  // Can't save temp files from the browser, so have to keep the image parts in memory...
  private var currentImageData: List[Uint8Array] = Nil

  // WebSocket for current subscription
  private var currentWebSocket: Option[WebSocket] = None


  // Combobox listing the available streams
  private val streamsItem = {
    import scalatags.JsDom.all._
    // XXX TODO: FIXME: deprecated warning: forward usage?
    select(onchange := subscribeToStream _)(
      option(value := "", selected := true)("")
    ).render
  }

  // Gets the currently selected stream name
  private def getSelectedStream: Option[String] =
    streamsItem.value match {
      case "" => None
      case x => Some(x)
    }

  // Update the streams combobox options
  private def updateStreamOptions(items: List[StreamInfo]): Unit = {
    import scalatags.JsDom.all._
    for (i <- (1 until streamsItem.length).reverse) {
      streamsItem.remove(i)
    }
    items.foreach { str =>
      streamsItem.add(option(value := str.name)(str.name).render)
    }
  }

  // Update the menu with the list of streams
  private def updateStreamsList(): Unit = {
    val xhr = new dom.XMLHttpRequest()
    xhr.open("GET", listStreamsUri)
    xhr.onload = { _: dom.Event =>
      if (xhr.status == 200) {
        val streams = read[List[StreamInfo]](xhr.responseText)
        updateStreamOptions(streams)
      }
    }
    xhr.send()
  }

  // Combine the image parts and send to the display
  private def displayImage(): Unit = {
    val buffers = currentImageData.reverse
    currentImageData = Nil
    val properties = js.Dynamic.literal("type" -> "image/fits").asInstanceOf[BlobPropertyBag]
    // JS9 has code to "flatten if necessary", so we can just pass in all the file parts together
    val blob = new Blob(js.Array(buffers :_*), properties)
//    JS9.Load(blob)
    JS9.RefreshImage(blob)
  }

  // Called when a stream is selected: Subscribe to the websocket for the stream
  private def subscribeToStream(event: dom.Event): Unit = {
    // Close previous web socket, which unsubscribes to the previous stream
    currentWebSocket.foreach(_.close())

    getSelectedStream.foreach { stream =>
      println(s"Subscribe to stream: $stream")
      val ws = new WebSocket(subscribeUri(stream))
      currentWebSocket = Some(ws)
      ws.binaryType = "arraybuffer"
      ws.onopen = { _: Event ⇒
        println(s"Opened websocket for stream $stream")
      }
      ws.onerror = { event: Event ⇒
        println(s"Error for stream $stream websocket: $event")
      }
      ws.onmessage = { event: MessageEvent ⇒
        val arrayBuffer = event.data.asInstanceOf[ArrayBuffer]
        // End marker is a message with one byte ("\n")
        if (arrayBuffer.byteLength == 1) {
          displayImage()
        } else {
          currentImageData =  new Uint8Array(arrayBuffer) :: currentImageData
        }
      }
      ws.onclose = { event: Event ⇒
        println(s"Websocket closed for stream $stream")
      }
    }
  }


  def init(): Unit = {
    import scalatags.JsDom.all._
    println("Starting 'vbds-scala-js'...")

    //    val streamField = input(`type` := "text").render
    //    val subscribeButton = button(`type` := "submit", onclick := subscribeToStream _)("Subscribe").render

    updateStreamsList()

    val layout = div(
      p("VBDS Test"),
      p("Stream name: ", streamsItem)
    ).render

    dom.document.body.appendChild(layout.render)
  }

}

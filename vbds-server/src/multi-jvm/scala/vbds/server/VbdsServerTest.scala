package vbds.server

import java.io.{BufferedOutputStream, File, FileOutputStream}

import akka.remote.testkit.MultiNodeConfig
import vbds.client.VbdsClient
import vbds.server.app.VbdsServer

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.StatusCodes
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll
import vbds.client.WebSocketActor.ReceivedFile

// Tests with multiple servers, publishers and subscribers

// Note: To test with remote hosts, set multiNodeHosts environment variable to comma separated list of hosts.
// For example: setenv multiNodeHosts "192.168.178.77,username@192.168.178.36"
// and run: sbt multiNodeTest
//
// To test locally on different JVMs, run: sbt multi-jvm:test

object VbdsServerTestConfig extends MultiNodeConfig {
  val server1     = role("server1")
  val server2     = role("server2")
  val subscriber1 = role("subscriber1")
  val subscriber2 = role("subscriber2")
  val publisher1  = role("publisher1")
  //  val publisher2 = role("publisher2")

  commonConfig(ConfigFactory.parseString("""
      | akka.loglevel = INFO
      | akka.log-dead-letters-during-shutdown = off
      | akka.testconductor.barrier-timeout = 30m
    """))

}

class VbdsServerSpecMultiJvmServer1 extends VbdsServerTest("server1")

class VbdsServerSpecMultiJvmServer2 extends VbdsServerTest("server2")

class VbdsServerSpecMultiJvmSubscriber1 extends VbdsServerTest("subscriber1")

class VbdsServerSpecMultiJvmSubscriber2 extends VbdsServerTest("subscriber2")

class VbdsServerSpecMultiJvmPublisher1 extends VbdsServerTest("publisher1")

//class VbdsServerSpecMultiJvmPublisher2 extends VbdsServerTest

object VbdsServerTest {
  val seedPort        = 8888
  val server1HttpPort = 7777
  val server2HttpPort = server1HttpPort + 1
  val streamName = "WFS1-RAW"
  val testFileName = "vbdsTestFile"

  // --- Edit this ---
//  val testFileSizeBytes = 640 * 1000 * 1000 // 640 mb
//  val testFileSizeBytes = 1000 * 1000 * 1000 // 1gb (XXX timed out)
//  val testFileSizeBytes =   75 * 1000 * 1000 // 75mb
//  val testFileSizeBytes = 256*256*2
  val testFileSizeBytes = 48*48*2
  val numFilesToPublish = 50000
  val printInterval     = 10000
  // ---

  val testFileSizeMb    = testFileSizeBytes/1000000.0

  val shortTimeout = 60.seconds
  val longTimeout  = 10.hours // in case you want to test with lots of files...

  // Simulate a slow publisher/subscriber (XXX Not sure simulated slow subscriber is working correctly)
  //  val publisherDelay = 100.millis
  //  val subscriber1Delay = 50.millis
  //  val subscriber2Delay = 35.millis
  val publisherDelay   = Duration.Zero
  val subscriber1Delay = Duration.Zero
  val subscriber2Delay = Duration.Zero

  // If true, compare files to make sure the file was transferred correctly
  val doCompareFiles = false

  val testFile = makeFile(testFileSizeBytes, testFileName)
  testFile.deleteOnExit()

  // Time at start of each set of test files (print interval)
  var startTime: Long = System.currentTimeMillis()

  private def getTempDir(name: String): File = {
    val dir = new File(s"${System.getProperty("java.io.tmpdir")}/$name")
    dir.mkdir()
    dir
  }

  // Called when a file is received by the named subscriber.
  // Checks the file contents (if doCompareFiles is true) and prints statistics when done.
  // Received (temp) files are deleted.
  private def receiveFile(name: String,
                          r: ReceivedFile,
                          promise: Promise[ReceivedFile],
                          delay: FiniteDuration): Unit = {

    def printStats() = {
      val testSecs = (System.currentTimeMillis() - startTime) / 1000.0
      val secsPerFile = testSecs / printInterval
      val mbPerSec = (testFileSizeMb * printInterval) / testSecs
      val hz = 1.0 / secsPerFile
      println(
        f"$name: ${r.count}: Received $printInterval $testFileSizeBytes byte files in $testSecs seconds ($secsPerFile%1.4f secs per file, $hz%1.4f hz, $mbPerSec%1.4f mb/sec)"
      )
      startTime = System.currentTimeMillis()
    }

    if (!doCompareFiles || FileUtils.contentEquals(r.path.toFile, testFile)) {
      if (r.count >= numFilesToPublish) {
        printStats()
        promise.success(r)
      } else {
        if (r.count % printInterval == 0) printStats()
        if (delay != Duration.Zero) Thread.sleep(delay.toMillis)
      }
    } else {
      println(s"${r.path} and $testFile differ")
      promise.failure(new RuntimeException(s"${r.path} and $testFile differ"))
    }
    r.path.toFile.delete()
  }

  // Returns a queue for the named subscriber that receives the files via websocket and verifies
  // that the data is correct (if doCompareFiles is true).
  def makeQueue(name: String, promise: Promise[ReceivedFile], log: LoggingAdapter, delay: FiniteDuration)(
      implicit mat: Materializer
  ): SourceQueueWithComplete[ReceivedFile] = {
    println(s"$name: Started timing")
    Source
      .queue[ReceivedFile](20, OverflowStrategy.backpressure)
      .map(receiveFile(name, _, promise, delay))
      .to(Sink.ignore)
      .run()
  }

  // Make a temp file with numBytes bytes of data and the given base name
  def makeFile(numBytes: Int, name: String): File = {
    val file = new File(s"${getTempDir("vbds")}/$name")
    val os   = new BufferedOutputStream(new FileOutputStream(file))
    (0 to numBytes).foreach(i => os.write(i))
    os.close()
    file
  }

  implicit class RichFuture[T](val f: Future[T]) extends AnyVal {
    def await(timeout: FiniteDuration): T = Await.result(f, timeout)
  }
}

class VbdsServerTest(name: String)
    extends MultiNodeSpec(VbdsServerTestConfig)
    with STMultiNodeSpec
    with ImplicitSender
    with BeforeAndAfterAll {

  import VbdsServerTestConfig._
  import VbdsServerTest._

  def initialParticipants = roles.size

  "A VbdsServerTest" must {

    "wait for all server nodes to enter a barrier" in {
      enterBarrier("startup")
    }

    "Allow creating a stream, subscribing and publishing to a stream" in {
      runOn(server1) {
        import system.dispatcher
        val host = system.settings.config.getString("multinode.host")
        println(s"server1 (seed node) is running on $host")

        // Start the first server (the seed node)
        val (server, bindingF) = VbdsServer.start(
          host,
          server1HttpPort,
          host,
          seedPort,
          s"$host:$seedPort"
        )
        expectNoMessage(2.seconds)
        enterBarrier("deployed")
        println("XXX server1: streamCreated")
        enterBarrier("streamCreated")
        enterBarrier("subscribedToStream")
        within(longTimeout) {
          enterBarrier("receivedFiles")
          println("server1: enterBarrier receivedFiles")
//          bindingF.foreach(server.stop)
        }
      }

      runOn(server2) {
        import system.dispatcher
        val host       = system.settings.config.getString("multinode.host")
        val serverHost = system.settings.config.getString("multinode.server-host")
        println(s"server2 is running on $host (seed node is $serverHost)")

        // Start a second server
        val (server, bindingF) = VbdsServer.start(
          host,
          server2HttpPort,
          host,
          0,
          s"$serverHost:$seedPort"
        )

        expectNoMessage(2.seconds)
        enterBarrier("deployed")
        println("XXX server2: streamCreated")
        enterBarrier("streamCreated")
        enterBarrier("subscribedToStream")
        within(longTimeout) {
          enterBarrier("receivedFiles")
          println("server2: enterBarrier receivedFiles")
//          bindingF.foreach(server.stop)
        }
      }

      runOn(subscriber1) {
        val host = system.settings.config.getString("multinode.host")
        println(s"subscriber1 is running on $host")
        implicit val materializer = ActorMaterializer()
        enterBarrier("deployed")
        val client = new VbdsClient("subscriber1", host, server1HttpPort)
        println("XXX subscriber1: streamCreated")
        enterBarrier("streamCreated")
        val promise = Promise[ReceivedFile]
        val queue   = makeQueue("subscriber1", promise, log, subscriber1Delay)
        val subscription = client.subscribe(streamName, getTempDir("subscriber1"), queue, doCompareFiles)
        val httpResponse = subscription.httpResponse.await(shortTimeout)
        assert(httpResponse.status == StatusCodes.SwitchingProtocols)
        enterBarrier("subscribedToStream")
        promise.future.await(longTimeout)
//        subscription.unsubscribe()
        within(longTimeout) {
          enterBarrier("receivedFiles")
          println("subscriber1: enterBarrier receivedFiles")
        }
      }

      runOn(subscriber2) {
        val host = system.settings.config.getString("multinode.host")
        println(s"subscriber2 is running on $host")
        implicit val materializer = ActorMaterializer()
        enterBarrier("deployed")
        val client = new VbdsClient("subscriber2", host, server2HttpPort)
        println("XXX subscriber2: streamCreated")
        enterBarrier("streamCreated")
        val promise = Promise[ReceivedFile]
        val queue   = makeQueue("subscriber2", promise, log, subscriber2Delay)
        val subscription = client.subscribe(streamName, getTempDir("subscriber2"), queue, doCompareFiles)
        val httpResponse = subscription.httpResponse.await(shortTimeout)
        assert(httpResponse.status == StatusCodes.SwitchingProtocols)
        enterBarrier("subscribedToStream")
        promise.future.await(longTimeout)
//        subscription.unsubscribe()
        within(longTimeout) {
          enterBarrier("receivedFiles")
          println("subscriber2: enterBarrier receivedFiles")
        }
      }

      runOn(publisher1) {
        val host = system.settings.config.getString("multinode.host")
        println(s"publisher1 is running on $host")
        implicit val materializer = ActorMaterializer()
        enterBarrier("deployed")
        val client         = new VbdsClient("publisher1", host, server1HttpPort)
        val createResponse = client.createStream(streamName).await(shortTimeout)
        assert(createResponse.status == StatusCodes.OK)
        println("XXX publisher1: streamCreated")
        enterBarrier("streamCreated")
        enterBarrier("subscribedToStream")
        // Note: +5 to make sure test completes
        Source(1 to numFilesToPublish+5).runForeach { _ =>
          client.publish(streamName, testFile, publisherDelay).await(shortTimeout)
        }
        within(longTimeout) {
          enterBarrier("receivedFiles")
          println("publisher1: enterBarrier receivedFiles")
        }
      }

      enterBarrier("finished")
    }
  }
}

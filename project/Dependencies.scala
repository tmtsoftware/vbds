import sbt._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

object Dependencies {

  val vbdsServer = Seq(
    Akka.`akka-stream-typed`,
    Akka.`akka-actor-typed`,
    Akka.`akka-cluster-typed`,
    Akka.`akka-slf4j`,
    Akka.`akka-remote`,
    AkkaHttp.`akka-http`,
    AkkaHttp.`akka-http-spray-json`,
    Libs.`scopt`,
    Libs.`boopickle`,
    Chill.`chill-akka`,
    Libs.`logback-classic`,

    Libs.`commons-io` % Test,
    Libs.`scalatest` % Test,
    AkkaHttp.`akka-http-testkit` % Test,
    Akka.`akka-multi-node-testkit` % Test
  )

  val vbdsClient = Seq(
    Akka.`akka-stream`,
    Akka.`akka-slf4j`,
    AkkaHttp.`akka-http`,
    Libs.`scopt`,
    Libs.`logback-classic`,

    Libs.`scalatest` % Test,
    AkkaHttp.`akka-http-testkit` % Test
  )

  // ScalaJS web client scala dependencies
  val webClient = Def.setting(Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.9.7",
    "com.lihaoyi" %%% "scalatags" % "0.6.8",
    "org.querki" %%% "jquery-facade" % "1.2",
    "com.github.japgolly.scalacss" %%% "core" % "0.5.6",
    "com.github.japgolly.scalacss" %%% "ext-scalatags" % "0.5.6",
    "com.lihaoyi" %%% "upickle" % "0.7.4",
    "org.scalatest" %%% "scalatest" % "3.0.7" % "test"
  ))

}

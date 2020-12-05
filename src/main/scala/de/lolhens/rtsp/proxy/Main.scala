package de.lolhens.rtsp.proxy

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ExitCode}
import fs2._
import fs2.io.tcp.SocketGroup
import monix.eval.{Task, TaskApp}
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import scala.jdk.CollectionConverters._

object Main extends TaskApp {
  private val logger = LoggerFactory.getLogger(Main.getClass)

  override def run(args: List[String]): Task[ExitCode] = {
    val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

    val destinationSocketAddress = env.get("DESTINATION_ADDRESS").map {
      case s"$host:$port" if port.toIntOption.isDefined => new InetSocketAddress(host, port.toInt)
    }.getOrElse(throw new RuntimeException("DESTINATION_ADDRESS is missing!"))

    val listenSocketAddress = env.get("LISTEN_ADDRESS").map {
      case s"$host:$port" if port.toIntOption.isDefined => new InetSocketAddress(host, port.toInt)
    }.getOrElse(new InetSocketAddress("0.0.0.0", destinationSocketAddress.getPort))

    val rtspClientHost = env.get("RTSP_CLIENT_HOST")
    val rtspServerHost = env.get("RTSP_SERVER_HOST")

    val debug = env.get("DEBUG").exists(_.toBoolean)

    Blocker[Task].use { blocker =>
      SocketGroup[Task](blocker).use { socketGroup =>
        socketGroup.serverResource[Task](listenSocketAddress).use {
          case (serverSocket, clientResources) =>
            logger.info("Server started on " + serverSocket)

            clientResources.map { clientResource =>
              Stream.resource(clientResource).flatMap { proxyClient =>
                for {
                  proxyClientRemoteAddress <- Stream.eval(proxyClient.remoteAddress).map {
                    case inetSocketAddress: InetSocketAddress => inetSocketAddress
                  }
                  _ = logger.info("Client connected from " + proxyClientRemoteAddress)
                  destinationClient <- Stream.resource(socketGroup.client(destinationSocketAddress))
                  destinationClientRemoteAddress <- Stream.eval(destinationClient.remoteAddress).map {
                    case inetSocketAddress: InetSocketAddress => inetSocketAddress
                  }
                  _ = logger.info("Proxy connection established to " + destinationClientRemoteAddress)
                  setupReadRef <- Stream.eval(Ref.of(false))
                  setupSentRef <- Stream.eval(Ref.of(false))
                  _ <-
                    proxyClient.reads(8192)
                      .through(text.utf8Decode)
                      .through(text.lines)
                      .evalMap { line =>
                        if (line.startsWith("SETUP "))
                          for {
                            _ <- setupReadRef.set(true)
                            _ <- setupSentRef.set(true)
                          } yield
                            line
                        else
                          for {
                            setupRead <- setupReadRef.get
                            line <-
                              if (setupRead && line.startsWith("Transport:")) {
                                for {
                                  _ <- setupReadRef.set(false)
                                } yield {
                                  val host = rtspClientHost.getOrElse(proxyClientRemoteAddress.getHostString)
                                  val s"Transport:$transport" = line
                                  val params = transport.trim.split(";")
                                  "Transport: " +
                                    (params.filterNot(_.startsWith("destination=")) :+ s"destination=$host")
                                      .mkString(";")
                                }
                              } else
                                Task.now(line)
                          } yield
                            line
                      }
                      .debug(logger = if (debug) string => println("> " + string) else _ => ())
                      .interleave(Stream.constant("\r\n"))
                      .through(text.utf8Encode)
                      .through(destinationClient.writes())
                      .concurrently(
                        destinationClient.reads(8192)
                          .through(text.utf8Decode)
                          .through(text.lines)
                          .evalMap { line =>
                            for {
                              setupSent <- setupSentRef.get
                              line <-
                                if (setupSent && line.startsWith("Transport:")) {
                                  for {
                                    _ <- setupSentRef.set(false)
                                  } yield {
                                    val host = rtspServerHost.getOrElse(destinationClientRemoteAddress.getHostString)
                                    val s"Transport:$transport" = line
                                    val params = transport.trim.split(";")
                                    "Transport: " +
                                      (params.filterNot(_.startsWith("source=")) :+ s"source=$host")
                                        .mkString(";")
                                  }
                                } else
                                  Task.now(line)
                            } yield
                              line
                          }
                          .debug(logger = if (debug) string => println("< " + string) else _ => ())
                          .interleave(Stream.constant("\r\n"))
                          .through(text.utf8Encode)
                          .through(proxyClient.writes())
                      )
                } yield
                  ()
              }.handleErrorWith { throwable =>
                throwable.printStackTrace()
                Stream(())
              }
            }.parJoin(100).compile.drain
        }
      }
    }.as(ExitCode.Success)
  }
}

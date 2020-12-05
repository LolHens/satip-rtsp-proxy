package de.lolhens.rtsp.proxy

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

    Blocker[Task].use { blocker =>
      SocketGroup[Task](blocker).use { socketGroup =>
        socketGroup.serverResource[Task](listenSocketAddress).use {
          case (serverSocket, clientResources) =>
            (Stream.eval(Task(logger.info("Server started on " + serverSocket))) >>
              clientResources.map { clientResource =>
                Stream.resource(clientResource).flatMap { proxyClient =>
                  Stream.eval(proxyClient.remoteAddress).map(remoteAddress => logger.info("Client connected from " + remoteAddress)) >>
                    Stream.resource(socketGroup.client(destinationSocketAddress)).flatMap { destinationClient =>
                      Stream.eval(destinationClient.remoteAddress).map(remoteAddress => logger.info("Proxy connection established to " + remoteAddress)) >>
                        proxyClient.reads(8192)
                          .through(destinationClient.writes())
                          .concurrently(
                            destinationClient.reads(8192)
                              //.through(text.utf8Decode)
                              //.through(text.lines)
                              //.interleave(Stream.constant("\n"))
                              //.through(text.utf8Encode)
                              .through(proxyClient.writes())
                          )
                    }
                }.handleErrorWith { throwable =>
                  throwable.printStackTrace()
                  Stream(())
                }
              }.parJoin(100)).compile.drain
        }
      }
    }.as(ExitCode.Success)
  }
}

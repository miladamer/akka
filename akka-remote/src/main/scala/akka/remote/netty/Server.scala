/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import scala.Option.option2Iterable
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.ChannelHandler.Sharable
import org.jboss.netty.channel.group.ChannelGroup
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel.{ StaticChannelPipeline, SimpleChannelUpstreamHandler, MessageEvent, ExceptionEvent, ChannelStateEvent, ChannelPipelineFactory, ChannelPipeline, ChannelHandlerContext, ChannelHandler, Channel }
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.execution.ExecutionHandler
import akka.event.Logging
import akka.remote.RemoteProtocol.{ RemoteControlProtocol, CommandType, AkkaRemoteProtocol }
import akka.remote.{ RemoteServerStarted, RemoteServerShutdown, RemoteServerError, RemoteServerClientDisconnected, RemoteServerClientConnected, RemoteServerClientClosed, RemoteProtocol, RemoteMessage }
import akka.actor.Address
import java.net.InetAddress
import akka.actor.ActorSystemImpl
import org.jboss.netty.channel.ChannelLocal

class NettyRemoteServer(
  val netty: NettyRemoteTransport,
  val loader: Option[ClassLoader]) {

  import netty.settings

  val ip = InetAddress.getByName(settings.Hostname)

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool(netty.threadFactory),
    Executors.newCachedThreadPool(netty.threadFactory))

  private val bootstrap = new ServerBootstrap(factory)

  private val executionHandler = new ExecutionHandler(netty.executor)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(openChannels, executionHandler, loader, netty)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", settings.Backlog)
  bootstrap.setOption("tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("reuseAddress", true)

  def start(): Unit = {
    openChannels.add(bootstrap.bind(new InetSocketAddress(ip, settings.Port)))
    netty.notifyListeners(RemoteServerStarted(netty))
  }

  def shutdown() {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        b.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(settings.systemName)
          .setHostname(settings.Hostname)
          .setPort(settings.Port)
          .build)
        if (settings.SecureCookie.nonEmpty)
          b.setCookie(settings.SecureCookie.get)
        b.build
      }
      openChannels.write(netty.createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      netty.notifyListeners(RemoteServerShutdown(netty))
    } catch {
      case e: Exception ⇒ netty.notifyListeners(RemoteServerError(e, netty))
    }
  }
}

class RemoteServerPipelineFactory(
  val openChannels: ChannelGroup,
  val executionHandler: ExecutionHandler,
  val loader: Option[ClassLoader],
  val netty: NettyRemoteTransport) extends ChannelPipelineFactory {

  import netty.settings

  def getPipeline: ChannelPipeline = {
    val lenDec = new LengthFieldBasedFrameDecoder(settings.MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val messageDec = new RemoteMessageDecoder
    val messageEnc = new RemoteMessageEncoder(netty)

    val authenticator = if (settings.RequireCookie) new RemoteServerAuthenticationHandler(settings.SecureCookie) :: Nil else Nil
    val remoteServer = new RemoteServerHandler(openChannels, loader, netty)
    val stages: List[ChannelHandler] = lenDec :: messageDec :: lenPrep :: messageEnc :: executionHandler :: authenticator ::: remoteServer :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

@ChannelHandler.Sharable
class RemoteServerAuthenticationHandler(secureCookie: Option[String]) extends SimpleChannelUpstreamHandler {
  val authenticated = new AnyRef

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = secureCookie match {
    case None ⇒ ctx.sendUpstream(event)
    case Some(cookie) ⇒
      ctx.getAttachment match {
        case `authenticated` ⇒ ctx.sendUpstream(event)
        case null ⇒ event.getMessage match {
          case remoteProtocol: AkkaRemoteProtocol if remoteProtocol.hasInstruction ⇒
            val instruction = remoteProtocol.getInstruction
            instruction.getCookie match {
              case `cookie` ⇒
                ctx.setAttachment(authenticated)
                ctx.sendUpstream(event)
              case _ ⇒
                throw new SecurityException(
                  "The remote client [" + ctx.getChannel.getRemoteAddress + "] secure cookie is not the same as remote server secure cookie")
            }
          case _ ⇒
            throw new SecurityException("The remote client [" + ctx.getChannel.getRemoteAddress + "] is not authorized!")
        }
      }
  }
}

object ChannelLocalSystem extends ChannelLocal[ActorSystemImpl] {
  override def initialValue(ch: Channel): ActorSystemImpl = null
}

@ChannelHandler.Sharable
class RemoteServerHandler(
  val openChannels: ChannelGroup,
  val applicationLoader: Option[ClassLoader],
  val netty: NettyRemoteTransport) extends SimpleChannelUpstreamHandler {

  import netty.settings

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx.getChannel)
    netty.notifyListeners(RemoteServerClientConnected(netty, clientAddress))
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx.getChannel)
    netty.notifyListeners(RemoteServerClientDisconnected(netty, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = getClientAddress(ctx.getChannel) match {
    case s @ Some(address) ⇒
      if (settings.UsePassiveConnections)
        netty.unbindClient(address)
      netty.notifyListeners(RemoteServerClientClosed(netty, s))
    case None ⇒
      netty.notifyListeners(RemoteServerClientClosed(netty, None))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = try {
    event.getMessage match {
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒
        netty.receiveMessage(new RemoteMessage(remote.getMessage, netty.system, applicationLoader))

      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒
        val instruction = remote.getInstruction
        instruction.getCommandType match {
          case CommandType.CONNECT if settings.UsePassiveConnections ⇒
            val origin = instruction.getOrigin
            val inbound = Address("akka", origin.getSystem, Some(origin.getHostname), Some(origin.getPort))
            val client = new PassiveRemoteClient(event.getChannel, netty, inbound)
            netty.bindClient(inbound, client)
          case CommandType.SHUTDOWN ⇒ //Will be unbound in channelClosed
          case _                    ⇒ //Unknown command
        }
      case _ ⇒ //ignore
    }
  } catch {
    case e: Exception ⇒ netty.notifyListeners(RemoteServerError(e, netty))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    netty.notifyListeners(RemoteServerError(event.getCause, netty))
    event.getChannel.close()
  }

  private def getClientAddress(c: Channel): Option[Address] =
    c.getRemoteAddress match {
      case inet: InetSocketAddress ⇒ Some(Address("akka", "unknown(yet)", Some(inet.getAddress.toString), Some(inet.getPort)))
      case _                       ⇒ None
    }
}


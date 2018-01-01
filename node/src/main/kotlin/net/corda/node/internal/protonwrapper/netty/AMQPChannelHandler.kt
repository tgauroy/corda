package net.corda.node.internal.protonwrapper.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.ReferenceCountUtil
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.debug
import net.corda.node.internal.protonwrapper.engine.EventProcessor
import net.corda.node.internal.protonwrapper.messages.ReceivedMessage
import net.corda.node.internal.protonwrapper.messages.impl.ReceivedMessageImpl
import net.corda.node.internal.protonwrapper.messages.impl.SendableMessageImpl
import org.apache.qpid.proton.engine.ProtonJTransport
import org.apache.qpid.proton.engine.Transport
import org.apache.qpid.proton.engine.impl.ProtocolTracer
import org.apache.qpid.proton.framing.TransportFrame
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.security.cert.X509Certificate

/**
 *  An instance of AMQPChannelHandler sits inside the netty pipeline and controls the socket level lifecycle.
 *  It also add some extra checks to the SSL handshake to support our non-standard certificate checks of legal identity.
 *  When a valid SSL connections is made then it initialises a proton-j engine instance to handle the protocol layer.
 */
internal class AMQPChannelHandler(private val serverMode: Boolean,
                                  private val allowedRemoteLegalNames: Set<CordaX500Name>?,
                                  private val userName: String?,
                                  private val password: String?,
                                  private val trace: Boolean,
                                  private val onOpen: (Pair<SocketChannel, ConnectionChange>) -> Unit,
                                  private val onClose: (Pair<SocketChannel, ConnectionChange>) -> Unit,
                                  private val onReceive: (ReceivedMessage) -> Unit) : ChannelDuplexHandler() {
    private val log = LoggerFactory.getLogger(allowedRemoteLegalNames?.firstOrNull()?.toString() ?: "AMQPChannelHandler")
    private lateinit var remoteAddress: InetSocketAddress
    private lateinit var localCert: X509Certificate
    private lateinit var remoteCert: X509Certificate
    private var eventProcessor: EventProcessor? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val ch = ctx.channel()
        remoteAddress = ch.remoteAddress() as InetSocketAddress
        val localAddress = ch.localAddress() as InetSocketAddress
        log.info("New client connection ${ch.id()} from $remoteAddress to $localAddress")
    }

    private fun createAMQPEngine(ctx: ChannelHandlerContext) {
        val ch = ctx.channel()
        eventProcessor = EventProcessor(ch, serverMode, localCert.subjectX500Principal.toString(), remoteCert.subjectX500Principal.toString(), userName, password)
        val connection = eventProcessor!!.connection
        val transport = connection.transport as ProtonJTransport
        if (trace) {
            transport.protocolTracer = object : ProtocolTracer {
                override fun sentFrame(transportFrame: TransportFrame) {
                    log.info("${transportFrame.body}")
                }

                override fun receivedFrame(transportFrame: TransportFrame) {
                    log.info("${transportFrame.body}")
                }
            }
        }
        ctx.fireChannelActive()
        eventProcessor!!.processEventsAsync()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val ch = ctx.channel()
        log.info("Closed client connection ${ch.id()} from $remoteAddress to ${ch.localAddress()}")
        onClose(Pair(ch as SocketChannel, ConnectionChange(remoteAddress, null, false)))
        eventProcessor?.close()
        ctx.fireChannelInactive()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is SslHandshakeCompletionEvent) {
            if (evt.isSuccess) {
                val sslHandler = ctx.pipeline().get(SslHandler::class.java)
                localCert = sslHandler.engine().session.localCertificates[0] as X509Certificate
                remoteCert = sslHandler.engine().session.peerCertificates[0] as X509Certificate
                try {
                    val remoteX500Name = CordaX500Name.build(remoteCert.subjectX500Principal)
                    require(allowedRemoteLegalNames == null || remoteX500Name in allowedRemoteLegalNames)
                    log.info("handshake completed subject: $remoteX500Name")
                } catch (ex: IllegalArgumentException) {
                    log.error("Invalid certificate subject", ex)
                    ctx.close()
                    return
                }
                createAMQPEngine(ctx)
                onOpen(Pair(ctx.channel() as SocketChannel, ConnectionChange(remoteAddress, remoteCert, true)))
            } else {
                log.error("Handshake failure $evt")
                ctx.close()
            }
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            log.debug { "Received $msg" }
            if (msg is ByteBuf) {
                eventProcessor!!.transportProcessInput(msg)
            }
        } finally {
            ReferenceCountUtil.release(msg)
        }
        eventProcessor!!.processEventsAsync()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        try {
            try {
                log.debug { "Sent $msg" }
                when (msg) {
                // Transfers application packet into the AMQP engine.
                    is SendableMessageImpl -> {
                        val inetAddress = InetSocketAddress(msg.destinationLink.host, msg.destinationLink.port)
                        require(inetAddress == remoteAddress) {
                            "Message for incorrect endpoint"
                        }
                        require(CordaX500Name.parse(msg.destinationLegalName) == CordaX500Name.build(remoteCert.subjectX500Principal)) {
                            "Message for incorrect legal identity"
                        }
                        log.debug { "channel write ${msg.applicationProperties["_AMQ_DUPL_ID"]}" }
                        eventProcessor!!.transportWriteMessage(msg)
                    }
                // A received AMQP packet has been completed and this self-posted packet will be signalled out to the
                // external application.
                    is ReceivedMessage -> {
                        onReceive(msg)
                    }
                // A general self-posted event that triggers creation of AMQP frames when required.
                    is Transport -> {
                        eventProcessor!!.transportProcessOutput(ctx)
                    }
                // A self-posted event that forwards status updates for delivered packets to the application.
                    is ReceivedMessageImpl.MessageCompleter -> {
                        eventProcessor!!.complete(msg)
                    }
                }
            } catch (ex: Exception) {
                log.error("Error in AMQP write processing", ex)
                throw ex
            }
        } finally {
            ReferenceCountUtil.release(msg)
        }
        eventProcessor!!.processEventsAsync()
    }
}
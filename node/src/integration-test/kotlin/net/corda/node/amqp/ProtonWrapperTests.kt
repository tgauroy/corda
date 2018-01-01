package net.corda.node.amqp

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.div
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.toFuture
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.protonwrapper.messages.MessageStatus
import net.corda.node.internal.protonwrapper.netty.AMQPClient
import net.corda.node.internal.protonwrapper.netty.AMQPServer
import net.corda.node.internal.security.RPCSecurityManager
import net.corda.node.services.config.CertChainPolicyConfig
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.messaging.ArtemisMessagingClient
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.PEER_USER
import net.corda.nodeapi.internal.crypto.loadKeyStore
import net.corda.testing.*
import net.corda.testing.internal.rigorousMock
import org.apache.activemq.artemis.api.core.RoutingType
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rx.Observable.never
import kotlin.test.assertEquals

class ProtonWrapperTests {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val serverPort = freePort()
    private val serverPort2 = freePort()
    private val artemisPort = freePort()

    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Test
    fun `Simple AMPQ Client to Server`() {
        val amqpServer = createServer(serverPort)
        amqpServer.use {
            amqpServer.start()
            val receiveSubs = amqpServer.onReceive.subscribe {
                assertEquals(BOB_NAME.toString(), it.sourceLegalName)
                assertEquals("p2p.inbound", it.topic)
                assertEquals("Test", String(it.payload))
                it.complete(true)
            }
            val amqpClient = createClient()
            amqpClient.use {
                val serverConnected = amqpServer.onConnection.toFuture()
                val clientConnected = amqpClient.onConnection.toFuture()
                amqpClient.start()
                val serverConnect = serverConnected.get()
                assertEquals(true, serverConnect.connected)
                assertEquals(BOB_NAME, CordaX500Name.build(serverConnect.remoteCert!!.subjectX500Principal))
                val clientConnect = clientConnected.get()
                assertEquals(true, clientConnect.connected)
                assertEquals(ALICE_NAME, CordaX500Name.build(clientConnect.remoteCert!!.subjectX500Principal))
                val msg = amqpClient.createMessage("Test".toByteArray(),
                        "p2p.inbound",
                        ALICE_NAME.toString(),
                        emptyMap())
                amqpClient.write(msg)
                assertEquals(MessageStatus.Acknowledged, msg.onComplete.get())
                receiveSubs.unsubscribe()
            }
        }
    }

    @Test
    fun `AMPQ Client refuses to connect to unexpected server`() {
        val amqpServer = createServer(serverPort, CordaX500Name("Rogue 1", "London", "GB"))
        amqpServer.use {
            amqpServer.start()
            val amqpClient = createClient()
            amqpClient.use {
                val clientConnected = amqpClient.onConnection.toFuture()
                amqpClient.start()
                val clientConnect = clientConnected.get()
                assertEquals(false, clientConnect.connected)
            }
        }
    }

    @Test
    fun `Client Failover for multiple IP`() {
        val amqpServer = createServer(serverPort)
        val amqpServer2 = createServer(serverPort2)
        val amqpClient = createClient()
        try {
            val serverConnected = amqpServer.onConnection.toFuture()
            val serverConnected2 = amqpServer2.onConnection.toFuture()
            val clientConnected = amqpClient.onConnection.toBlocking().iterator
            amqpServer.start()
            amqpClient.start()
            val serverConn1 = serverConnected.get()
            assertEquals(true, serverConn1.connected)
            assertEquals(BOB_NAME, CordaX500Name.build(serverConn1.remoteCert!!.subjectX500Principal))
            val connState1 = clientConnected.next()
            assertEquals(true, connState1.connected)
            assertEquals(ALICE_NAME, CordaX500Name.build(connState1.remoteCert!!.subjectX500Principal))
            assertEquals(serverPort, connState1.remoteAddress.port)

            // Fail over
            amqpServer2.start()
            amqpServer.stop()
            val connState2 = clientConnected.next()
            assertEquals(false, connState2.connected)
            assertEquals(serverPort, connState2.remoteAddress.port)
            val serverConn2 = serverConnected2.get()
            assertEquals(true, serverConn2.connected)
            assertEquals(BOB_NAME, CordaX500Name.build(serverConn2.remoteCert!!.subjectX500Principal))
            val connState3 = clientConnected.next()
            assertEquals(true, connState3.connected)
            assertEquals(ALICE_NAME, CordaX500Name.build(connState3.remoteCert!!.subjectX500Principal))
            assertEquals(serverPort2, connState3.remoteAddress.port)

            // Fail back
            amqpServer.start()
            amqpServer2.stop()
            val connState4 = clientConnected.next()
            assertEquals(false, connState4.connected)
            assertEquals(serverPort2, connState4.remoteAddress.port)
            val serverConn3 = serverConnected.get()
            assertEquals(true, serverConn3.connected)
            assertEquals(BOB_NAME, CordaX500Name.build(serverConn3.remoteCert!!.subjectX500Principal))
            val connState5 = clientConnected.next()
            assertEquals(true, connState5.connected)
            assertEquals(ALICE_NAME, CordaX500Name.build(connState5.remoteCert!!.subjectX500Principal))
            assertEquals(serverPort, connState5.remoteAddress.port)
        } finally {
            amqpClient.close()
            amqpServer.close()
            amqpServer2.close()
        }
    }

    @Test
    fun `Send a message from AMQP to Artemis inbox`() {
        val (server, artemisClient) = createArtemisServerAndClient()
        val amqpClient = createClient()
        val clientConnected = amqpClient.onConnection.toFuture()
        amqpClient.start()
        assertEquals(true, clientConnected.get().connected)
        assertEquals(CHARLIE_NAME, CordaX500Name.build(clientConnected.get().remoteCert!!.subjectX500Principal))
        val artemis = artemisClient.started!!
        val sendAddress = "p2p.inbound"
        artemis.session.createQueue(sendAddress, RoutingType.MULTICAST, "queue", true)
        val consumer = artemis.session.createConsumer("queue")
        val testData = "Test".toByteArray()
        val testProperty = mutableMapOf<Any?, Any?>()
        testProperty["TestProp"] = "1"
        val message = amqpClient.createMessage(testData, sendAddress, CHARLIE_NAME.toString(), testProperty)
        amqpClient.write(message)
        assertEquals(MessageStatus.Acknowledged, message.onComplete.get())
        val received = consumer.receive()
        assertEquals("1", received.getStringProperty("TestProp"))
        assertArrayEquals(testData, ByteArray(received.bodySize).apply { received.bodyBuffer.readBytes(this) })
        amqpClient.stop()
        artemisClient.stop()
        server.stop()
    }

    @Test
    fun `shared AMQPClient threadpool tests`() {
        val amqpServer = createServer(serverPort)
        amqpServer.use {
            val connectionEvents = amqpServer.onConnection.toBlocking().iterator
            amqpServer.start()
            val sharedThreads = NioEventLoopGroup()
            val amqpClient1 = createSharedThreadsClient(sharedThreads, 0)
            val amqpClient2 = createSharedThreadsClient(sharedThreads, 1)
            amqpClient1.start()
            val connection1 = connectionEvents.next()
            assertEquals(true, connection1.connected)
            val connection1ID = CordaX500Name.build(connection1.remoteCert!!.subjectX500Principal)
            assertEquals("client 0", connection1ID.organisationUnit)
            val source1 = connection1.remoteAddress
            amqpClient2.start()
            val connection2 = connectionEvents.next()
            assertEquals(true, connection2.connected)
            val connection2ID = CordaX500Name.build(connection2.remoteCert!!.subjectX500Principal)
            assertEquals("client 1", connection2ID.organisationUnit)
            val source2 = connection2.remoteAddress
            // Stopping one shouldn't disconnect the other
            amqpClient1.stop()
            val connection3 = connectionEvents.next()
            assertEquals(false, connection3.connected)
            assertEquals(source1, connection3.remoteAddress)
            assertEquals(false, amqpClient1.connected)
            assertEquals(true, amqpClient2.connected)
            // Now shutdown both
            amqpClient2.stop()
            val connection4 = connectionEvents.next()
            assertEquals(false, connection4.connected)
            assertEquals(source2, connection4.remoteAddress)
            assertEquals(false, amqpClient1.connected)
            assertEquals(false, amqpClient2.connected)
            // Now restarting one should work
            amqpClient1.start()
            val connection5 = connectionEvents.next()
            assertEquals(true, connection5.connected)
            val connection5ID = CordaX500Name.build(connection5.remoteCert!!.subjectX500Principal)
            assertEquals("client 0", connection5ID.organisationUnit)
            assertEquals(true, amqpClient1.connected)
            assertEquals(false, amqpClient2.connected)
            // Cleanup
            amqpClient1.stop()
            sharedThreads.shutdownGracefully()
            sharedThreads.terminationFuture().sync()
        }
    }

    private fun createArtemisServerAndClient(): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "artemis").whenever(it).baseDirectory
            doReturn(CHARLIE_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn("").whenever(it).exportJMXto
            doReturn(emptyList<CertChainPolicyConfig>()).whenever(it).certificateChainCheckPolicies
            doReturn(true).whenever(it).useAMQPBridges
        }
        artemisConfig.configureWithDevSSLCertificate()

        val networkMap = rigorousMock<NetworkMapCache>().also {
            doReturn(never<NetworkMapCache.MapChange>()).whenever(it).changed
        }
        val userService = rigorousMock<RPCSecurityManager>()
        val server = ArtemisMessagingServer(artemisConfig, artemisPort, null, networkMap, userService, MAX_MESSAGE_SIZE)
        val client = ArtemisMessagingClient(artemisConfig, NetworkHostAndPort("localhost", artemisPort), MAX_MESSAGE_SIZE)
        server.start()
        client.start()
        return Pair(server, client)
    }

    private fun createClient(): AMQPClient {
        val clientConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "client").whenever(it).baseDirectory
            doReturn(BOB_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
        }
        clientConfig.configureWithDevSSLCertificate()

        val clientTruststore = loadKeyStore(clientConfig.trustStoreFile, clientConfig.trustStorePassword)
        val clientKeystore = loadKeyStore(clientConfig.sslKeystore, clientConfig.keyStorePassword)
        return AMQPClient(
                listOf(NetworkHostAndPort("localhost", serverPort),
                NetworkHostAndPort("localhost", serverPort2),
                NetworkHostAndPort("localhost", artemisPort)),
                setOf(ALICE_NAME, CHARLIE_NAME),
                PEER_USER,
                PEER_USER,
                clientKeystore,
                clientConfig.keyStorePassword,
                clientTruststore, true)
    }

    private fun createSharedThreadsClient(sharedEventGroup: EventLoopGroup, id: Int): AMQPClient {
        val clientConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "client_%$id").whenever(it).baseDirectory
            doReturn(CordaX500Name(null, "client $id", "Corda", "London", null, "GB")).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
        }
        clientConfig.configureWithDevSSLCertificate()

        val clientTruststore = loadKeyStore(clientConfig.trustStoreFile, clientConfig.trustStorePassword)
        val clientKeystore = loadKeyStore(clientConfig.sslKeystore, clientConfig.keyStorePassword)
        return AMQPClient(
                listOf(NetworkHostAndPort("localhost", serverPort)),
                setOf(ALICE_NAME),
                PEER_USER,
                PEER_USER,
                clientKeystore,
                clientConfig.keyStorePassword,
                clientTruststore, true, sharedEventGroup)
    }

    private fun createServer(port: Int, name: CordaX500Name = ALICE_NAME): AMQPServer {
        val serverConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "server").whenever(it).baseDirectory
            doReturn(name).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
        }
        serverConfig.configureWithDevSSLCertificate()

        val serverTruststore = loadKeyStore(serverConfig.trustStoreFile, serverConfig.trustStorePassword)
        val serverKeystore = loadKeyStore(serverConfig.sslKeystore, serverConfig.keyStorePassword)
        return AMQPServer(
                "0.0.0.0",
                port,
                PEER_USER,
                PEER_USER,
                serverKeystore,
                serverConfig.keyStorePassword,
                serverTruststore)
    }
}

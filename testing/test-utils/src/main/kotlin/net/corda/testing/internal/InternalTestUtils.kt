package net.corda.testing.internal

import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.loggerFor
import net.corda.nodeapi.internal.createDevNodeCa
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.serialization.amqp.AMQP_ENABLED
import org.mockito.Mockito
import org.mockito.internal.stubbing.answers.ThrowsException
import java.lang.reflect.Modifier
import java.util.*
import javax.security.auth.x500.X500Principal

@Suppress("unused")
inline fun <reified T : Any> T.kryoSpecific(reason: String, function: () -> Unit) = if (!AMQP_ENABLED) {
    function()
} else {
    loggerFor<T>().info("Ignoring Kryo specific test, reason: $reason")
}

@Suppress("unused")
inline fun <reified T : Any> T.amqpSpecific(reason: String, function: () -> Unit) = if (AMQP_ENABLED) {
    function()
} else {
    loggerFor<T>().info("Ignoring AMQP specific test, reason: $reason")
}

/**
 * A method on a mock was called, but no behaviour was previously specified for that method.
 * You can use [com.nhaarman.mockito_kotlin.doReturn] or similar to specify behaviour, see Mockito documentation for details.
 */
class UndefinedMockBehaviorException(message: String) : RuntimeException(message)

inline fun <reified T : Any> rigorousMock() = rigorousMock(T::class.java)
/**
 * Create a Mockito mock that has [UndefinedMockBehaviorException] as the default behaviour of all abstract methods,
 * and [org.mockito.invocation.InvocationOnMock.callRealMethod] as the default for all concrete methods.
 * @param T the type to mock. Note if you want concrete methods of a Kotlin interface to be invoked,
 * it won't work unless you mock a (trivial) abstract implementation of that interface instead.
 */
fun <T> rigorousMock(clazz: Class<T>): T = Mockito.mock(clazz) {
    if (Modifier.isAbstract(it.method.modifiers)) {
        // Use ThrowsException to hack the stack trace, and lazily so we can customise the message:
        ThrowsException(UndefinedMockBehaviorException("Please specify what should happen when '${it.method}' is called, or don't call it. Args: ${Arrays.toString(it.arguments)}")).answer(it)
    } else {
        it.callRealMethod()
    }
}

private val defaultRootCaName = X500Principal("CN=Corda Root CA,O=R3 Ltd,L=London,C=GB")
private val defaultIntermediateCaName = X500Principal("CN=Corda Intermediate CA,O=R3 Ltd,L=London,C=GB")

/**
 * Returns a pair of [CertificateAndKeyPair]s, the first being the root CA and the second the intermediate CA.
 * @param rootCaName The subject name for the root CA cert.
 * @param intermediateCaName The subject name for the intermediate CA cert.
 */
fun createDevIntermediateCaCertPath(
        rootCaName: X500Principal = defaultRootCaName,
        intermediateCaName: X500Principal = defaultIntermediateCaName
): Pair<CertificateAndKeyPair, CertificateAndKeyPair> {
    val rootKeyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
    val rootCert = X509Utilities.createSelfSignedCACertificate(rootCaName, rootKeyPair)

    val intermediateCaKeyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
    val intermediateCaCert = X509Utilities.createCertificate(
            CertificateType.INTERMEDIATE_CA,
            rootCert,
            rootKeyPair,
            intermediateCaName,
            intermediateCaKeyPair.public)

    return Pair(
            CertificateAndKeyPair(rootCert, rootKeyPair),
            CertificateAndKeyPair(intermediateCaCert, intermediateCaKeyPair)
    )
}

/**
 * Returns a triple of [CertificateAndKeyPair]s, the first being the root CA, the second the intermediate CA and the third
 * the node CA.
 * @param legalName The subject name for the node CA cert.
 */
fun createDevNodeCaCertPath(
        legalName: CordaX500Name,
        rootCaName: X500Principal = defaultRootCaName,
        intermediateCaName: X500Principal = defaultIntermediateCaName
): Triple<CertificateAndKeyPair, CertificateAndKeyPair, CertificateAndKeyPair> {
    val (rootCa, intermediateCa) = createDevIntermediateCaCertPath(rootCaName, intermediateCaName)
    val nodeCa = createDevNodeCa(intermediateCa, legalName)
    return Triple(rootCa, intermediateCa, nodeCa)
}

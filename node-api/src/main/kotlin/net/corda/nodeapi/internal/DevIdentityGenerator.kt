package net.corda.nodeapi.internal

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.config.NodeSSLConfiguration
import net.corda.nodeapi.internal.crypto.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.cert.X509Certificate

/**
 * Contains utility methods for generating identities for a node.
 *
 * WARNING: This is not application for production use.
 */
object DevIdentityGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    // TODO These don't need to be prefixes but can be the full aliases
    // TODO Move these constants out of here as the node needs access to them
    const val NODE_IDENTITY_ALIAS_PREFIX = "identity"
    const val DISTRIBUTED_NOTARY_ALIAS_PREFIX = "distributed-notary"

    /**
     * Install a node key store for the given node directory using the given legal name and an optional root cert. If no
     * root cert is specified then the default one in certificates/cordadevcakeys.jks is used.
     */
    fun installKeyStoreWithNodeIdentity(nodeDir: Path, legalName: CordaX500Name, customRootCert: X509Certificate? = null): Party {
        val nodeSslConfig = object : NodeSSLConfiguration {
            override val baseDirectory = nodeDir
            override val keyStorePassword: String = "cordacadevpass"
            override val trustStorePassword get() = throw NotImplementedError("Not expected to be called")
        }

        // TODO The passwords for the dev key stores are spread everywhere and should be constants in a single location
        val caKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordadevcakeys.jks"), "cordacadevpass")
        val intermediateCa = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, "cordacadevkeypass")
        // TODO If using a custom root cert, then the intermidate cert needs to be generated from it as well, and not taken from the default
        val rootCert = customRootCert ?: caKeyStore.getX509Certificate(X509Utilities.CORDA_ROOT_CA)

        nodeSslConfig.certificatesDirectory.createDirectories()
        nodeSslConfig.createDevKeyStores(rootCert, intermediateCa, legalName)

        val keyStoreWrapper = KeyStoreWrapper(nodeSslConfig.nodeKeystore, nodeSslConfig.keyStorePassword)
        val identity = keyStoreWrapper.storeLegalIdentity(legalName, "$NODE_IDENTITY_ALIAS_PREFIX-private-key", Crypto.generateKeyPair())
        return identity.party
    }

    fun generateDistributedNotaryIdentity(dirs: List<Path>, notaryName: CordaX500Name, threshold: Int = 1, customRootCert: X509Certificate? = null): Party {
        require(dirs.isNotEmpty())

        log.trace { "Generating identity \"$notaryName\" for nodes: ${dirs.joinToString()}" }
        val keyPairs = (1..dirs.size).map { generateKeyPair() }
        val compositeKey = CompositeKey.Builder().addKeys(keyPairs.map { it.public }).build(threshold)

        val caKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordadevcakeys.jks"), "cordacadevpass")
        val intermediateCa = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, "cordacadevkeypass")
        // TODO If using a custom root cert, then the intermidate cert needs to be generated from it as well, and not taken from the default
        val rootCert = customRootCert ?: caKeyStore.getCertificate(X509Utilities.CORDA_ROOT_CA)

        keyPairs.zip(dirs) { keyPair, nodeDir ->
            val (serviceKeyCert, compositeKeyCert) = listOf(keyPair.public, compositeKey).map { publicKey ->
                X509Utilities.createCertificate(
                        CertificateType.SERVICE_IDENTITY,
                        intermediateCa.certificate,
                        intermediateCa.keyPair,
                        notaryName.x500Principal,
                        publicKey)
            }
            val distServKeyStoreFile = (nodeDir / "certificates").createDirectories() / "distributedService.jks"
            val keystore = loadOrCreateKeyStore(distServKeyStoreFile, "cordacadevpass")
            keystore.setCertificateEntry("$DISTRIBUTED_NOTARY_ALIAS_PREFIX-composite-key", compositeKeyCert)
            keystore.setKeyEntry(
                    "$DISTRIBUTED_NOTARY_ALIAS_PREFIX-private-key",
                    keyPair.private,
                    "cordacadevkeypass".toCharArray(),
                    arrayOf(serviceKeyCert, intermediateCa.certificate, rootCert))
            keystore.save(distServKeyStoreFile, "cordacadevpass")
        }

        return Party(notaryName, compositeKey)
    }
}

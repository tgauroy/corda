package net.corda.node

import net.corda.core.crypto.Crypto
import net.corda.core.internal.div
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.config.configureDevKeyAndTrustStores
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.crypto.*
import net.corda.testing.ALICE_NAME
import net.corda.testing.driver.driver
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.nio.file.Path
import javax.security.auth.x500.X500Principal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeKeystoreCheckTest {
    @Test
    fun `starting node in non-dev mode with no key store`() {
        driver(startNodesInProcess = true) {
            assertThatThrownBy {
                startNode(customOverrides = mapOf("devMode" to false)).getOrThrow()
            }.hasMessageContaining("Identity certificate not found")
        }
    }

    @Test
    fun `node should throw exception if cert path doesn't chain to the trust root`() {
        driver(startNodesInProcess = true) {
            // Create keystores
            val keystorePassword = "password"
            val config = object : SSLConfiguration {
                override val keyStorePassword: String = keystorePassword
                override val trustStorePassword: String = keystorePassword
                override val certificatesDirectory: Path = baseDirectory(ALICE_NAME) / "certificates"
            }
            config.configureDevKeyAndTrustStores(ALICE_NAME)

            // This should pass with correct keystore.
            val node = startNode(
                    providedName = ALICE_NAME,
                    customOverrides = mapOf("devMode" to false,
                            "keyStorePassword" to keystorePassword,
                            "trustStorePassword" to keystorePassword)
            ).getOrThrow()
            node.stop()

            // Fiddle with node keystore.
            val keystore = loadKeyStore(config.nodeKeystore, config.keyStorePassword)

            // Self signed root
            val badRootKeyPair = Crypto.generateKeyPair()
            val badRoot = X509Utilities.createSelfSignedCACertificate(X500Principal("O=Bad Root,L=Lodnon,C=GB"), badRootKeyPair)
            val nodeCA = keystore.getCertificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA, config.keyStorePassword)
            val badNodeCACert = X509Utilities.createCertificate(CertificateType.NODE_CA, badRoot, badRootKeyPair, ALICE_NAME.x500Principal, nodeCA.keyPair.public)
            keystore.setKeyEntry(X509Utilities.CORDA_CLIENT_CA, nodeCA.keyPair.private, config.keyStorePassword.toCharArray(), arrayOf(badNodeCACert, badRoot))
            keystore.save(config.nodeKeystore, config.keyStorePassword)

            assertThatThrownBy {
                startNode(providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false)).getOrThrow()
            }.hasMessage("Client CA certificate must chain to the trusted root.")
        }
    }
}

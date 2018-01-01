package net.corda.node.utilities.registration

import net.corda.core.crypto.Crypto
import net.corda.core.internal.*
import net.corda.core.utilities.seconds
import net.corda.node.services.config.NodeConfiguration
import net.corda.nodeapi.internal.crypto.*
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_TLS
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_ROOT_CA
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * Helper for managing the node registration process, which checks for any existing certificates and requests them if
 * needed.
 */
class NetworkRegistrationHelper(private val config: NodeConfiguration, private val certService: NetworkRegistrationService) {
    companion object {
        val pollInterval = 10.seconds
        val SELF_SIGNED_PRIVATE_KEY = "Self Signed Private Key"
    }

    private val requestIdStore = config.certificatesDirectory / "certificate-request-id.txt"
    private val keystorePassword = config.keyStorePassword
    // TODO: Use different password for private key.
    private val privateKeyPassword = config.keyStorePassword
    private val trustStore: KeyStore
    private val rootCert: X509Certificate

    init {
        require(config.trustStoreFile.exists()) {
            "${config.trustStoreFile} does not exist. This file must contain the root CA cert of your compatibility zone. " +
                    "Please contact your CZ operator."
        }
        trustStore = loadKeyStore(config.trustStoreFile, config.trustStorePassword)
        val rootCert = trustStore.getCertificate(CORDA_ROOT_CA)
        require(rootCert != null) {
            "${config.trustStoreFile} does not contain a certificate with the key $CORDA_ROOT_CA." +
                    "This file must contain the root CA cert of your compatibility zone. " +
                    "Please contact your CZ operator."
        }
        this.rootCert = rootCert as X509Certificate
    }

    /**
     * Ensure the initial keystore for a node is set up.
     *
     * This checks the "config.certificatesDirectory" field for certificates required to connect to a Corda network.
     * If the certificates are not found, a PKCS #10 certification request will be submitted to the
     * Corda network permissioning server via [NetworkRegistrationService]. This process will enter a polling loop until
     * the request has been approved, and then the certificate chain will be downloaded and stored in [KeyStore] reside in
     * the certificates directory.
     *
     * @throws CertificateRequestException if the certificate retrieved by doorman is invalid.
     */
    fun buildKeystore() {
        config.certificatesDirectory.createDirectories()
        val caKeyStore = loadOrCreateKeyStore(config.nodeKeystore, keystorePassword)
        if (!caKeyStore.containsAlias(CORDA_CLIENT_CA)) {
            // Create or load self signed keypair from the key store.
            // We use the self sign certificate to store the key temporarily in the keystore while waiting for the request approval.
            if (!caKeyStore.containsAlias(SELF_SIGNED_PRIVATE_KEY)) {
                val keyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
                val selfSignCert = X509Utilities.createSelfSignedCACertificate(config.myLegalName.x500Principal, keyPair)
                // Save to the key store.
                caKeyStore.addOrReplaceKey(SELF_SIGNED_PRIVATE_KEY, keyPair.private, privateKeyPassword.toCharArray(),
                        arrayOf(selfSignCert))
                caKeyStore.save(config.nodeKeystore, keystorePassword)
            }
            val keyPair = caKeyStore.getKeyPair(SELF_SIGNED_PRIVATE_KEY, privateKeyPassword)
            val requestId = submitOrResumeCertificateSigningRequest(keyPair)

            val certificates = try {
                pollServerForCertificates(requestId)
            } catch (certificateRequestException: CertificateRequestException) {
                System.err.println(certificateRequestException.message)
                System.err.println("Please make sure the details in configuration file are correct and try again.")
                System.err.println("Corda node will now terminate.")
                requestIdStore.deleteIfExists()
                throw certificateRequestException
            }

            println("Certificate signing request approved, storing private key with the certificate chain.")
            // Save private key and certificate chain to the key store.
            caKeyStore.addOrReplaceKey(CORDA_CLIENT_CA, keyPair.private, privateKeyPassword.toCharArray(), certificates)
            caKeyStore.deleteEntry(SELF_SIGNED_PRIVATE_KEY)
            caKeyStore.save(config.nodeKeystore, keystorePassword)
            println("Node private key and certificate stored in ${config.nodeKeystore}.")

            println("Checking root of the certificate path is what we expect.")
            X509Utilities.validateCertificateChain(rootCert, *certificates)

            println("Generating SSL certificate for node messaging service.")
            val sslKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val caCert = caKeyStore.getX509Certificate(CORDA_CLIENT_CA)
            val sslCert = X509Utilities.createCertificate(CertificateType.TLS, caCert, keyPair, caCert.subjectX500Principal, sslKey.public)
            val sslKeyStore = loadOrCreateKeyStore(config.sslKeystore, keystorePassword)
            sslKeyStore.addOrReplaceKey(CORDA_CLIENT_TLS, sslKey.private, privateKeyPassword.toCharArray(), arrayOf(sslCert, *certificates))
            sslKeyStore.save(config.sslKeystore, config.keyStorePassword)
            println("SSL private key and certificate stored in ${config.sslKeystore}.")
            // All done, clean up temp files.
            requestIdStore.deleteIfExists()
        } else {
            println("Certificate already exists, Corda node will now terminate...")
        }
    }

    /**
     * Poll Certificate Signing Server for approved certificate,
     * enter a slow polling loop if server return null.
     * @param requestId Certificate signing request ID.
     * @return Map of certificate chain.
     */
    private fun pollServerForCertificates(requestId: String): Array<Certificate> {
        println("Start polling server for certificate signing approval.")
        // Poll server to download the signed certificate once request has been approved.
        var certificates = certService.retrieveCertificates(requestId)
        while (certificates == null) {
            Thread.sleep(pollInterval.toMillis())
            certificates = certService.retrieveCertificates(requestId)
        }
        return certificates
    }

    /**
     * Submit Certificate Signing Request to Certificate signing service if request ID not found in file system
     * New request ID will be stored in requestId.txt
     * @param keyPair Public Private key pair generated for SSL certification.
     * @return Request ID return from the server.
     */
    private fun submitOrResumeCertificateSigningRequest(keyPair: KeyPair): String {
        // Retrieve request id from file if exists, else post a request to server.
        return if (!requestIdStore.exists()) {
            val request = X509Utilities.createCertificateSigningRequest(config.myLegalName.x500Principal, config.emailAddress, keyPair)
            val writer = StringWriter()
            JcaPEMWriter(writer).use {
                it.writeObject(PemObject("CERTIFICATE REQUEST", request.encoded))
            }
            println("Certificate signing request with the following information will be submitted to the Corda certificate signing server.")
            println()
            println("Legal Name: ${config.myLegalName}")
            println("Email: ${config.emailAddress}")
            println()
            println("Public Key: ${keyPair.public}")
            println()
            println("$writer")
            // Post request to signing server via http.
            println("Submitting certificate signing request to Corda certificate signing server.")
            val requestId = certService.submitRequest(request)
            // Persists request ID to file in case of node shutdown.
            requestIdStore.writeLines(listOf(requestId))
            println("Successfully submitted request to Corda certificate signing server, request ID: $requestId.")
            requestId
        } else {
            val requestId = requestIdStore.readLines { it.findFirst().get() }
            println("Resuming from previous certificate signing request, request ID: $requestId.")
            requestId
        }
    }
}

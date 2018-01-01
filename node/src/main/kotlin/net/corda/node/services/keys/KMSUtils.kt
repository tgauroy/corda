package net.corda.node.services.keys

import net.corda.core.crypto.Crypto
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.CertRole
import net.corda.core.utilities.days
import net.corda.node.services.api.IdentityServiceInternal
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.ContentSignerBuilder
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import org.bouncycastle.operator.ContentSigner
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * Generates a new random [KeyPair], adds it to the internal key storage, then generates a corresponding
 * [X509Certificate] and adds it to the identity service.
 *
 * @param identityService issuer service to use when registering the certificate.
 * @param subjectPublicKey public key of new identity.
 * @param issuer issuer to generate a key and certificate for. Must be an identity this node has the private key for.
 * @param issuerSigner a content signer for the issuer.
 * @param revocationEnabled whether to check revocation status of certificates in the certificate path.
 * @return X.509 certificate and path to the trust root.
 */
fun freshCertificate(identityService: IdentityServiceInternal,
                     subjectPublicKey: PublicKey,
                     issuer: PartyAndCertificate,
                     issuerSigner: ContentSigner,
                     revocationEnabled: Boolean = false): PartyAndCertificate {
    val issuerRole = CertRole.extract(issuer.certificate)
    require(issuerRole == CertRole.LEGAL_IDENTITY) { "Confidential identities can only be issued from well known identities, provided issuer ${issuer.name} has role $issuerRole" }
    val issuerCert = issuer.certificate
    val window = X509Utilities.getCertificateValidityWindow(Duration.ZERO, 3650.days, issuerCert)
    val ourCertificate = X509Utilities.createCertificate(
            CertificateType.CONFIDENTIAL_LEGAL_IDENTITY,
            issuerCert.subjectX500Principal,
            issuerSigner,
            issuer.name.x500Principal,
            subjectPublicKey,
            window)
    val ourCertPath = X509CertificateFactory().generateCertPath(listOf(ourCertificate) + issuer.certPath.certificates)
    val anonymisedIdentity = PartyAndCertificate(ourCertPath)
    identityService.justVerifyAndRegisterIdentity(anonymisedIdentity)
    return anonymisedIdentity
}

fun getSigner(issuerKeyPair: KeyPair): ContentSigner {
    val signatureScheme = Crypto.findSignatureScheme(issuerKeyPair.private)
    val provider = Security.getProvider(signatureScheme.providerName)
    return ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
}

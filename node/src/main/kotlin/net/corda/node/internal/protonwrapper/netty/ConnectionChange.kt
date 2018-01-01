package net.corda.node.internal.protonwrapper.netty

import java.net.InetSocketAddress
import java.security.cert.X509Certificate

data class ConnectionChange(val remoteAddress: InetSocketAddress, val remoteCert: X509Certificate?, val connected: Boolean)
// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Preserve platform cipher suites and certificate verification; remove legacy TLS protocols. */
final class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    Tls12SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
    private Socket restrict(Socket socket) throws IOException {
        if (!(socket instanceof SSLSocket)) throw new IOException("TLS socket required");
        SSLSocket ssl = (SSLSocket) socket;
        List<String> protocols = new ArrayList<>();
        for (String protocol : ssl.getSupportedProtocols())
            if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) protocols.add(protocol);
        if (protocols.isEmpty()) { socket.close(); throw new IOException("TLS 1.2+ unavailable"); }
        ssl.setEnabledProtocols(protocols.toArray(new String[0]));
        return ssl;
    }
    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return restrict(delegate.createSocket()); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
        return restrict(delegate.createSocket(socket, host, port, close));
    }
    @Override public Socket createSocket(String host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return restrict(delegate.createSocket(host, port, local, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return restrict(delegate.createSocket(host, port, local, localPort));
    }
}

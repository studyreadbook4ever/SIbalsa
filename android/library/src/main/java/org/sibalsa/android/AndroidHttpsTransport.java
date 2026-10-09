// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Network;
import org.sibalsa.core.AdapterSnapshot;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.Lease;
import org.sibalsa.core.ServerPolicy;
import org.sibalsa.core.ServerTransport;
import org.sibalsa.core.SiBalsaConfig;
import org.sibalsa.core.UpdatePayload;
import org.sibalsa.core.UpdateReceipt;
import org.sibalsa.core.WireCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** All DNS, handshakes and requests are bound to the currently eligible Wi-Fi network. */
public final class AndroidHttpsTransport implements ServerTransport {
    private final SiBalsaConfig config;
    private final AndroidExecutionGate gate;
    private final SSLSocketFactory tls;
    public AndroidHttpsTransport(Context context, SiBalsaConfig config, AndroidExecutionGate gate) throws Exception {
        if (config.server == null || gate == null) throw new IllegalArgumentException("HTTPS server and idle gate required");
        this.config = config;
        this.gate = gate;
        byte[] ca = config.developmentCa();
        SSLSocketFactory factory;
        if (ca != null) {
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
                throw new SecurityException("Development CA allowed only in a debuggable host APK");
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(ca));
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
            roots.load(null, null);
            roots.setCertificateEntry("sibalsa-development-ca", certificate);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(roots);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
            factory = ssl.getSocketFactory();
        } else {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, null, null);
            factory = ssl.getSocketFactory();
        }
        tls = new Tls12SocketFactory(factory);
    }
    @Override public ServerPolicy policy(Cancellation cancellation) throws Exception {
        return WireCodec.decodePolicy(request("GET", "/v1/policy", null, cancellation));
    }
    @Override public Lease lease(AdapterSnapshot base, Cancellation cancellation) throws Exception {
        return WireCodec.decodeLease(request("POST", "/v1/lease", WireCodec.encodeLease(base), cancellation), base.model);
    }
    @Override public UpdateReceipt upload(UpdatePayload update, Cancellation cancellation) throws Exception {
        return WireCodec.decodeReceipt(request("POST", "/v1/update", WireCodec.encodeUpdate(update), cancellation));
    }
    @Override public AdapterSnapshot adapter(Cancellation cancellation) throws Exception {
        return WireCodec.decodeAdapter(request("GET", "/v1/adapter", null, cancellation));
    }
    private String request(String method, String path, String payload, Cancellation cancellation) throws Exception {
        Network wifi = gate.requireNetwork(cancellation);
        URL endpoint = config.server.resolve(path).toURL();
        HttpsURLConnection connection = (HttpsURLConnection) wifi.openConnection(endpoint);
        connection.setSSLSocketFactory(tls);
        // Do not replace the platform hostname verifier or certificate trust with permissive hooks.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(config.connectTimeoutMs);
        connection.setReadTimeout(config.readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "SIbalsa/1");
        connection.setRequestProperty("Cookie", "");
        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            if (body.length > config.maxResponseBytes) throw new IOException("Update request too large");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
        }
        try (AutoCloseable closing = cancellation.onCancel(connection::disconnect)) {
            gate.requireNetwork(wifi, cancellation);
            connection.connect();
            gate.requireNetwork(wifi, cancellation);
            verifyPin(connection);
            if (body != null) {
                try (OutputStream output = connection.getOutputStream()) {
                    try (AutoCloseable outputCancellation = cancellation.onCancel(() -> close(output))) {
                        for (int offset = 0; offset < body.length; offset += 4096) {
                            gate.requireNetwork(wifi, cancellation);
                            output.write(body, offset, Math.min(4096, body.length - offset));
                        }
                        gate.requireNetwork(wifi, cancellation);
                        output.flush();
                    }
                }
            }
            gate.requireNetwork(wifi, cancellation);
            int status = connection.getResponseCode();
            gate.requireNetwork(wifi, cancellation);
            if (status != 200) throw new HttpStatusException(status);
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                throw new IOException("Expected JSON response");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Compressed response rejected");
            long length = connection.getContentLengthLong();
            if (length > config.maxResponseBytes) throw new IOException("Server response too large");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                try (AutoCloseable inputCancellation = cancellation.onCancel(() -> close(input))) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        gate.requireNetwork(wifi, cancellation);
                        int count = input.read(buffer);
                        if (count < 0) break;
                        if (count > config.maxResponseBytes - bytes.size()) throw new IOException("Server response too large");
                        bytes.write(buffer, 0, count);
                    }
                    gate.requireNetwork(wifi, cancellation);
                    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                }
            }
        } finally { connection.disconnect(); }
    }
    private void verifyPin(HttpsURLConnection connection) throws Exception {
        if (config.spkiPin == null) return;
        Certificate[] certificates = connection.getServerCertificates();
        if (certificates.length == 0) throw new IOException("Missing TLS peer certificate");
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(certificates[0].getPublicKey().getEncoded());
        byte[] expected = Base64.getDecoder().decode(config.spkiPin.substring(7));
        if (!MessageDigest.isEqual(actual, expected)) throw new IOException("TLS SPKI pin mismatch");
    }
    private static void close(AutoCloseable resource) {
        try { resource.close(); } catch (Exception ignored) {}
    }
}

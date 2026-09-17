package com.hatis.platform.assets.adapter.scan;

import com.hatis.platform.assets.port.out.MalwareScanner;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * ClamAV adapter speaking the daemon {@code INSTREAM} protocol.
 *
 * <p>The scanner runs as a separate container next to the worker, so bytes never
 * leave the pod network and no cloud scanning service sees customer content — which
 * matters for customers whose uploads are confidential by contract.
 *
 * <p>Failures are reported as {@code ERROR}, never as {@code CLEAN}. A scanner that
 * cannot be reached must quarantine rather than wave content through.
 */
@Component
@ConditionalOnProperty(name = "hatis.assets.scanner.mode", havingValue = "clamav")
public class ClamAvScanner implements MalwareScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final byte[] INSTREAM_END = new byte[]{0, 0, 0, 0};
    private static final int CHUNK_SIZE = 64 * 1024;

    private final StorageProvider storage;
    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvScanner(StorageProvider storage,
                         @Value("${hatis.assets.scanner.clamav.host:clamav}") String host,
                         @Value("${hatis.assets.scanner.clamav.port:3310}") int port,
                         @Value("${hatis.assets.scanner.clamav.timeout-millis:60000}") int timeoutMillis) {
        this.storage = storage;
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return "clamav";
    }

    @Override
    public ScanResult scan(String storageKey) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            try (InputStream content = storage.get(storageKey)) {
                DataOutputStream framed = new DataOutputStream(out);
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = content.read(buffer)) > 0) {
                    framed.writeInt(read);
                    framed.write(buffer, 0, read);
                }
                framed.write(INSTREAM_END);
                framed.flush();
            }

            String response = readResponse(socket.getInputStream());
            return interpret(response);
        } catch (Exception e) {
            log.warn("ClamAV scan failed for key {}: {}", storageKey, e.getClass().getSimpleName());
            throw new PlatformExceptions.DependencyUnavailable("malware-scanner",
                    "The malware scanner did not respond");
        }
    }

    /**
     * Interprets a daemon response.
     *
     * <p>The documented forms are {@code stream: OK} and
     * {@code stream: <signature> FOUND}; {@code ERROR} covers limits and internal
     * faults. Anything unrecognised is treated as an error, not as clean.
     */
    static ScanResult interpret(String response) {
        if (response == null) {
            return ScanResult.error("No response from the scanner");
        }
        String value = response.trim();
        if (value.endsWith(": OK") || value.endsWith("OK")) {
            return ScanResult.clean();
        }
        int found = value.lastIndexOf(" FOUND");
        if (found > 0) {
            int colon = value.indexOf(':');
            String signature = colon >= 0 ? value.substring(colon + 1, found).trim() : value.substring(0, found);
            return ScanResult.infected(signature);
        }
        return ScanResult.error(value);
    }

    private static String readResponse(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read()) > 0) {
            buffer.write(read);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}

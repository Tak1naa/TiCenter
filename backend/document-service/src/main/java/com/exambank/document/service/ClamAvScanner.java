package com.exambank.document.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal clamd INSTREAM client. ClamAV remains the scanning engine. */
@Service
public class ClamAvScanner {
    private final String host;
    private final int port;
    private final boolean required;

    public ClamAvScanner(@Value("${app.upload.clamav-host:}") String host,
                         @Value("${app.upload.clamav-port:3310}") int port,
                         @Value("${app.upload.clamav-required:false}") boolean required) {
        this.host = host;
        this.port = port;
        this.required = required;
    }

    public void assertClean(Path path) throws IOException {
        if (host == null || host.isBlank()) {
            if (required) throw new IOException("ClamAV is required but not configured");
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    out.write(ByteBuffer.allocate(4).putInt(read).array());
                    out.write(buffer, 0, read);
                }
            }
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();
            String result = new String(socket.getInputStream().readNBytes(4096),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (result.contains("FOUND")) throw new SecurityException("文件未通过恶意软件扫描");
            if (!result.contains("OK")) throw new IOException("ClamAV scan failed: " + result.strip());
        } catch (IOException e) {
            if (required) throw e;
        }
    }
}

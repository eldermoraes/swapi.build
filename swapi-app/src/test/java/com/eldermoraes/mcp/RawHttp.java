package com.eldermoraes.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * HTTP/1.1 over a plain socket, for what java.net.http cannot send: two lines
 * of one header, a body that stops before its end, a malformed chunk.
 */
final class RawHttp {

    private RawHttp() {
    }

    /** What came back: every byte until the server closed (or the timeout), and whether it closed. */
    record Exchange(byte[] bytes, boolean closedByServer) {

        String head() {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            return end < 0 ? text : text.substring(0, end);
        }

        int status() {
            String head = head();
            return head.startsWith("HTTP/1.1 ") ? Integer.parseInt(head.substring(9, 12)) : -1;
        }

        /** The first value of a header, case-insensitive; null if absent. */
        String header(String name) {
            for (String line : head().split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        String body() {
            String text = new String(bytes, StandardCharsets.UTF_8);
            int end = text.indexOf("\r\n\r\n");
            String body = end < 0 ? "" : text.substring(end + 4);
            String encoding = header("Transfer-Encoding");
            if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                return body;
            }
            StringBuilder out = new StringBuilder();
            int at = 0;
            while (at < body.length()) {
                int line = body.indexOf("\r\n", at);
                if (line < 0) {
                    break;
                }
                int size = Integer.parseInt(body.substring(at, line).split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                out.append(body, line + 2, line + 2 + size);
                at = line + 2 + size + 2;
            }
            return out.toString();
        }
    }

    /**
     * Writes the request, then closes the socket's output when halfClose (a
     * client that goes away mid-body), and reads until the server closes or
     * timeoutMs passes without a byte.
     */
    static Exchange exchange(int port, byte[] request, boolean halfClose, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 2000);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            if (halfClose) {
                socket.shutdownOutput();
            }
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    got.write(buffer, 0, n);
                }
                return new Exchange(got.toByteArray(), true);
            } catch (SocketTimeoutException e) {
                return new Exchange(got.toByteArray(), false);
            } catch (IOException reset) {
                // A connection reset is the server closing too.
                return new Exchange(got.toByteArray(), true);
            }
        }
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

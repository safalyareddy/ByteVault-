package dev.bytevault.controlplane.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;

/**
 * Enforces a body size cap while the body is read, so it also holds for chunked transfer
 * encoding where no Content-Length is declared up front.
 */
public final class LimitedBodyRequest extends HttpServletRequestWrapper {

    /** Thrown mid-read once the cap is exceeded; mapped to 413 by the exception handler. */
    public static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long max) {
            super("request body exceeds " + max + " bytes");
        }
    }

    private final long max;
    private ServletInputStream stream;

    LimitedBodyRequest(HttpServletRequest request, long max) {
        super(request);
        this.max = max;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            ServletInputStream in = super.getInputStream();
            stream = new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b != -1) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws BodyTooLargeException {
                    read += n;
                    if (read > max) {
                        throw new BodyTooLargeException(max);
                    }
                }

                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
            };
        }
        return stream;
    }
}

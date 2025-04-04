package org.cardanofoundation.lob.app.support.spring_web;

import java.io.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

public class CustomHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedRequest;

    public CustomHttpServletRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        // Read and cache the request body
        this.cachedRequest= request.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(this.cachedRequest);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(this.cachedRequest)));
    }

    private static class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream byteArrayInputStream;

        public CachedServletInputStream(byte[] cachedRequest) {
            this.byteArrayInputStream = new ByteArrayInputStream(cachedRequest);
        }

        @Override
        public boolean isFinished() {
            return byteArrayInputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // Not required for this implementation
        }

        @Override
        public int read() {
            return byteArrayInputStream.read();
        }
    }
}

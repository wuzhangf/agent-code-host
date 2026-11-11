package com.steercrew.examples.embedded;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * JDK request headers are unmodifiable on modern runtimes, so edge auth
 * adaptation can't mutate them in place — wrap the exchange instead and
 * serve a rewritten Headers view to the delegate.
 */
final class HeaderInjectingExchange extends HttpExchange {

    private final HttpExchange delegate;
    private final Headers requestHeaders;

    HeaderInjectingExchange(HttpExchange delegate, String name, String value) {
        this.delegate = delegate;
        this.requestHeaders = new Headers();
        for (Map.Entry<String, List<String>> e
                : delegate.getRequestHeaders().entrySet()) {
            this.requestHeaders.put(e.getKey(), e.getValue());
        }
        this.requestHeaders.set(name, value);
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
    @Override public URI getRequestURI() { return delegate.getRequestURI(); }
    @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
    @Override public HttpContext getHttpContext() { return delegate.getHttpContext(); }
    @Override public void close() { delegate.close(); }
    @Override public InputStream getRequestBody() { return delegate.getRequestBody(); }
    @Override public OutputStream getResponseBody() { return delegate.getResponseBody(); }
    @Override public void sendResponseHeaders(int code, long length)
            throws java.io.IOException { delegate.sendResponseHeaders(code, length); }
    @Override public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
    @Override public int getResponseCode() { return delegate.getResponseCode(); }
    @Override public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
    @Override public String getProtocol() { return delegate.getProtocol(); }
    @Override public Object getAttribute(String name) { return delegate.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
    @Override public void setStreams(InputStream in, OutputStream out) { delegate.setStreams(in, out); }
    @Override public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
}

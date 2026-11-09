package mp4.platform;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;

/** Minimal servlet bridge for the existing JDK assigned-report HTTP handler. */
final class ServletHttpExchange extends HttpExchange {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final Headers responseHeaders = new Headers();

    ServletHttpExchange(HttpServletRequest request, HttpServletResponse response) {
        this.request = request;
        this.response = response;
    }

    @Override public Headers getRequestHeaders() { return new Headers(); }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() {
        String query = request.getQueryString();
        return URI.create(request.getRequestURI() + (query == null ? "" : "?" + query));
    }
    @Override public String getRequestMethod() { return request.getMethod(); }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() { }
    @Override public InputStream getRequestBody() {
        try { return request.getInputStream(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public OutputStream getResponseBody() {
        try { return response.getOutputStream(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public void sendResponseHeaders(int status, long responseLength) throws IOException {
        response.setStatus(status);
        responseHeaders.forEach((name, values) -> { if (!values.isEmpty()) response.setHeader(name, values.get(0)); });
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
    }
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort()); }
    @Override public int getResponseCode() { return response.getStatus(); }
    @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(request.getLocalAddr(), request.getLocalPort()); }
    @Override public String getProtocol() { return request.getProtocol(); }
    @Override public Object getAttribute(String name) { return request.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { request.setAttribute(name, value); }
    @Override public void setStreams(InputStream input, OutputStream output) { }
    @Override public HttpPrincipal getPrincipal() { return null; }
}

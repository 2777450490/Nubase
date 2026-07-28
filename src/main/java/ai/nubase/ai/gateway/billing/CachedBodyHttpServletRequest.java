package ai.nubase.ai.gateway.billing;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request, int maximumBytes) throws IOException {
        super(request);
        byte[] loaded = request.getInputStream().readNBytes(maximumBytes + 1);
        if (loaded.length > maximumBytes) {
            throw new IllegalArgumentException("Request body exceeds billing admission limit");
        }
        this.body = loaded;
    }

    byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                if (readListener == null) {
                    return;
                }
                try {
                    if (!isFinished()) {
                        readListener.onDataAvailable();
                    }
                    readListener.onAllDataRead();
                } catch (IOException e) {
                    readListener.onError(e);
                }
            }

            @Override
            public int read() {
                return input.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}

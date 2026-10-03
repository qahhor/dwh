package com.smartup24.cms.spi.storage;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FileDownloadStream(InputStream inputStream, long contentLength, String contentType) implements Closeable {

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}

package com.greenwhite.dwh.spi.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public record FileDownloadStream(
        InputStream inputStream,
        long contentLength,
        String contentType
) implements Closeable {

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}

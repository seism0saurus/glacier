package de.seism0saurus.glacier.mastodon;

import java.io.Closeable;
import java.io.IOException;

/**
 * A wrapper for objects implementing the Closeable interface.
 * This class provides an additional functionality to track whether the wrapped Closeable has been closed.
 */
public class CloseableWrapper implements Closeable {

    private final Closeable closable;
    private boolean closed = false;

    public CloseableWrapper(final Closeable closable) {
        this.closable = closable;
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
        this.closable.close();
    }

    public boolean closed() {
        return this.closed;
    }
}

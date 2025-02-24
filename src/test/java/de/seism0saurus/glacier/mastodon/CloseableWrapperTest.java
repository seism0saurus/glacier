package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.Closeable;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the CloseableWrapper class.
 * This test class ensures that the CloseableWrapper functionality
 * operates correctly under various scenarios, including its ability
 * to close the wrapped Closeable and track the closed state accurately.
 */
class CloseableWrapperTest {

    @Test
    void testClose_SetsClosedTrueAndInvokesCloseOnWrappedCloseable() throws IOException {
        // Arrange
        Closeable mockCloseable = mock(Closeable.class);
        CloseableWrapper closeableWrapper = new CloseableWrapper(mockCloseable);

        // Act
        closeableWrapper.close();

        // Assert
        assertTrue(closeableWrapper.closed());
        verify(mockCloseable, times(1)).close();
    }

    @Test
    void testClosed_ReturnsFalseInitially() {
        // Arrange
        Closeable mockCloseable = mock(Closeable.class);
        CloseableWrapper closeableWrapper = new CloseableWrapper(mockCloseable);

        // Act
        boolean isClosed = closeableWrapper.closed();

        // Assert
        assertFalse(isClosed);
    }

    @Test
    void testClose_ThrowsIOExceptionWhenWrappedCloseableThrows() throws IOException {
        // Arrange
        Closeable mockCloseable = mock(Closeable.class);
        doThrow(new IOException("Mock exception")).when(mockCloseable).close();
        CloseableWrapper closeableWrapper = new CloseableWrapper(mockCloseable);

        try {
            // Act
            closeableWrapper.close();
        } catch (IOException e) {
            // Assert
            assertTrue(e.getMessage().contains("Mock exception"));
        }
    }
}
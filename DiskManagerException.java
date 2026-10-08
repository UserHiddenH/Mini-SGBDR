/**
 * Exception thrown when an error occurs during disk manager operations,
 * such as I/O failures, invalid directory paths, incompatible page sizes,
 * corrupt page identifiers, or allocation errors.
 */
public class DiskManagerException extends Exception {

    /**
     * Constructs a new DiskManagerException with no detail message.
     */
    public DiskManagerException() {
        super();
    }

    /**
     * Constructs a new DiskManagerException with the specified detail message.
     *
     * @param message the detail message describing the error.
     */
    public DiskManagerException(String message) {
        super(message);
    }

    /**
     * Constructs a new DiskManagerException with the specified detail message and cause.
     *
     * @param message the detail message describing the error.
     * @param cause   the underlying cause of the exception (e.g., an IOException).
     */
    public DiskManagerException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new DiskManagerException with the specified cause.
     *
     * @param cause the underlying cause of the exception.
     */
    public DiskManagerException(Throwable cause) {
        super(cause);
    }
}

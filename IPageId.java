/**
 * Interface representing a unique identifier for a disk storage page
 * in the database management system.
 * <p>
 * Implementations must uniquely identify a physical or logical page on disk
 * and properly implement value-based equality.
 */
public interface IPageId {

    /**
     * Indicates whether some other object is "equal to" this page identifier.
     * <p>
     * Two {@code IPageId} instances are considered equal if they reference
     * the exact same underlying page on disk.
     *
     * @param obj the reference object with which to compare.
     * @return {@code true} if this page identifier is equivalent to {@code obj};
     *         {@code false} otherwise.
     */
    @Override
    boolean equals(Object obj);
}
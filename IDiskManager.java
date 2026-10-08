import java.nio.ByteBuffer;

/**
 * Interface defining the operations of the Disk Manager component
 * for the storage engine layer of the DBMS.
 */
public interface IDiskManager {

    /**
     * Initializes the disk manager with a target working directory and a page size.
     * <p>
     * Binds the disk manager to the new directory.
     * <ul>
     * 	 <li>if the disk manager is already bound to a directory, fail.</li> 
     *   <li>If the directory already contains data, reuse it if compatible.</li> 
     *   <li>If the directory is empty, initialize it for subsequent use.</li>
     *   <li>If the directory does not exist, create then use as above.</li>   
     * </ul>
     *
     * @param dmDir    the absolute path to a valid working directory.
     * @param pageSize the size of a single page in bytes.
     * @throws DiskManagerException 
     */
    void Init(String dmDir, int pageSize) throws DiskManagerException;

    /**
     * Persists the current state and metadata of the disk manager
     * to the working directory.
     *
     * @throws DiskManagerException
     */
    void Save() throws DiskManagerException;

    /**
     * Allocates a new page requested by the upper layers of the DBMS.
     * <p>
     * Allocation policy:
     * <ol>
     *   <li>If a previously deallocated page is available, reuse it.</li>
     *   <li>Otherwise, append a new page to the disk storage.</li>
     * </ol>
     *
     * @return the {@link IPageId} identifying the newly allocated page.
     * @throws DiskManagerException
     */
    IPageId AllocPage() throws DiskManagerException;

    /**
     * Reads the on-disk content of the page designated by {@code ipid}
     * into the provided buffer.
     * <p>
     * Note: The caller is responsible for allocating and passing the destination buffer.
     *
     * @param ipid   the identifier of the page to read.
     * @param buffer the buffer into which data will be copied
     * @throws DiskManagerException
     */
    void ReadPage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException;

    /**
     * Writes the content of the provided buffer into the page designated by {@code ipid}.
     * This is the counterpart of {@link #ReadPage(IPageId, ByteBuffer)}.
     *
     * @param ipid   the identifier of the target page.
     * @param buffer the buffer containing the data to write.
     * @throws DiskManagerException
     */
    void WritePage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException;

    /**
     * Deallocates the page identified by {@code ipid}, marking it as available
     * for future allocations.
     *
     * @param ipid the identifier of the page to free.
     * @throws DiskManagerException
     */
    void DeallocPage(IPageId ipid) throws DiskManagerException;

    /**
     * Returns the total number of pages currently in use (allocated and not yet deallocated).
     *
     * @return the count of active allocated pages.
     */
    int GetActivePageCount();

    /**
     * Returns the configured page size in bytes managed by this disk manager.
     *
     * @return the page size in bytes.
     */
    int GetPageSize();
}

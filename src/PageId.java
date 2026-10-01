public class PageId implements IPageId {
    private final int index;

    public PageId(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return "PageId(" + index + ")";
    }
}
public class PageId implements IPageId {
    private final int index; // Le numéro de la page dans le fichier de données

    public PageId(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    // ---------------------------------------------------------------
    // equals : deux PageId sont égaux s'ils ont le même numéro de page
    // (exigé par l'interface IPageId)
    // ---------------------------------------------------------------
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;                  // c'est exactement le même objet
        }
        if (!(obj instanceof PageId)) {
            return false;                 // ce n'est pas un PageId (ou c'est null)
        }
        PageId autre = (PageId) obj;
        return this.index == autre.index; // même numéro = même page
    }

    // hashCode : doit toujours accompagner equals (deux objets égaux => même hashCode)
    @Override
    public int hashCode() {
        return Integer.hashCode(index);
    }

    @Override
    public String toString() {
        return "PageId(" + index + ")";
    }
}

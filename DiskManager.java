import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class DiskManager implements IDiskManager {

    private static final String NOM_FICHIER_ETAT = "diskmanager.state"; // Nom du fichier où on sauvegarde l'état du DiskManager (pas le contenu des pages)
    private static final String NOM_FICHIER_DONNEES = "data.bin"; // Nom du fichier où sont stockées les pages (le contenu écrit avec WritePage)
    private String dossier; // Le dossier de travail (ex : "/home/moi/mon_sgbd"), vaut null tant que Init n'a pas été appelée
    private int taillePage; // La taille d'une page en octets (ex : 4096)
    private int nombrePages; // Combien de pages ont été créées au total
    private ArrayList<Integer> pagesLibres = new ArrayList<Integer>(); // Les numéros des pages désallouées, réutilisables par AllocPage

    // Constructeur sans argument
    public DiskManager() {
    }

    // ---------------------------------------------------------------
    // Init : on prépare le DiskManager sur un dossier
    // - ne peut être appelée qu'une seule fois
    // - crée le dossier s'il n'existe pas
    // - si le dossier contient déjà un état, on le relit
    // On remplit les variables du DiskManager seulement à la fin :
    // si Init échoue, le DiskManager reste "non initialisé".
    // ---------------------------------------------------------------
    @Override
    public void Init(String dmDir, int pageSize) throws DiskManagerException {

        // 1. Init ne doit être appelée qu'une seule fois
        if (dossier != null) {
            throw new DiskManagerException("Init a déjà été appelée : le DiskManager est déjà lié au dossier " + dossier);
        }

        // 2. On vérifie les arguments
        if (dmDir == null || dmDir.isEmpty()) {
            throw new DiskManagerException("Le chemin du dossier est vide ou null");
        }
        if (pageSize <= 0) {
            throw new DiskManagerException("La taille de page doit être strictement positive : " + pageSize);
        }

        // 3. On crée le dossier s'il n'existe pas, et on vérifie que c'est bien un dossier
        File dossierTravail = new File(dmDir);
        if (!dossierTravail.exists()) {
            if (!dossierTravail.mkdirs()) {
                throw new DiskManagerException("Impossible de créer le dossier : " + dmDir);
            }
        } else if (!dossierTravail.isDirectory()) {
            throw new DiskManagerException(dmDir + " existe mais n'est pas un dossier");
        }

        // 4. On prépare l'état dans des variables temporaires (dossier vide : on part de zéro)
        int nombrePagesLu = 0;
        ArrayList<Integer> pagesLibresLues = new ArrayList<Integer>();

        // 5. Si le dossier a déjà un fichier d'état, on relit ce qui a été sauvegardé
        File fichierEtat = new File(dossierTravail, NOM_FICHIER_ETAT);
        if (fichierEtat.exists()) {
            try (Scanner lecteur = new Scanner(fichierEtat)) {

                int taillePageSauvegardee = lecteur.nextInt();
                if (taillePageSauvegardee != pageSize) {
                    throw new DiskManagerException("Taille de page incompatible : sauvegardée = "
                            + taillePageSauvegardee + ", demandée = " + pageSize);
                }

                nombrePagesLu = lecteur.nextInt();

                while (lecteur.hasNextInt()) {
                    pagesLibresLues.add(lecteur.nextInt());
                }
            } catch (IOException | NoSuchElementException e) {
                // IOException : fichier illisible / NoSuchElementException : contenu qui n'est pas des nombres
                throw new DiskManagerException("Fichier d'état illisible ou corrompu : " + e.getMessage(), e);
            }

            // On vérifie que les données relues ont du sens
            if (nombrePagesLu < 0) {
                throw new DiskManagerException("Fichier d'état corrompu : nombre de pages négatif");
            }
            for (int numeroPage : pagesLibresLues) {
                if (numeroPage < 0 || numeroPage >= nombrePagesLu) {
                    throw new DiskManagerException("Fichier d'état corrompu : page libre inexistante " + numeroPage);
                }
            }
        }

        // 6. Tout est bon : on lie enfin le DiskManager à ce dossier
        dossier = dmDir;
        taillePage = pageSize;
        nombrePages = nombrePagesLu;
        pagesLibres = pagesLibresLues;
    }

    // ---------------------------------------------------------------
    // Save : on écrit l'état dans le fichier
    // Le fichier ressemble à ça :
    //   4096        <- taille d'une page
    //   5           <- nombre de pages
    //   1           <- pages libres (une par ligne)
    //   3
    // ---------------------------------------------------------------
    @Override
    public void Save() throws DiskManagerException {
        verifierInitialise();

        File fichierEtat = new File(dossier, NOM_FICHIER_ETAT);

        try (PrintWriter ecrivain = new PrintWriter(fichierEtat)) {
            ecrivain.println(taillePage);
            ecrivain.println(nombrePages);
            for (int numeroPage : pagesLibres) {
                ecrivain.println(numeroPage);
            }
        } catch (IOException e) {
            throw new DiskManagerException("Erreur d'écriture de l'état : " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------
    // ReadPage : on copie le contenu de la page indiquée dans le buffer
    // (le buffer est créé et fourni par l'appelant)
    // ---------------------------------------------------------------
    @Override
    public void ReadPage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException {

        // 1. On vérifie que tout est valable
        verifierInitialise();
        PageId idPage = verifierPage(ipid);
        verifierBuffer(buffer);

        // 2. On retrouve le fichier et la position de la page dans ce fichier
        File fichierDonnees = new File(dossier, NOM_FICHIER_DONNEES);
        long position = (long) idPage.getIndex() * taillePage;

        try (RandomAccessFile fichier = new RandomAccessFile(fichierDonnees, "r")) {

            // 3. On lit exactement taillePage octets à cette position
            //    (readFully lève une erreur si le fichier est trop court)
            byte[] contenuPage = new byte[taillePage];
            fichier.seek(position);
            fichier.readFully(contenuPage);

            // 4. On copie la page dans le buffer, depuis son début, puis on le remet au début
            buffer.clear();
            buffer.put(contenuPage);
            buffer.rewind();
        } catch (IOException e) {
            throw new DiskManagerException("Erreur de lecture de la page " + idPage + " : " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------
    // WritePage : on copie le contenu du buffer dans la page indiquée
    // (c'est l'inverse de ReadPage)
    // ---------------------------------------------------------------
    @Override
    public void WritePage(IPageId ipid, ByteBuffer buffer) throws DiskManagerException {

        // 1. On vérifie que tout est valable (la page doit avoir été allouée)
        verifierInitialise();
        PageId idPage = verifierPage(ipid);
        verifierBuffer(buffer);

        // 2. On retrouve le fichier et la position de la page dans ce fichier
        File fichierDonnees = new File(dossier, NOM_FICHIER_DONNEES);
        long position = (long) idPage.getIndex() * taillePage;

        try (RandomAccessFile fichier = new RandomAccessFile(fichierDonnees, "rw")) {
            fichier.seek(position);

            // 3. On repart du 1er octet du buffer, pour être sûr de tout copier
            buffer.clear();

            // 4. On copie taillePage octets du buffer dans un tableau, puis on les écrit
            byte[] contenuPage = new byte[taillePage]; // un tableau vide de taillePage octets (rempli de zéros)
            buffer.get(contenuPage);                   // copie les octets du buffer dans ce tableau
            fichier.write(contenuPage);                // écrit ces octets dans le fichier, à la position de la page

            // 5. On remet le buffer au début, comme on l'a trouvé
            buffer.rewind();
        } catch (IOException e) {
            throw new DiskManagerException("Erreur d'écriture de la page " + idPage + " : " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------
    // AllocPage : on donne une page à la couche du dessus
    // - s'il y a une page libre, on la réutilise
    // - sinon, on rajoute une page à la fin du fichier
    // ---------------------------------------------------------------
    @Override
    public IPageId AllocPage() throws DiskManagerException {
        verifierInitialise();

        int numeroPage;

        if (!pagesLibres.isEmpty()) {
            // Page déjà présente dans le fichier, rien à faire sur le disque
            numeroPage = pagesLibres.remove(0);
        } else {
            // Nouvelle page à la fin
            numeroPage = nombrePages;

            // On agrandit le fichier d'une page (remplie de zéros)
            try (RandomAccessFile fichier = new RandomAccessFile(new File(dossier, NOM_FICHIER_DONNEES), "rw")) {
                fichier.setLength((long) (nombrePages + 1) * taillePage);
            } catch (IOException e) {
                throw new DiskManagerException("Erreur d'allocation d'une page : " + e.getMessage(), e);
            }

            // On ne compte la page que si le fichier a bien été agrandi
            nombrePages++;
        }

        return new PageId(numeroPage);
    }

    // ---------------------------------------------------------------
    // DeallocPage : la page devient libre et pourra être réutilisée
    // (on ne touche pas au fichier, la page reste dedans)
    // ---------------------------------------------------------------
    @Override
    public void DeallocPage(IPageId ipid) throws DiskManagerException {
        verifierInitialise();
        PageId idPage = verifierPage(ipid);

        if (pagesLibres.contains(idPage.getIndex())) {
            throw new DiskManagerException("La page " + idPage + " est déjà désallouée");
        }

        pagesLibres.add(idPage.getIndex());
    }

    // Taille d'une page, en octets
    @Override
    public int GetPageSize() {
        return taillePage;
    }

    // Nombre de pages utilisées = pages créées - pages libres
    @Override
    public int GetActivePageCount() {
        return nombrePages - pagesLibres.size();
    }

    // ---------------------------------------------------------------
    // Méthodes privées de vérification (pour ne pas répéter le code)
    // ---------------------------------------------------------------

    /** Vérifie que Init a bien été appelée. */
    private void verifierInitialise() throws DiskManagerException {
        if (dossier == null) {
            throw new DiskManagerException("Le DiskManager n'est pas initialisé : appelez Init d'abord");
        }
    }

    /** Vérifie que l'identifiant est un PageId valide et renvoie-le sous la forme d'un PageId. */
    private PageId verifierPage(IPageId ipid) throws DiskManagerException {
        if (ipid == null) {
            throw new DiskManagerException("Identifiant de page null");
        }
        if (!(ipid instanceof PageId)) {
            throw new DiskManagerException("Identifiant de page d'un type inconnu : " + ipid.getClass().getName());
        }
        PageId idPage = (PageId) ipid;

        // Les pages existantes vont de 0 à nombrePages - 1
        if (idPage.getIndex() < 0 || idPage.getIndex() >= nombrePages) {
            throw new DiskManagerException("Page inexistante : " + idPage);
        }
        return idPage;
    }

    /** Vérifie que le buffer existe et qu'il est assez grand pour contenir une page. */
    private void verifierBuffer(ByteBuffer buffer) throws DiskManagerException {
        if (buffer == null) {
            throw new DiskManagerException("Buffer null");
        }
        if (buffer.capacity() < taillePage) {
            throw new DiskManagerException("Buffer trop petit : " + buffer.capacity()
                    + " octets, une page en fait " + taillePage);
        }
    }
}

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Scanner;

public class DiskManager {


    private static final String NOM_FICHIER_ETAT = "diskmanager.state"; // Nom du fichier où on sauvegarde l'état du DiskManager (pas le contenu des pages)
    private static final String NOM_FICHIER_DONNEES = "data.bin"; // Nom du fichier où sont stockées les pages (le contenu écrit avec WritePage)
    private String dossier; // Le dossier de travail (ex : "/home/moi/mon_sgbd")
    private int taillePage;  // La taille d'une page en octets (ex : 4096)
    private int nombrePages; // Combien de pages ont été créées au total
    private ArrayList<Integer> pagesLibres = new ArrayList<Integer>();  // Les numéros des pages désallouées, réutilisables par AllocPage

    // ---------------------------------------------------------------
    // Init : on prépare le DiskManager sur un dossier
    // ---------------------------------------------------------------
    public void Init(String dmDir, int pageSize) {

        // 1. On efface tout ce qu'on savait avant
        dossier = dmDir;
        taillePage = pageSize;
        nombrePages = 0;
        pagesLibres = new ArrayList<Integer>();

        // 2. On regarde si le dossier a déjà un fichier d'état
        File fichierEtat = new File(dmDir, NOM_FICHIER_ETAT);

        if (fichierEtat.exists()) {
            // Dossier déjà utilisé : on relit ce qui a été sauvegardé
            try {
                Scanner lecteur = new Scanner(fichierEtat);

                int taillePageSauvegardee = lecteur.nextInt();
                if (taillePageSauvegardee != pageSize) {
                    System.out.println("Erreur : taille de page incompatible");
                    // (la vraie gestion d'erreur viendra au TP suivant)
                }

                nombrePages = lecteur.nextInt();

                while (lecteur.hasNextInt()) {
                    pagesLibres.add(lecteur.nextInt());
                }

                lecteur.close();
            } catch (Exception e) {
                System.out.println("Erreur de lecture : " + e.getMessage());
            }
        }
        // Sinon : dossier vide, on part de zéro, il n'y a rien d'autre à faire
    }

    // ---------------------------------------------------------------
    // Save : on écrit l'état dans le fichier
    // Le fichier ressemble à ça :
    //   4096        <- taille d'une page
    //   5           <- nombre de pages
    //   1           <- pages libres (une par ligne)
    //   3
    // ---------------------------------------------------------------
    public void Save() {
        File fichierEtat = new File(dossier, NOM_FICHIER_ETAT);

        try {
            PrintWriter ecrivain = new PrintWriter(fichierEtat);

            ecrivain.println(taillePage);
            ecrivain.println(nombrePages);
            for (int numeroPage : pagesLibres) {
                ecrivain.println(numeroPage);
            }

            ecrivain.close();
        } catch (Exception e) {
            System.out.println("Erreur d'écriture : " + e.getMessage());
        }
    }


    public void ReadPage(IPageId ipid, ByteBuffer buffer) {

    // 1. On vérifie que l'identifiant est valable
    if (ipid == null || !(ipid instanceof PageId)) {
        return;
    }
    PageId idPage = (PageId) ipid;

    // 2. On retrouve le fichier et la position de la page dans ce fichier
    File fichierDonnees = new File(dossier, NOM_FICHIER_DONNEES);
    long position = (long) idPage.getIndex() * taillePage;

    // 3. On lit taillePage octets à cette position, directement dans le buffer
    try (RandomAccessFile fichier = new RandomAccessFile(fichierDonnees, "r")) {
        fichier.seek(position);
        fichier.read(buffer.array(), 0, taillePage);
    } catch (IOException e) {
        System.out.println("Erreur de lecture de la page : " + e.getMessage());
    }
}

// ---------------------------------------------------------------
// WritePage : on copie le contenu du buffer dans la page indiquée
// (c'est l'inverse de ReadPage)
// ---------------------------------------------------------------
public void WritePage(IPageId ipid, ByteBuffer buffer) {

    // 1. On vérifie que l'identifiant est valable
    if (ipid == null || !(ipid instanceof PageId)) {
        return;
    }
    PageId idPage = (PageId) ipid;

    // 2. On refuse d'écrire dans une page qui n'a jamais été allouée
    if (idPage.getIndex() < 0 || idPage.getIndex() >= nombrePages) {
        System.out.println("Erreur : page inexistante " + idPage);
        return;
    }

    // 3. On retrouve le fichier et la position de la page dans ce fichier
    File fichierDonnees = new File(dossier, NOM_FICHIER_DONNEES);
    long position = (long) idPage.getIndex() * taillePage;

    try (RandomAccessFile fichier = new RandomAccessFile(fichierDonnees, "rw")) {
        fichier.seek(position);

        // 4. On repart du 1er octet du buffer, pour être sûr de tout copier
        buffer.rewind();

        // 5. On copie taillePage octets du buffer dans un tableau, puis on les écrit
        byte[] contenuPage = new byte[taillePage];
        buffer.get(contenuPage);
        fichier.write(contenuPage);

        // 6. On remet le buffer au début, comme on l'a trouvé
        buffer.rewind();
    } catch (IOException e) {
        System.out.println("Erreur d'écriture de la page : " + e.getMessage());
    }
}

    // ---------------------------------------------------------------
    // AllocPage : on donne une page à la couche du dessus
    // - s'il y a une page libre, on la réutilise
    // - sinon, on rajoute une page à la fin du fichier
    // ---------------------------------------------------------------
    public IPageId AllocPage() {
        int numeroPage;

        if (pagesLibres.size() > 0) {
            // Page déjà présente dans le fichier, rien à faire sur le disque
            numeroPage = pagesLibres.remove(0);
        } else {
            // Nouvelle page à la fin
            numeroPage = nombrePages;
            nombrePages++;

            try {
                // On agrandit le fichier d'une page (remplie de zéros)
                RandomAccessFile fichier = new RandomAccessFile(new File(dossier, NOM_FICHIER_DONNEES), "rw");
                fichier.setLength((long) nombrePages * taillePage);
                fichier.close();
            } catch (Exception e) {
                System.out.println("Erreur d'allocation : " + e.getMessage());
            }
        }

        return new PageId(numeroPage);
    }

    // ---------------------------------------------------------------
    // DeallocPage : la page devient libre et pourra être réutilisée
    // (on ne touche pas au fichier, la page reste dedans)
    // ---------------------------------------------------------------
    public void DeallocPage(IPageId ipid) {
        int numeroPage = ((PageId) ipid).getIndex();

        if (numeroPage < 0 || numeroPage >= nombrePages) return; // page inexistante
        if (pagesLibres.contains(numeroPage)) return;           // déjà libre

        pagesLibres.add(numeroPage);
    }


}
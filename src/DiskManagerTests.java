import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

// ---------------------------------------------------------------
// Tests du DiskManager (sans JUnit : on lance juste le main)
//
//   javac -d out src/*.java
//   java -cp out DiskManagerTests            (graine aléatoire)
//   java -cp out DiskManagerTests 12345      (rejoue une graine précise)
//
// Deux catégories de tests :
//   - FONCTIONNEL : ce qui DOIT marcher dès maintenant. Un échec = un bug.
//   - ROBUSTESSE  : la gestion d'erreurs (TP suivant). Un échec est affiché
//                   en "A FAIRE" : c'est la liste de ce qu'il reste à gérer.
//                   Un mauvais appel doit être refusé par une exception
//                   "propre" (IllegalArgumentException, exception perso...),
//                   pas par un crash (NullPointerException, ClassCastException...)
//                   ni par un simple println qui laisse continuer.
//
// Chaque test travaille dans son propre dossier temporaire, supprimé à la fin.
// ---------------------------------------------------------------
public class DiskManagerTests {

    private static final String FICHIER_DONNEES = "data.bin";
    private static final String FICHIER_ETAT = "diskmanager.state";

    private enum Categorie { FONCTIONNEL, ROBUSTESSE }

    private interface Corps {
        void executer() throws Exception;
    }

    // Levée par les vérifications quand quelque chose ne va pas
    private static class EchecTest extends RuntimeException {
        EchecTest(String message) {
            super(message);
        }
    }

    // Exceptions qui trahissent un crash non maîtrisé plutôt qu'un vrai refus
    private static final List<Class<? extends Throwable>> CRASHS = Arrays.asList(
            NullPointerException.class,
            ClassCastException.class,
            IndexOutOfBoundsException.class,
            java.nio.BufferUnderflowException.class,
            java.nio.BufferOverflowException.class,
            UnsupportedOperationException.class,
            ArithmeticException.class,
            java.util.NoSuchElementException.class
    );

    private static final PrintStream console = System.out;
    private static ByteArrayOutputStream sortieCapturee;
    private static final List<File> dossiersTemporaires = new ArrayList<File>();

    private static int nbOk = 0;
    private static int nbEchecs = 0;
    private static int nbAFaire = 0;
    private static final List<String> echecs = new ArrayList<String>();
    private static final List<String> aFaire = new ArrayList<String>();

    private static long graine;
    private static final List<String> notes = new ArrayList<String>(); // affichées sous le résultat du test

    public static void main(String[] args) {
        graine = args.length > 0 ? Long.parseLong(args[0]) : System.nanoTime();
        console.println("=== Tests du DiskManager (graine = " + graine + ") ===");

        console.println();
        console.println("--- Fonctionnel ---");
        lancer("Init sur un dossier vide", Categorie.FONCTIONNEL, DiskManagerTests::testInitDossierVide);
        lancer("Ecriture/lecture d'une page de 4 octets", Categorie.FONCTIONNEL, DiskManagerTests::testEcriturePage);
        lancer("Pages de 1 octet", Categorie.FONCTIONNEL, DiskManagerTests::testPagesUnOctet);
        lancer("Toutes les valeurs d'octet (0x00 a 0xFF)", Categorie.FONCTIONNEL, DiskManagerTests::testToutesLesValeursOctet);
        lancer("Grande page (8192 octets aleatoires)", Categorie.FONCTIONNEL, DiskManagerTests::testGrandePage);
        lancer("Une nouvelle page est remplie de zeros", Categorie.FONCTIONNEL, DiskManagerTests::testNouvellePageAZero);
        lancer("Reecriture d'une page", Categorie.FONCTIONNEL, DiskManagerTests::testReecriture);
        lancer("Pages independantes + disposition dans data.bin", Categorie.FONCTIONNEL, DiskManagerTests::testPagesIndependantes);
        lancer("WritePage ne modifie pas le buffer", Categorie.FONCTIONNEL, DiskManagerTests::testBufferInchangeApresEcriture);
        lancer("Buffer plus grand que la page (ecriture)", Categorie.FONCTIONNEL, DiskManagerTests::testEcritureBufferTropGrand);
        lancer("Buffer plus grand que la page (lecture)", Categorie.FONCTIONNEL, DiskManagerTests::testLectureBufferTropGrand);
        lancer("Alloc : indices consecutifs et taille du fichier", Categorie.FONCTIONNEL, DiskManagerTests::testAllocConsecutive);
        lancer("Dealloc puis Alloc reutilise la page", Categorie.FONCTIONNEL, DiskManagerTests::testDeallocRealloc);
        lancer("Plusieurs pages liberees sont toutes reutilisees", Categorie.FONCTIONNEL, DiskManagerTests::testReutilisationMultiple);
        lancer("Double Dealloc ne donne pas 2 fois la meme page", Categorie.FONCTIONNEL, DiskManagerTests::testDoubleDealloc);
        lancer("Save puis Init : l'etat est restaure", Categorie.FONCTIONNEL, DiskManagerTests::testPersistance);
        lancer("Plusieurs Save : c'est le dernier qui compte", Categorie.FONCTIONNEL, DiskManagerTests::testSavesSuccessifs);
        lancer("Sans Save, l'etat n'est pas conserve", Categorie.FONCTIONNEL, DiskManagerTests::testSansSave);
        lancer("Init remet a zero un DiskManager deja utilise", Categorie.FONCTIONNEL, DiskManagerTests::testInitReinitialise);
        lancer("Deux DiskManager sur deux dossiers", Categorie.FONCTIONNEL, DiskManagerTests::testDeuxDossiers);
        lancer("2000 pages de 4 octets", Categorie.FONCTIONNEL, DiskManagerTests::testBeaucoupDePages);
        lancer("Stress aleatoire vs modele (pages de 1 octet)", Categorie.FONCTIONNEL, () -> testStressAleatoire(1, 1500));
        lancer("Stress aleatoire vs modele (pages de 4 octets)", Categorie.FONCTIONNEL, () -> testStressAleatoire(4, 3000));
        lancer("Stress aleatoire vs modele (pages de 64 octets)", Categorie.FONCTIONNEL, () -> testStressAleatoire(64, 3000));
        lancer("Stress aleatoire vs modele (pages de 4096 octets)", Categorie.FONCTIONNEL, () -> testStressAleatoire(4096, 800));

        console.println();
        console.println("--- Robustesse (gestion des erreurs) ---");
        lancer("WritePage(null)", Categorie.ROBUSTESSE, DiskManagerTests::testWritePageNull);
        lancer("ReadPage(null)", Categorie.ROBUSTESSE, DiskManagerTests::testReadPageNull);
        lancer("DeallocPage(null)", Categorie.ROBUSTESSE, DiskManagerTests::testDeallocNull);
        lancer("IPageId qui n'est pas un PageId", Categorie.ROBUSTESSE, DiskManagerTests::testAutreImplementationPageId);
        lancer("WritePage sur une page jamais allouee", Categorie.ROBUSTESSE, DiskManagerTests::testWritePageNonAllouee);
        lancer("WritePage sur un indice negatif", Categorie.ROBUSTESSE, DiskManagerTests::testWritePageNegative);
        lancer("ReadPage sur une page jamais allouee", Categorie.ROBUSTESSE, DiskManagerTests::testReadPageNonAllouee);
        lancer("DeallocPage sur une page jamais allouee", Categorie.ROBUSTESSE, DiskManagerTests::testDeallocNonAllouee);
        lancer("WritePage avec un buffer trop petit", Categorie.ROBUSTESSE, DiskManagerTests::testWriteBufferTropPetit);
        lancer("ReadPage avec un buffer trop petit", Categorie.ROBUSTESSE, DiskManagerTests::testReadBufferTropPetit);
        lancer("ReadPage avec un buffer direct (allocateDirect)", Categorie.ROBUSTESSE, DiskManagerTests::testReadBufferDirect);
        lancer("ReadPage avec un buffer 'slice' (arrayOffset != 0)", Categorie.ROBUSTESSE, DiskManagerTests::testReadBufferSlice);
        lancer("Init avec une taille de page <= 0", Categorie.ROBUSTESSE, DiskManagerTests::testInitTailleInvalide);
        lancer("Init avec une taille de page differente de la sauvegarde", Categorie.ROBUSTESSE, DiskManagerTests::testInitTailleIncompatible);
        lancer("Init avec un fichier d'etat corrompu", Categorie.ROBUSTESSE, DiskManagerTests::testEtatCorrompu);
        lancer("Init avec un fichier d'etat tronque", Categorie.ROBUSTESSE, DiskManagerTests::testEtatTronque);
        lancer("Init avec une page libre hors limites dans l'etat", Categorie.ROBUSTESSE, DiskManagerTests::testEtatIncoherent);
        lancer("AllocPage dans un dossier inexistant", Categorie.ROBUSTESSE, DiskManagerTests::testAllocDossierInexistant);
        lancer("AllocPage avant Init", Categorie.ROBUSTESSE, DiskManagerTests::testAllocSansInit);

        nettoyer();

        console.println();
        console.println("=== Bilan ===");
        console.println("  OK      : " + nbOk);
        console.println("  ECHECS  : " + nbEchecs + "   (bugs sur ce qui devrait deja marcher)");
        console.println("  A FAIRE : " + nbAFaire + "   (gestion d'erreurs pas encore faite)");
        if (!echecs.isEmpty()) {
            console.println();
            console.println("Echecs :");
            for (String e : echecs) console.println("  - " + e);
        }
        if (!aFaire.isEmpty()) {
            console.println();
            console.println("A faire pour la gestion des erreurs :");
            for (String e : aFaire) console.println("  - " + e);
        }
        console.println();
        console.println("Pour rejouer exactement ces tests : java DiskManagerTests " + graine);

        System.exit(nbEchecs == 0 ? 0 : 1);
    }

    // ===============================================================
    // Le "framework" de test
    // ===============================================================

    private static void lancer(String nom, Categorie categorie, Corps corps) {
        // On capture ce que le DiskManager affiche, pour repérer les "Erreur ..."
        sortieCapturee = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sortieCapturee, true));

        notes.clear();
        String probleme = null;
        try {
            corps.executer();
            String sortie = sortieCapturee.toString().trim();
            if (categorie == Categorie.FONCTIONNEL && sortie.contains("Erreur")) {
                probleme = "le DiskManager a affiche une erreur inattendue : " + premiereLigne(sortie);
            }
        } catch (EchecTest e) {
            probleme = e.getMessage();
        } catch (Throwable e) {
            probleme = "exception " + e.getClass().getSimpleName() + " : " + e.getMessage();
        } finally {
            System.setOut(console);
        }

        if (probleme == null) {
            nbOk++;
            console.println("  [OK]      " + nom);
        } else if (categorie == Categorie.FONCTIONNEL) {
            nbEchecs++;
            echecs.add(nom + " -> " + probleme);
            console.println("  [ECHEC]   " + nom);
            console.println("            " + probleme);
        } else {
            nbAFaire++;
            aFaire.add(nom + " -> " + probleme);
            console.println("  [A FAIRE] " + nom);
            console.println("            " + probleme);
        }
        for (String note : notes) console.println("            " + note);
    }

    // Vérifie qu'un appel invalide est refusé proprement par une exception
    private static void attendRefus(Corps appel) {
        int debut = sortieCapturee.size();
        try {
            appel.executer();
        } catch (Throwable e) {
            for (Class<? extends Throwable> crash : CRASHS) {
                if (crash.isInstance(e)) {
                    throw new EchecTest("crash non maitrise : " + e.getClass().getSimpleName()
                            + " (il faudrait une exception explicite avec un message clair)");
                }
            }
            return; // refus propre : c'est ce qu'on veut
        }
        String affiche = sortieCapturee.toString().substring(debut).trim();
        if (affiche.isEmpty()) {
            throw new EchecTest("accepte en silence, aucune erreur signalee");
        }
        throw new EchecTest("un message est affiche mais le programme continue (\""
                + premiereLigne(affiche) + "\") : il faudrait lever une exception");
    }

    private static void verifier(boolean condition, String message) {
        if (!condition) throw new EchecTest(message);
    }

    private static void verifierEgal(long attendu, long obtenu, String quoi) {
        if (attendu != obtenu) {
            throw new EchecTest(quoi + " : attendu " + attendu + ", obtenu " + obtenu);
        }
    }

    private static void verifierOctets(byte[] attendu, byte[] obtenu, String quoi) {
        if (!Arrays.equals(attendu, obtenu)) {
            throw new EchecTest(quoi + " : attendu " + hex(attendu) + ", obtenu " + hex(obtenu));
        }
    }

    private static String premiereLigne(String texte) {
        int fin = texte.indexOf('\n');
        return (fin < 0 ? texte : texte.substring(0, fin)).trim();
    }

    // ===============================================================
    // Utilitaires
    // ===============================================================

    private static File nouveauDossier() throws IOException {
        File dossier = Files.createTempDirectory("dm-test-").toFile();
        dossiersTemporaires.add(dossier);
        return dossier;
    }

    private static DiskManager nouveauDM(File dossier, int taillePage) {
        DiskManager dm = new DiskManager();
        dm.Init(dossier.getAbsolutePath(), taillePage);
        return dm;
    }

    private static void ecrire(DiskManager dm, int indice, byte[] contenu) {
        dm.WritePage(new PageId(indice), ByteBuffer.wrap(contenu.clone()));
    }

    private static byte[] lire(DiskManager dm, int indice, int taillePage) {
        ByteBuffer buffer = ByteBuffer.allocate(taillePage);
        dm.ReadPage(new PageId(indice), buffer);
        return buffer.array();
    }

    private static int indice(IPageId id) {
        verifier(id != null, "AllocPage a renvoye null");
        verifier(id instanceof PageId, "AllocPage n'a pas renvoye un PageId");
        return ((PageId) id).getIndex();
    }

    private static long tailleFichier(File dossier) {
        return new File(dossier, FICHIER_DONNEES).length();
    }

    private static byte[] aleatoire(Random r, int taille) {
        byte[] octets = new byte[taille];
        r.nextBytes(octets);
        return octets;
    }

    private static String hex(byte[] octets) {
        int max = Math.min(octets.length, 16);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", octets[i] & 0xFF));
        }
        if (octets.length > max) sb.append(" ... (").append(octets.length).append(" octets)");
        return sb.append(']').toString();
    }

    // Affiche le contenu d'une page, pratique avec des pages de 4 octets
    private static void afficherPage(String titre, byte[] page) {
        notes.add(titre + " = " + hex(page));
    }

    private static void nettoyer() {
        for (File dossier : dossiersTemporaires) supprimer(dossier);
    }

    private static void supprimer(File f) {
        File[] enfants = f.listFiles();
        if (enfants != null) {
            for (File enfant : enfants) supprimer(enfant);
        }
        f.delete();
    }

    // ===============================================================
    // Tests fonctionnels
    // ===============================================================

    private static void testInitDossierVide() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        verifierEgal(0, indice(dm.AllocPage()), "premiere page allouee");
        verifierEgal(4, tailleFichier(dossier), "taille de data.bin apres 1 page");
    }

    private static void testEcriturePage() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        int p = indice(dm.AllocPage());

        byte[] contenu = {1, 2, 3, 4};
        ecrire(dm, p, contenu);
        byte[] relu = lire(dm, p, 4);

        afficherPage("ecrit", contenu);
        afficherPage("relu ", relu);
        verifierOctets(contenu, relu, "page relue");
    }

    private static void testPagesUnOctet() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 1);
        for (int i = 0; i < 20; i++) {
            int p = indice(dm.AllocPage());
            verifierEgal(i, p, "indice de la page");
            ecrire(dm, p, new byte[]{(byte) (i * 7)});
        }
        verifierEgal(20, tailleFichier(dossier), "taille de data.bin (20 pages de 1 octet)");
        for (int i = 0; i < 20; i++) {
            verifierOctets(new byte[]{(byte) (i * 7)}, lire(dm, i, 1), "page " + i);
        }
    }

    private static void testToutesLesValeursOctet() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 256);
        int p = indice(dm.AllocPage());
        byte[] contenu = new byte[256];
        for (int i = 0; i < 256; i++) contenu[i] = (byte) i;
        ecrire(dm, p, contenu);
        verifierOctets(contenu, lire(dm, p, 256), "page avec les 256 valeurs");
    }

    private static void testGrandePage() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 8192);
        Random r = new Random(graine);
        byte[][] contenus = new byte[3][];
        for (int i = 0; i < 3; i++) {
            int p = indice(dm.AllocPage());
            contenus[i] = aleatoire(r, 8192);
            ecrire(dm, p, contenus[i]);
        }
        for (int i = 0; i < 3; i++) {
            verifierOctets(contenus[i], lire(dm, i, 8192), "page " + i);
        }
    }

    private static void testNouvellePageAZero() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 8);
        int p = indice(dm.AllocPage());
        // On pré-remplit le buffer pour être sûr que ReadPage l'écrase bien
        ByteBuffer buffer = ByteBuffer.allocate(8);
        Arrays.fill(buffer.array(), (byte) 0x7F);
        dm.ReadPage(new PageId(p), buffer);
        verifierOctets(new byte[8], buffer.array(), "nouvelle page");
    }

    private static void testReecriture() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        int p = indice(dm.AllocPage());
        ecrire(dm, p, new byte[]{9, 9, 9, 9});
        ecrire(dm, p, new byte[]{1, 0, 1, 0});
        verifierOctets(new byte[]{1, 0, 1, 0}, lire(dm, p, 4), "page apres reecriture");
        verifierEgal(4, tailleFichier(dossier), "taille de data.bin (la reecriture ne doit pas l'agrandir)");
    }

    private static void testPagesIndependantes() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        for (int i = 0; i < 3; i++) dm.AllocPage();

        ecrire(dm, 0, new byte[]{0x0A, 0x0A, 0x0A, 0x0A});
        ecrire(dm, 1, new byte[]{0x0B, 0x0B, 0x0B, 0x0B});
        ecrire(dm, 2, new byte[]{0x0C, 0x0C, 0x0C, 0x0C});
        ecrire(dm, 1, new byte[]{1, 2, 3, 4}); // on réécrit celle du milieu

        verifierOctets(new byte[]{0x0A, 0x0A, 0x0A, 0x0A}, lire(dm, 0, 4), "page 0 (voisine de gauche)");
        verifierOctets(new byte[]{1, 2, 3, 4}, lire(dm, 1, 4), "page 1");
        verifierOctets(new byte[]{0x0C, 0x0C, 0x0C, 0x0C}, lire(dm, 2, 4), "page 2 (voisine de droite)");

        // La page i doit être à l'octet i * taillePage dans data.bin
        byte[] brut = Files.readAllBytes(new File(dossier, FICHIER_DONNEES).toPath());
        byte[] attendu = {0x0A, 0x0A, 0x0A, 0x0A, 1, 2, 3, 4, 0x0C, 0x0C, 0x0C, 0x0C};
        afficherPage("data.bin", brut);
        verifierOctets(attendu, brut, "contenu brut de data.bin");
    }

    private static void testBufferInchangeApresEcriture() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        int p = indice(dm.AllocPage());
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{5, 6, 7, 8});
        dm.WritePage(new PageId(p), buffer);
        verifierOctets(new byte[]{5, 6, 7, 8}, buffer.array(), "contenu du buffer apres WritePage");
        verifierEgal(0, buffer.position(), "position du buffer apres WritePage");

        // Le même buffer doit pouvoir être réutilisé tel quel
        dm.WritePage(new PageId(p), buffer);
        verifierOctets(new byte[]{5, 6, 7, 8}, lire(dm, p, 4), "page apres 2 ecritures du meme buffer");
    }

    private static void testEcritureBufferTropGrand() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        ecrire(dm, 1, new byte[]{7, 7, 7, 7});
        // Buffer de 8 octets : seuls les 4 premiers doivent être écrits
        ecrire(dm, 0, new byte[]{1, 2, 3, 4, 9, 9, 9, 9});
        verifierOctets(new byte[]{1, 2, 3, 4}, lire(dm, 0, 4), "page 0");
        verifierOctets(new byte[]{7, 7, 7, 7}, lire(dm, 1, 4), "page 1 (ne doit pas etre ecrasee)");
        verifierEgal(8, tailleFichier(dossier), "taille de data.bin");
    }

    private static void testLectureBufferTropGrand() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        ecrire(dm, 0, new byte[]{1, 2, 3, 4});
        ecrire(dm, 1, new byte[]{5, 6, 7, 8});
        // Buffer de 8 octets rempli de 0x55 : seuls les 4 premiers doivent changer
        ByteBuffer buffer = ByteBuffer.allocate(8);
        Arrays.fill(buffer.array(), (byte) 0x55);
        dm.ReadPage(new PageId(0), buffer);
        verifierOctets(new byte[]{1, 2, 3, 4, 0x55, 0x55, 0x55, 0x55}, buffer.array(),
                "buffer (ReadPage ne doit lire qu'une page)");
    }

    private static void testAllocConsecutive() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 16);
        for (int i = 0; i < 10; i++) {
            verifierEgal(i, indice(dm.AllocPage()), "page allouee n°" + (i + 1));
            verifierEgal((i + 1) * 16L, tailleFichier(dossier), "taille de data.bin apres " + (i + 1) + " pages");
        }
    }

    private static void testDeallocRealloc() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        for (int i = 0; i < 3; i++) dm.AllocPage();
        dm.DeallocPage(new PageId(1));
        verifierEgal(12, tailleFichier(dossier), "taille de data.bin (Dealloc ne doit pas la reduire)");
        verifierEgal(1, indice(dm.AllocPage()), "la page liberee doit etre reutilisee");
        verifierEgal(3, indice(dm.AllocPage()), "plus de page libre : nouvelle page a la fin");
        verifierEgal(16, tailleFichier(dossier), "taille de data.bin");
    }

    private static void testReutilisationMultiple() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        for (int i = 0; i < 6; i++) dm.AllocPage();
        dm.DeallocPage(new PageId(4));
        dm.DeallocPage(new PageId(0));
        dm.DeallocPage(new PageId(2));

        Set<Integer> obtenues = new HashSet<Integer>();
        for (int i = 0; i < 3; i++) obtenues.add(indice(dm.AllocPage()));
        verifier(obtenues.equals(new HashSet<Integer>(Arrays.asList(0, 2, 4))),
                "pages reutilisees : attendu {0, 2, 4}, obtenu " + obtenues);
        verifierEgal(6, indice(dm.AllocPage()), "page suivante");
    }

    private static void testDoubleDealloc() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        dm.DeallocPage(new PageId(0));
        dm.DeallocPage(new PageId(0));
        int a = indice(dm.AllocPage());
        int b = indice(dm.AllocPage());
        verifier(a != b, "la page " + a + " a ete donnee deux fois ! (deux tables ecriraient dedans)");
    }

    private static void testPersistance() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        for (int i = 0; i < 5; i++) {
            int p = indice(dm.AllocPage());
            ecrire(dm, p, new byte[]{(byte) p, (byte) p, (byte) p, (byte) p});
        }
        dm.DeallocPage(new PageId(1));
        dm.DeallocPage(new PageId(3));
        dm.Save();
        verifier(new File(dossier, FICHIER_ETAT).exists(), "Save n'a pas cree " + FICHIER_ETAT);

        // On "redémarre" : nouvel objet, même dossier
        DiskManager dm2 = nouveauDM(dossier, 4);
        for (int p : new int[]{0, 2, 4}) {
            verifierOctets(new byte[]{(byte) p, (byte) p, (byte) p, (byte) p}, lire(dm2, p, 4),
                    "page " + p + " apres redemarrage");
        }
        Set<Integer> reutilisees = new HashSet<Integer>();
        reutilisees.add(indice(dm2.AllocPage()));
        reutilisees.add(indice(dm2.AllocPage()));
        verifier(reutilisees.equals(new HashSet<Integer>(Arrays.asList(1, 3))),
                "pages libres apres redemarrage : attendu {1, 3}, obtenu " + reutilisees);
        verifierEgal(5, indice(dm2.AllocPage()), "nouvelle page apres redemarrage");
    }

    private static void testSavesSuccessifs() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        dm.DeallocPage(new PageId(0));
        dm.Save();
        dm.AllocPage(); // reprend la 0
        dm.AllocPage(); // nouvelle : 2
        dm.Save();

        DiskManager dm2 = nouveauDM(dossier, 4);
        verifierEgal(3, indice(dm2.AllocPage()), "page allouee apres rechargement (aucune page libre)");
    }

    private static void testSansSave() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        dm.Save();
        dm.AllocPage(); // pas sauvegardée

        DiskManager dm2 = nouveauDM(dossier, 4);
        verifierEgal(2, indice(dm2.AllocPage()), "page allouee (l'etat vient du dernier Save)");
    }

    private static void testInitReinitialise() throws Exception {
        File dossierA = nouveauDossier();
        File dossierB = nouveauDossier();
        DiskManager dm = nouveauDM(dossierA, 4);
        dm.AllocPage();
        dm.AllocPage();
        dm.AllocPage();
        dm.DeallocPage(new PageId(1));

        dm.Init(dossierB.getAbsolutePath(), 8);
        verifierEgal(0, indice(dm.AllocPage()), "premiere page dans le nouveau dossier");
        verifierEgal(8, tailleFichier(dossierB), "taille de data.bin dans le nouveau dossier");
        verifierEgal(12, tailleFichier(dossierA), "l'ancien dossier ne doit pas bouger");
    }

    private static void testDeuxDossiers() throws Exception {
        File dossierA = nouveauDossier();
        File dossierB = nouveauDossier();
        DiskManager a = nouveauDM(dossierA, 4);
        DiskManager b = nouveauDM(dossierB, 4);
        int pa = indice(a.AllocPage());
        int pb = indice(b.AllocPage());
        verifierEgal(0, pa, "page dans A");
        verifierEgal(0, pb, "page dans B");
        ecrire(a, pa, new byte[]{1, 1, 1, 1});
        ecrire(b, pb, new byte[]{2, 2, 2, 2});
        verifierOctets(new byte[]{1, 1, 1, 1}, lire(a, pa, 4), "page 0 de A");
        verifierOctets(new byte[]{2, 2, 2, 2}, lire(b, pb, 4), "page 0 de B");
    }

    private static void testBeaucoupDePages() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        int n = 2000;
        long debut = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int p = indice(dm.AllocPage());
            dm.WritePage(new PageId(p), ByteBuffer.allocate(4).putInt(0, p));
        }
        for (int i = 0; i < n; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            dm.ReadPage(new PageId(i), buffer);
            verifierEgal(i, buffer.getInt(0), "entier stocke dans la page " + i);
        }
        verifierEgal(n * 4L, tailleFichier(dossier), "taille de data.bin");
        long ms = (System.nanoTime() - debut) / 1_000_000;
        notes.add("(" + n + " alloc + ecritures + lectures en " + ms + " ms)");
    }

    // On fait plein d'opérations au hasard, en parallèle sur le DiskManager et
    // sur un "modèle" en mémoire (ce que le disque est censé contenir), et on
    // vérifie à chaque lecture que les deux sont d'accord. De temps en temps on
    // fait Save + redémarrage pour tester la persistance au milieu de tout ça.
    private static void testStressAleatoire(int taillePage, int nbOperations) throws Exception {
        Random r = new Random(graine ^ taillePage);
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, taillePage);

        Map<Integer, byte[]> disque = new HashMap<Integer, byte[]>(); // contenu attendu de chaque page
        List<Integer> allouees = new ArrayList<Integer>();
        Set<Integer> libres = new HashSet<Integer>();
        int nbPages = 0;
        int nbRedemarrages = 0;

        for (int op = 0; op < nbOperations; op++) {
            int de = r.nextInt(100);
            String contexte = "operation " + op + " (taille " + taillePage + ")";

            if (de < 30 || allouees.isEmpty()) {
                int p = indice(dm.AllocPage());
                verifier(!allouees.contains(p), contexte + " : AllocPage a donne la page " + p + " deja allouee");
                if (!libres.isEmpty()) {
                    verifier(libres.contains(p), contexte + " : il y avait des pages libres " + libres
                            + " mais AllocPage a donne " + p);
                    libres.remove(p);
                } else {
                    verifierEgal(nbPages, p, contexte + " : nouvelle page");
                    nbPages++;
                    disque.put(p, new byte[taillePage]); // une nouvelle page est à zéro
                }
                allouees.add(p);
            } else if (de < 45) {
                int p = allouees.remove(r.nextInt(allouees.size()));
                dm.DeallocPage(new PageId(p));
                libres.add(p);
            } else if (de < 72) {
                int p = allouees.get(r.nextInt(allouees.size()));
                byte[] contenu = aleatoire(r, taillePage);
                ecrire(dm, p, contenu);
                disque.put(p, contenu);
            } else if (de < 97) {
                int p = allouees.get(r.nextInt(allouees.size()));
                verifierOctets(disque.get(p), lire(dm, p, taillePage), contexte + " : page " + p);
            } else {
                dm.Save();
                dm = nouveauDM(dossier, taillePage);
                nbRedemarrages++;
            }
        }

        verifierEgal((long) nbPages * taillePage, tailleFichier(dossier), "taille finale de data.bin");
        dm.Save();
        dm = nouveauDM(dossier, taillePage);
        for (int p : allouees) {
            verifierOctets(disque.get(p), lire(dm, p, taillePage), "verification finale, page " + p);
        }
        notes.add("(" + nbOperations + " operations, " + nbPages + " pages, "
                + nbRedemarrages + " redemarrages)");
    }

    // ===============================================================
    // Tests de robustesse (gestion des erreurs, à faire)
    // ===============================================================

    private static void testWritePageNull() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> dm.WritePage(null, ByteBuffer.allocate(4)));
    }

    private static void testReadPageNull() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> dm.ReadPage(null, ByteBuffer.allocate(4)));
    }

    private static void testDeallocNull() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> dm.DeallocPage(null));
    }

    private static void testAutreImplementationPageId() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        IPageId bizarre = new IPageId() { };
        attendRefus(() -> dm.DeallocPage(bizarre));
    }

    private static void testWritePageNonAllouee() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        attendRefus(() -> ecrire(dm, 99, new byte[]{1, 2, 3, 4}));
        verifierEgal(4, tailleFichier(dossier), "data.bin ne doit pas grossir");
    }

    private static void testWritePageNegative() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> ecrire(dm, -1, new byte[]{1, 2, 3, 4}));
    }

    private static void testReadPageNonAllouee() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> lire(dm, 42, 4));
    }

    private static void testDeallocNonAllouee() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        attendRefus(() -> dm.DeallocPage(new PageId(42)));
    }

    private static void testWriteBufferTropPetit() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 8);
        dm.AllocPage();
        attendRefus(() -> dm.WritePage(new PageId(0), ByteBuffer.allocate(3)));
    }

    private static void testReadBufferTropPetit() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 8);
        dm.AllocPage();
        attendRefus(() -> dm.ReadPage(new PageId(0), ByteBuffer.allocate(3)));
    }

    // Ici ce n'est pas un refus qu'on attend : ça devrait juste marcher
    private static void testReadBufferDirect() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        ecrire(dm, 0, new byte[]{1, 2, 3, 4});
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        dm.ReadPage(new PageId(0), direct);
        byte[] relu = new byte[4];
        direct.get(0, relu);
        verifierOctets(new byte[]{1, 2, 3, 4}, relu, "page lue dans un buffer direct");
    }

    // buffer.array() renvoie TOUT le tableau, il faut tenir compte de arrayOffset()
    private static void testReadBufferSlice() throws Exception {
        DiskManager dm = nouveauDM(nouveauDossier(), 4);
        dm.AllocPage();
        ecrire(dm, 0, new byte[]{1, 2, 3, 4});
        byte[] grand = new byte[12];
        ByteBuffer morceau = ByteBuffer.wrap(grand, 4, 4).slice(); // vue sur grand[4..7]
        dm.ReadPage(new PageId(0), morceau);
        verifierOctets(new byte[]{0, 0, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0}, grand,
                "tableau sous-jacent (la page doit atterrir dans grand[4..7])");
    }

    private static void testInitTailleInvalide() throws Exception {
        File dossier = nouveauDossier();
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), 0));
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), -4));
    }

    private static void testInitTailleIncompatible() throws Exception {
        File dossier = nouveauDossier();
        DiskManager dm = nouveauDM(dossier, 4);
        dm.AllocPage();
        dm.AllocPage();
        dm.AllocPage();
        dm.Save();
        // Si on accepte, la page 1 serait lue aux octets 8..15 au lieu de 4..7 : tout est décalé
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), 8));
    }

    private static void testEtatCorrompu() throws Exception {
        File dossier = nouveauDossier();
        Files.write(new File(dossier, FICHIER_ETAT).toPath(), "n'importe quoi".getBytes(StandardCharsets.UTF_8));
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), 4));
    }

    private static void testEtatTronque() throws Exception {
        File dossier = nouveauDossier();
        Files.write(new File(dossier, FICHIER_ETAT).toPath(), "4\n".getBytes(StandardCharsets.UTF_8));
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), 4));
    }

    // État qui dit "2 pages" mais "la page 7 est libre" : AllocPage donnerait une page hors du fichier
    private static void testEtatIncoherent() throws Exception {
        File dossier = nouveauDossier();
        Files.write(new File(dossier, FICHIER_DONNEES).toPath(), new byte[8]);
        Files.write(new File(dossier, FICHIER_ETAT).toPath(), "4\n2\n7\n".getBytes(StandardCharsets.UTF_8));
        attendRefus(() -> new DiskManager().Init(dossier.getAbsolutePath(), 4));
    }

    private static void testAllocDossierInexistant() throws Exception {
        File dossier = new File(nouveauDossier(), "existe-pas");
        DiskManager dm = nouveauDM(dossier, 4);
        String probleme = null;
        try {
            attendRefus(dm::AllocPage);
        } catch (EchecTest e) {
            probleme = e.getMessage();
        }
        // Après l'échec, le DiskManager ne doit pas croire qu'il a une page de plus
        verifier(dossier.mkdirs(), "impossible de creer le dossier de test");
        int p = indice(dm.AllocPage());
        if (p != 0) {
            probleme = (probleme == null ? "" : probleme + " ; de plus, ")
                    + "l'allocation ratee a quand meme compte une page (prochaine page = " + p + " au lieu de 0)";
        }
        if (probleme != null) throw new EchecTest(probleme);
    }

    private static void testAllocSansInit() throws Exception {
        // Sans Init, dossier vaut null : attention à ne pas créer data.bin n'importe où
        File fichierCourant = new File(FICHIER_DONNEES);
        boolean existaitDeja = fichierCourant.exists();
        try {
            attendRefus(() -> new DiskManager().AllocPage());
        } catch (EchecTest e) {
            if (!existaitDeja && fichierCourant.exists()) {
                throw new EchecTest(e.getMessage() + " ; et un data.bin a ete cree dans le dossier courant ("
                        + Path.of("").toAbsolutePath() + ") !");
            }
            throw e;
        } finally {
            if (!existaitDeja) fichierCourant.delete();
        }
    }
}

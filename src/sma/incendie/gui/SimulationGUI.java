package sma.incendie.gui;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.Timer;

/**
 * SimulationGUI v3 — Interface graphique améliorée pour la forêt.
 * 
 * Améliorations :
 * - Flammes proportionnelles au danger (plus le danger est élevé, plus il y a de flammes)
 * - Propagation du feu dans chaque zone (multiples points d'incendie)
 * - Agents positionnés sur la zone concernée (pompiers dispersés, véhicules en bas, hélicos au-dessus)
 * - Effets visuels : hélicoptères qui arrosent, pompiers avec lance à eau
 * - Icônes agrandies pour meilleure visibilité
 */
public class SimulationGUI extends Agent {

    // ── Données partagées ─────────────────────────────────────────────────────
    private volatile int    dangerGlobal     = 0;
    private volatile String niveauIntervention = "NORMAL";
    private volatile int    pompiersDeployes  = 0;
    private volatile int    vehiculesDeployes = 0;
    private volatile int    helicosDeployes   = 0;
    private volatile int    progressionMoyenne = 0;

    // Météo
    private volatile double vitesseVent   = 0;
    private volatile String directionVent = "—";
    private volatile double temperature   = 22;
    private volatile double humidite      = 60;
    private volatile int    risqueMeteo   = 0;

    // Zones capteurs
    private final Map<String, ZoneInfo> zones = Collections.synchronizedMap(new LinkedHashMap<>());

    // Agents G3
    private final Map<String, AgentInfo> agents = Collections.synchronizedMap(new LinkedHashMap<>());

    // Log
    private final CopyOnWriteArrayList<LogEntry> logs = new CopyOnWriteArrayList<>();

    // Compteurs
    private volatile int nbAlertes = 0;
    private volatile int nbOrdres  = 0;

    // ── Composants Swing ──────────────────────────────────────────────────────
    private JFrame      frame;
    private ForetPanel  foretPanel;
    private NiveauPanel niveauPanel;
    private MeteoPanel  meteoPanel;
    private AgentPanel  agentPanel;
    private LogPanel    logPanel;
    private Timer       animTimer;

    // ── Positions agents sur la carte ─────────────────────────────────────────
    private final Set<String> agentsSurZone = Collections.synchronizedSet(new HashSet<>());
    
    // Map pour stocker la zone assignée à chaque agent (si connue)
    private final Map<String, String> agentZone = Collections.synchronizedMap(new HashMap<>());

    // ── Activation MadKit ─────────────────────────────────────────────────────
    @Override
    protected void activate() {
        getLogger().info("=== SimulationGUI v3 : Démarrage ===");

        // Initialiser les zones (les 4 capteurs)
        for (String z : new String[]{"Zone_Nord", "Zone_Sud", "Zone_Est", "Zone_Ouest"}) {
            zones.put(z, new ZoneInfo(z));
        }

        // Rejoindre tous les groupes pour tout voir
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, false, null);
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);

        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_COORD_SURV, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_DECIDEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_SUPERVISEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_ANALYSTE_SIT, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_POMPIER, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_CONDUCTEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_RENFORT, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_OBSERVATEUR, null);

        SwingUtilities.invokeLater(this::construireInterface);
    }

    @Override
    protected void live() {
        while (true) {
            Message msg;
            while ((msg = nextMessage()) != null) {
                traiterMessage(msg);
            }
            try { Thread.sleep(150); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        if (animTimer != null) animTimer.stop();
        if (frame != null)     frame.dispose();
    }

    // ── Traitement des messages ────────────────────────────────────────────────

    private void traiterMessage(Message msg) {
        if (msg instanceof AlerteMessage) {
            AlerteMessage a = (AlerteMessage) msg;
            nbAlertes++;

            // Mettre à jour la zone correspondante
            ZoneInfo zi = zones.get(a.getZoneSource());
            if (zi != null) {
                zi.danger      = a.getIndiceDanger();
                zi.temperature = a.getTemperature();
                zi.humidite    = a.getHumidite();
                zi.enFeu       = a.getIndiceDanger() >= AGRConstants.SEUIL_ORANGE;
            }

            // Danger global = max de toutes les zones
            dangerGlobal = zones.values().stream()
                .mapToInt(z -> z.danger).max().orElse(0);

            // Niveau d'intervention
            niveauIntervention = calculerNiveau(dangerGlobal);

            // Mettre à jour l'agent source dans le panneau agents
            String nomSource = a.getSource();
            if (nomSource.contains("Drone") || nomSource.contains("drone")) {
                mettreAJourAgent(nomSource, "G1", AGRConstants.ROLE_DRONE,
                    "Confirmation → Danger=" + a.getIndiceDanger(), couleurDanger(a.getIndiceDanger()));
            } else {
                mettreAJourAgent(a.getZoneSource(), "G1", AGRConstants.ROLE_CAPTEUR,
                    "T=" + String.format("%.0f", a.getTemperature()) + "°C | Danger=" + a.getIndiceDanger(),
                    couleurDanger(a.getIndiceDanger()));
            }

            log("ALERTE", a.getZoneSource() + " | Danger=" + a.getIndiceDanger()
                + "/100 | " + a.getNiveauAlerte(), couleurDanger(a.getIndiceDanger()));

       } else if (msg instanceof OrdreMessage) {
    OrdreMessage o = (OrdreMessage) msg;
    nbOrdres++;

    if ("RAPPORT_FINAL".equals(o.getTypeAction())) {
        niveauIntervention = "FIN_ALERTE";
        dangerGlobal       = 0;
        agentsSurZone.clear();
        agentZone.clear();
        zones.values().forEach(z -> { z.enFeu = false; z.danger = 0; });
        // ← Réinitialiser les compteurs
        pompiersDeployes = 0;
        vehiculesDeployes = 0;
        helicosDeployes = 0;
        log("SYSTÈME", "=== FIN D'ALERTE OFFICIELLE ===", new Color(0, 200, 100));
    } else {
        // Compter les ressources déployées - CORRECTION
        String action = o.getTypeAction();
        if ("EXTINCTION".equals(action)) {
            pompiersDeployes++;
            log("ORDRE", "🚒 Pompier déployé (" + pompiersDeployes + "/5)", new Color(220, 80, 80));
        } else if ("SUPPORT_TRANSPORT".equals(action)) {
            vehiculesDeployes++;
            log("ORDRE", "🚛 Véhicule déployé (" + vehiculesDeployes + "/3)", new Color(255, 140, 0));
        } else if ("ARROSAGE_AERIEN".equals(action)) {
            helicosDeployes++;
            log("ORDRE", "🚁 Hélicoptère déployé (" + helicosDeployes + "/2)", new Color(180, 0, 200));
        } else {
            log("ORDRE", "#" + o.getIdOrdre() + " → " + action + " | " + o.getPriorite(),
                new Color(148, 0, 211));
        }
    }
}else if (msg instanceof RapportMessage) {
            RapportMessage r = (RapportMessage) msg;
            String nom    = r.getAgentEmetteur();
            String statut = r.getStatut();

            // Position sur la carte
            if ("SUR_ZONE".equals(statut) || "EXTINCTION".equals(statut)
                    || "ARROSAGE_EFFECTUE".equals(statut)) {
                agentsSurZone.add(nom);
                // Essayer de déterminer la zone à partir du message
                String zone = extraireZoneDuMessage(r.getObservations());
                if (zone != null) agentZone.put(nom, zone);
            } else if ("TERMINE".equals(statut) || "RETOUR".equals(statut)) {
                agentsSurZone.remove(nom);
                agentZone.remove(nom);
                // ← NOUVEAU : Décrémenter les compteurs quand un agent termine
        if (nom.contains("Pompier")) {
            pompiersDeployes = Math.max(0, pompiersDeployes - 1);
            log("INFO", "Pompier " + nom + " a terminé → " + pompiersDeployes + "/5 restants", new Color(100, 180, 100));
        } else if (nom.contains("Citerne")) {
            vehiculesDeployes = Math.max(0, vehiculesDeployes - 1);
            log("INFO", "Véhicule " + nom + " a terminé → " + vehiculesDeployes + "/3 restants", new Color(100, 180, 100));
        } else if (nom.contains("Helico")) {
            helicosDeployes = Math.max(0, helicosDeployes - 1);
            log("INFO", "Hélicoptère " + nom + " a terminé → " + helicosDeployes + "/2 restants", new Color(100, 180, 100));
        }
            }

            // Progression moyenne
            progressionMoyenne = r.getProgressionExtinction();

            // Déterminer le rôle
            String groupe = "G3";
            String role;
            if (nom.contains("Helico") || nom.contains("helico")) role = AGRConstants.ROLE_RENFORT;
            else if (nom.contains("Citerne") || nom.contains("Vehicule")) role = AGRConstants.ROLE_CONDUCTEUR;
            else role = AGRConstants.ROLE_POMPIER;

            Color c = r.estTermine() ? new Color(0, 180, 80) : new Color(220, 120, 0);
            mettreAJourAgent(nom, groupe, role, statut + " " + r.getProgressionExtinction() + "%", c);

            log("RAPPORT", nom + " | " + r.getProgressionExtinction() + "% | " + statut, c);

            if (r.estTermine()) {
                // Réduction du danger quand les agents terminent
                dangerGlobal = Math.max(0, dangerGlobal - 15);
            }

        } else if (msg instanceof MeteoMessage) {
            MeteoMessage m = (MeteoMessage) msg;
            vitesseVent   = m.getVitesseVent();
            directionVent = m.getDirectionVent();
            temperature   = m.getTemperature();
            humidite      = m.getHumidite();
            risqueMeteo   = m.getIndiceRisque();
            mettreAJourAgent("AgentMeteo", "G1", AGRConstants.ROLE_METEO,
                String.format("%.0f km/h %s | Risque=%d", vitesseVent, directionVent, risqueMeteo),
                new Color(30, 144, 255));
            log("MÉTÉO", String.format("Vent=%.0fkm/h %s | T=%.1f°C | H=%.0f%% | Risque=%d",
                vitesseVent, directionVent, temperature, humidite, risqueMeteo),
                new Color(30, 144, 255));

        } else if (msg instanceof SimpleMessage) {
            SimpleMessage sm = (SimpleMessage) msg;
            if (sm.isFin()) {
                niveauIntervention = "FIN_ALERTE";
                dangerGlobal       = 0;
                agentsSurZone.clear();
                agentZone.clear();
                zones.values().forEach(z -> { z.enFeu = false; z.danger = 0; });
                // Mettre tous les G3 en retour
                agents.forEach((k, ai) -> {
                    if ("G3".equals(ai.groupe))
                        mettreAJourAgent(ai.nom, "G3", ai.role, "RETOUR BASE", Color.GRAY);
                });
                log("SYSTÈME", "=== FIN D'ALERTE ===", new Color(0, 200, 100));
            } else if ("EXTINCTION_EN_COURS".equals(sm.getContenu())) {
                log("INFO", "Extinction en cours — capteurs notifiés", new Color(100, 180, 255));
            } else if ("INCENDIE_MAITRISE".equals(sm.getContenu())) {
                zones.values().forEach(z -> { z.enFeu = false; z.danger = Math.max(0, z.danger - 20); });
                log("SYSTÈME", "Incendie maîtrisé — retour à la normale", new Color(0, 200, 100));
            }
        }

        SwingUtilities.invokeLater(this::rafraichir);
    }

    private String extraireZoneDuMessage(String observations) {
        if (observations == null) return null;
        for (String zone : zones.keySet()) {
            if (observations.contains(zone)) return zone;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String calculerNiveau(int danger) {
        if (danger >= AGRConstants.SEUIL_CRITIQUE)    return "EXTREME";
        if (danger >= AGRConstants.SEUIL_ROUGE)       return "CRITIQUE";
        if (danger >= AGRConstants.SEUIL_ORANGE)      return "ROUGE";
        if (danger >= AGRConstants.SEUIL_SURVEILLANCE) return "ORANGE";
        return "NORMAL";
    }

    private Color couleurDanger(int danger) {
        if (danger < AGRConstants.SEUIL_SURVEILLANCE) return new Color(0, 200, 100);
        if (danger < AGRConstants.SEUIL_ORANGE)       return new Color(255, 200, 0);
        if (danger < AGRConstants.SEUIL_ROUGE)        return new Color(255, 130, 0);
        if (danger < AGRConstants.SEUIL_CRITIQUE)     return new Color(220, 40, 40);
        return new Color(180, 0, 255);
    }

    private int extraireNombre(String texte, int defaut) {
        try {
            String[] parts = texte.split(" ");
            for (String p : parts) {
                if (p.matches("\\d+")) return Integer.parseInt(p);
            }
        } catch (Exception ignored) {}
        return defaut;
    }

    private void mettreAJourAgent(String nom, String groupe, String role, String etat, Color c) {
        agents.put(nom, new AgentInfo(nom, groupe, role, etat, c));
    }

    private void log(String type, String message, Color c) {
        logs.add(0, new LogEntry(type, message, c));
        if (logs.size() > 300) logs.remove(logs.size() - 1);
    }

    private void rafraichir() {
        if (foretPanel  != null) foretPanel.repaint();
        if (niveauPanel != null) niveauPanel.repaint();
        if (meteoPanel  != null) meteoPanel.repaint();
        if (agentPanel  != null) agentPanel.actualiser();
        if (logPanel    != null) logPanel.repaint();
    }

    // ── Construction de l'interface ───────────────────────────────────────────

    private void construireInterface() {
        frame = new JFrame("SMA — Gestion Incendie Forêt (v3)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);

        JPanel bg = new JPanel(new BorderLayout(6, 6));
        bg.setBackground(new Color(18, 18, 28));
        bg.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── NORD : bandeau titre + niveau ──────────────────
        niveauPanel = new NiveauPanel();
        niveauPanel.setPreferredSize(new Dimension(1400, 70));
        bg.add(niveauPanel, BorderLayout.NORTH);

        // ── CENTRE ─────────────────────────────────────────
        JPanel centre = new JPanel(new BorderLayout(6, 0));
        centre.setOpaque(false);

        // Gauche : forêt
        foretPanel = new ForetPanel();
        foretPanel.setPreferredSize(new Dimension(680, 500));
        centre.add(foretPanel, BorderLayout.CENTER);

        // Droite : météo + agents
        JPanel droite = new JPanel(new BorderLayout(0, 6));
        droite.setOpaque(false);
        droite.setPreferredSize(new Dimension(390, 500));

        meteoPanel = new MeteoPanel();
        meteoPanel.setPreferredSize(new Dimension(390, 180));
        droite.add(meteoPanel, BorderLayout.NORTH);

        agentPanel = new AgentPanel();
        droite.add(agentPanel, BorderLayout.CENTER);

        centre.add(droite, BorderLayout.EAST);
        bg.add(centre, BorderLayout.CENTER);

        // ── SUD : log ──────────────────────────────────────
        logPanel = new LogPanel();
        logPanel.setPreferredSize(new Dimension(1400, 190));
        bg.add(logPanel, BorderLayout.SOUTH);

        frame.add(bg);
        frame.setVisible(true);

        animTimer = new Timer(40, e -> rafraichir());
        animTimer.start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PANNEAU 1 : Carte de la forêt (vue unique)
    // ══════════════════════════════════════════════════════════════════════════

    class ForetPanel extends JPanel {
      private int flammePhase = 0;
    private Timer localTimer;
    
    // Stocker les points de feu pour chaque zone - CLÉ UNIQUE PAR ZONE
    private final Map<String, List<Point>> pointsFeu = new HashMap<>();
    private final Map<String, List<Point>> pointsPropagation = new HashMap<>();
    private final Map<String, Integer> dernierDanger = new HashMap<>();

    ForetPanel() {
        setBackground(new Color(12, 35, 12));
        setBorder(titre("Forêt — Vue d'ensemble"));
        localTimer = new Timer(80, e -> { 
            flammePhase = (flammePhase + 1) % 16; 
            repaint(); 
        });
        localTimer.start();
    }
    
    private void initialiserPointsFeu(String zoneNom, int x, int y, int w, int h) {
        // Utiliser un seed unique basé sur le nom de la zone pour des points reproductibles
        Random rnd = new Random(zoneNom.hashCode() * 31L);
        List<Point> points = new ArrayList<>();
        int nbPoints = 8 + rnd.nextInt(8);  // Entre 8 et 15 points
        
        for (int i = 0; i < nbPoints; i++) {
            // Points uniquement à l'intérieur de la zone
            int px = x + 15 + rnd.nextInt(Math.max(1, w - 30));
            int py = y + 20 + rnd.nextInt(Math.max(1, h - 40));
            points.add(new Point(px, py));
        }
        pointsFeu.put(zoneNom, points);
        pointsPropagation.put(zoneNom, new ArrayList<>());
        dernierDanger.put(zoneNom, 0);
    }
    
    private void mettreAJourPropagation(String zoneNom, int danger, int x, int y, int w, int h) {
        int oldDanger = dernierDanger.getOrDefault(zoneNom, 0);
        if (danger > oldDanger + 10) {
            List<Point> prop = pointsPropagation.get(zoneNom);
            if (prop == null) {
                prop = new ArrayList<>();
                pointsPropagation.put(zoneNom, prop);
            }
            Random rnd = new Random(System.currentTimeMillis() % 10000 + zoneNom.hashCode());
            int nbNouveaux = Math.min(5, (danger - oldDanger) / 10);
            for (int n = 0; n < nbNouveaux && prop.size() < 15; n++) {
                int px = x + 15 + rnd.nextInt(Math.max(1, w - 30));
                int py = y + 20 + rnd.nextInt(Math.max(1, h - 40));
                prop.add(new Point(px, py));
            }
            dernierDanger.put(zoneNom, danger);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();

        // Fond forêt dégradé
        g2.setPaint(new GradientPaint(0, 0, new Color(8, 45, 8), w, h, new Color(18, 70, 18)));
        g2.fillRect(0, 0, w, h);

        // Dessiner les 4 zones capteurs (quadrants)
        int mx = w / 2, my = h / 2;
        int pad = 28;
        
        // Zone Nord
        dessinerZone(g2, pad, pad, mx-pad-4, my-pad-4, "Zone_Nord");
        
        // Zone Est
        dessinerZone(g2, mx+4, pad, mx-pad-4, my-pad-4, "Zone_Est");
        
        // Zone Sud
        dessinerZone(g2, pad, my+4, mx-pad-4, my-pad-4, "Zone_Sud");
        
        // Zone Ouest
        dessinerZone(g2, mx+4, my+4, mx-pad-4, my-pad-4, "Zone_Ouest");

        // Rose des vents (coin sup droit)
        dessinerRoseVent(g2, w - 55, 55, 40);

        // Légende
        dessinerLegende(g2, pad, h - 22);

        g2.dispose();
    }

    private void dessinerZone(Graphics2D g2, int x, int y, int w, int h, String nom) {
        ZoneInfo zi = zones.get(nom);
        if (zi == null) return;

        // Fond selon état
        Color fond;
        if (zi.enFeu)       fond = new Color(55, 12, 5);
        else if (zi.danger >= AGRConstants.SEUIL_SURVEILLANCE) fond = new Color(40, 30, 5);
        else                fond = new Color(14, 52, 14);
        g2.setColor(fond);
        g2.fillRoundRect(x, y, w, h, 14, 14);

        // Arbres (points verts) - DESSINÉS UNIQUEMENT DANS LEUR ZONE
        Random rnd = new Random(nom.hashCode());
        g2.setColor(new Color(22, 100, 22, 140));
        for (int i = 0; i < 30; i++) {
            int tx = x + 12 + rnd.nextInt(Math.max(1, w - 24));
            int ty = y + 22 + rnd.nextInt(Math.max(1, h - 44));
            int ts = 4 + rnd.nextInt(5);
            int[] px = {tx, tx - ts, tx + ts};
            int[] py = {ty - ts*2, ty, ty};
            g2.fillPolygon(px, py, 3);
        }

        // Flammes si en feu (avec propagation) - UNIQUEMENT DANS LEUR ZONE
        if (zi.enFeu) {
            // Initialiser les points de feu pour cette zone si nécessaire
            if (!pointsFeu.containsKey(nom)) {
                initialiserPointsFeu(nom, x, y, w, h);
            }
            mettreAJourPropagation(nom, zi.danger, x, y, w, h);
            dessinerFlammesPropagees(g2, x, y, w, h, nom, zi.danger);
            // Dessiner les agents sur cette zone
            dessinerAgentsSurZone(g2, x, y, w, h, nom);
        }

        // Bordure
        Color bord;
        if (zi.enFeu)      bord = new Color(255, 80, 0);
        else if (zi.danger >= AGRConstants.SEUIL_SURVEILLANCE) bord = new Color(255, 200, 0);
        else               bord = new Color(30, 100, 30);
        g2.setColor(bord);
        g2.setStroke(new BasicStroke(zi.enFeu ? 2.5f : 1.2f));
        g2.drawRoundRect(x, y, w, h, 14, 14);
        g2.setStroke(new BasicStroke(1f));

        // Nom de la zone
        g2.setFont(new Font("Consolas", Font.BOLD, 12));
        g2.setColor(Color.WHITE);
        g2.drawString(nom.replace("Zone_", ""), x + 8, y + 16);

        // Indicateurs T / H / Danger
        if (zi.danger > 0) {
            g2.setFont(new Font("Consolas", Font.PLAIN, 11));
            g2.setColor(new Color(220, 220, 180));
            g2.drawString(String.format("T=%.0f°C  H=%.0f%%", zi.temperature, zi.humidite),
                x + 8, y + h - 18);
            g2.setColor(couleurDanger(zi.danger));
            g2.setFont(new Font("Consolas", Font.BOLD, 12));
            g2.drawString("Danger: " + zi.danger + "/100", x + 8, y + h - 5);
        }
    }
    
    private void dessinerFlammesPropagees(Graphics2D g2, int x, int y, int w, int h, 
                                           String zoneNom, int danger) {
        List<Point> points = pointsFeu.get(zoneNom);
        if (points == null) return;
        
        List<Point> prop = pointsPropagation.getOrDefault(zoneNom, Collections.emptyList());
        
        // Le nombre de flammes actives dépend du danger
        int nbFlammesBase = Math.max(4, Math.min(points.size(), danger / 6));
        int nbFlammesProp = Math.min(prop.size(), danger / 12);
        int nbFlammesActives = nbFlammesBase + nbFlammesProp;
        
        // Dessiner les flammes de base
        for (int i = 0; i < nbFlammesBase && i < points.size(); i++) {
            Point p = points.get(i);
            // Vérifier que le point est bien dans la zone
            if (p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h) {
                dessinerFlamme(g2, p.x, p.y, danger, i);
            }
        }
        
        // Dessiner les flammes de propagation
        for (int i = 0; i < nbFlammesProp && i < prop.size(); i++) {
            Point p = prop.get(i);
            if (p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h) {
                dessinerFlamme(g2, p.x, p.y, danger, i + points.size());
            }
        }
        
        // Effet de lueur globale sur la zone (optionnel)
        if (danger > 50) {
            float alpha = Math.min(0.2f, (danger - 50) / 250.0f);
            g2.setColor(new Color(1f, 0.3f, 0f, alpha));
            g2.fillRoundRect(x + 4, y + 4, w - 8, h - 8, 10, 10);
        }
    }
    
    private void dessinerFlamme(Graphics2D g2, int fx, int fy, int danger, int seed) {
        // Scintillement
        double scintille = Math.sin(flammePhase * 0.45 + seed * 1.2);
        
        // Taille proportionnelle au danger
        int tailleBase = 6 + danger / 10;
        int taille = tailleBase + (int)(scintille * 4);
        taille = Math.max(5, Math.min(25, taille));
        
        // Couleur selon danger
        Color flammeColor;
        if (danger >= 85) {
            flammeColor = new Color(255, 255, 180, 220);
        } else if (danger >= 70) {
            flammeColor = new Color(255, 220, 80, 210);
        } else if (danger >= 50) {
            flammeColor = new Color(255, 155, 40, 195);
        } else if (danger >= 35) {
            flammeColor = new Color(255, 90, 10, 175);
        } else {
            flammeColor = new Color(220, 60, 0, 150);
        }
        
        // Flamme principale
        int[] px = {fx, fx - taille/2, fx + taille/2};
        int[] py = {fy - taille*2, fy, fy};
        g2.setColor(flammeColor);
        g2.fillPolygon(px, py, 3);
        
        // Flamme secondaire (si danger suffisant)
        if (danger > 40) {
            int taille2 = Math.max(3, taille - 4);
            int[] px2 = {fx - 4, fx - taille2/2 - 2, fx + taille2/2 - 4};
            int[] py2 = {fy - taille2 - 4, fy + 2, fy + 2};
            g2.setColor(new Color(255, 100, 0, 160));
            g2.fillPolygon(px2, py2, 3);
        }
        
        // Effet de lueur
        int lueur = taille + 4;
        g2.setColor(new Color(255, 80, 0, 40));
        g2.fillOval(fx - lueur, fy - lueur, lueur * 2, lueur * 2);
    }
        
        private void dessinerAgentsSurZone(Graphics2D g2, int x, int y, int w, int h, String zoneNom) {
            ZoneInfo zi = zones.get(zoneNom);
            if (zi == null || !zi.enFeu) return;
            
            // Récupérer les agents sur zone
            List<String> pompiers = new ArrayList<>();
            List<String> vehicules = new ArrayList<>();
            List<String> helicos = new ArrayList<>();
            
            synchronized (agentsSurZone) {
                for (String nom : agentsSurZone) {
                    if (nom.contains("Pompier")) pompiers.add(nom);
                    else if (nom.contains("Helico")) helicos.add(nom);
                    else if (nom.contains("Citerne")) vehicules.add(nom);
                }
            }
            
            Random rnd = new Random(zoneNom.hashCode());
            
            // Pompiers : dispersés dans la zone (icônes plus grandes)
            for (int i = 0; i < pompiers.size() && i < 6; i++) {
                int px = x + 20 + rnd.nextInt(Math.max(1, w - 40));
                int py = y + 30 + rnd.nextInt(Math.max(1, h - 60));
                dessinerIconePompierAvecEau(g2, px, py, flammePhase);
            }
            
            // Véhicules : en bas de la zone (icônes plus grandes)
            for (int i = 0; i < vehicules.size() && i < 3; i++) {
                int px = x + 30 + i * 50;
                int py = y + h - 35;
                dessinerIconeVehicule(g2, px, py);
            }
            
            // Hélicoptères : au-dessus de la zone (icônes plus grandes)
            for (int i = 0; i < helicos.size() && i < 2; i++) {
                int px = x + w / 2 - 40 + i * 70;
                int py = y - 30;
                dessinerHelicoVolantAvecEau(g2, px, py, flammePhase);
            }
        }
        
        private void dessinerIconePompierAvecEau(Graphics2D g2, int x, int y, int phase) {
            // Corps (plus grand : 20x20 au lieu de 14x14)
            g2.setColor(new Color(220, 40, 40));
            g2.fillRoundRect(x, y, 20, 20, 6, 6);
            
            // Casque (plus grand)
            g2.setColor(new Color(255, 80, 0));
            g2.fillArc(x + 2, y - 6, 16, 12, 0, 180);
            
            // Lance à eau (plus longue)
            g2.setColor(new Color(100, 100, 100));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(x + 17, y + 10, x + 32, y + 7);
            
            // Jet d'eau (plus visible et animé)
            g2.setColor(new Color(100, 200, 255, 200));
            g2.setStroke(new BasicStroke(2.5f));
            for (int i = 0; i < 8; i++) {
                int dx = x + 32 + i * 4;
                int dy = y + 7 - (phase + i * 2) % 6;
                g2.drawLine(dx, dy, dx + 5, dy - 2);
                
                // Gouttes d'eau
                if (i % 2 == 0) {
                    g2.fillOval(dx + 2, dy + 2, 3, 4);
                }
            }
            g2.setStroke(new BasicStroke(1f));
            
            // Détails (visage)
            g2.setColor(Color.YELLOW);
            g2.fillOval(x + 14, y + 4, 3, 3);
            g2.fillOval(x + 14, y + 12, 3, 3);
        }
        
        private void dessinerIconeVehicule(Graphics2D g2, int x, int y) {
            // Corps (plus grand : 30x16 au lieu de 22x12)
            g2.setColor(new Color(255, 140, 0));
            g2.fillRoundRect(x, y + 5, 30, 16, 6, 6);
            
            // Cabine
            g2.setColor(new Color(200, 200, 220));
            g2.fillRoundRect(x + 16, y + 6, 14, 10, 4, 4);
            
            // Roues (plus grandes)
            g2.setColor(Color.DARK_GRAY);
            g2.fillOval(x + 5, y + 19, 8, 8);
            g2.fillOval(x + 19, y + 19, 8, 8);
            
            // Gyrophare (clignotant)
            g2.setColor(new Color(255, 0, 0, 150 + (int)(Math.sin(System.currentTimeMillis() * 0.01) * 50)));
            g2.fillOval(x + 24, y + 2, 6, 6);
            
            // Réservoir d'eau
            g2.setColor(new Color(100, 150, 200));
            g2.fillRoundRect(x + 2, y + 8, 12, 10, 3, 3);
        }
        
        private void dessinerHelicoVolantAvecEau(Graphics2D g2, int x, int y, int phase) {
            // Corps (plus grand : 45x16 au lieu de 35x12)
            g2.setColor(new Color(100, 100, 150));
            g2.fillRoundRect(x, y, 45, 16, 8, 8);
            
            // Rotor principal (plus grand)
            g2.setColor(new Color(80, 80, 120));
            g2.setStroke(new BasicStroke(2.5f));
            double angle = Math.toRadians(phase * 45);
            int rx = x + 22;
            int ry = y - 3;
            int rLen = 38;
            int x1 = rx + (int)(rLen * Math.cos(angle));
            int y1 = ry + (int)(rLen * Math.sin(angle));
            int x2 = rx - (int)(rLen * Math.cos(angle));
            int y2 = ry - (int)(rLen * Math.sin(angle));
            g2.drawLine(x1, y1, x2, y2);
            
            // Rotor arrière
            g2.drawLine(x + 42, y + 8, x + 55, y + 8);
            
            // Cabine
            g2.setColor(new Color(150, 150, 200));
            g2.fillRoundRect(x + 26, y + 3, 16, 10, 5, 5);
            
            // Arrosage (gouttes d'eau plus grandes et plus nombreuses)
            g2.setColor(new Color(100, 200, 255, 220));
            for (int i = 0; i < 15; i++) {
                int dx = x + 32 + (i * 3);
                int dy = y + 18 + (phase + i * 4) % 20;
                g2.fillOval(dx, dy, 4, 6);
            }
            
            // Hélice de queue
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x + 48, y + 8, x + 58, y + 3);
        }

        private void dessinerIconePompier(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(220, 40, 40));
            g2.fillRoundRect(x, y, 20, 20, 6, 6);
            g2.setColor(new Color(255, 80, 0));
            g2.fillArc(x + 2, y - 6, 16, 12, 0, 180);
            g2.setColor(new Color(0, 150, 255));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(x + 17, y + 10, x + 26, y + 6);
            g2.setStroke(new BasicStroke(1f));
        }

        private void dessinerIconeHelico(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(180, 0, 200));
            g2.fillRoundRect(x + 5, y + 8, 20, 12, 6, 6);
            g2.setColor(new Color(220, 50, 240));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(x, y + 4, x + 28, y + 4);
            g2.setStroke(new BasicStroke(1f));
        }

        private void dessinerRoseVent(Graphics2D g2, int cx, int cy, int r) {
            g2.setColor(new Color(80, 140, 220, 160));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
            double angle = angleVent(directionVent);
            int ax = (int)(cx + (r - 6) * Math.cos(angle));
            int ay = (int)(cy + (r - 6) * Math.sin(angle));
            g2.setColor(new Color(150, 200, 255));
            g2.drawLine(cx, cy, ax, ay);
            g2.fillOval(ax - 3, ay - 3, 6, 6);
            g2.setFont(new Font("Consolas", Font.PLAIN, 9));
            g2.setColor(Color.WHITE);
            g2.drawString(String.format("%.0f km/h", vitesseVent), cx - 20, cy + r + 12);
        }

        private double angleVent(String dir) {
            return switch (dir) {
                case "Nord"      -> -Math.PI / 2;
                case "Nord-Est"  -> -Math.PI / 4;
                case "Est"       -> 0;
                case "Sud-Est"   -> Math.PI / 4;
                case "Sud"       -> Math.PI / 2;
                case "Sud-Ouest" -> 3 * Math.PI / 4;
                case "Ouest"     -> Math.PI;
                case "Nord-Ouest"-> -3 * Math.PI / 4;
                default          -> 0;
            };
        }

        private void dessinerLegende(Graphics2D g2, int x, int y) {
            Object[][] items = {
                {new Color(255, 80, 0), "En feu"},
                {new Color(255, 200, 0), "Alerte"},
                {new Color(30, 100, 30), "Normal"},
                {new Color(220, 40, 40), "Pompier"},
                {new Color(255, 140, 0), "Véhicule"},
                {new Color(180, 0, 200), "Hélico"}
            };
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            int ox = x;
            for (Object[] it : items) {
                g2.setColor((Color) it[0]);
                g2.fillRect(ox, y - 9, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawString((String) it[1], ox + 13, y);
                ox += 80;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PANNEAU 2 : Bandeau niveau d'intervention
    // ══════════════════════════════════════════════════════════════════════════
 class NiveauPanel extends JPanel {
        NiveauPanel() {
            setBackground(new Color(18, 18, 28));
            setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(50, 50, 80)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();

            // Titre
            g2.setFont(new Font("Consolas", Font.BOLD, 20));
            g2.setColor(new Color(255, 160, 0));
            g2.drawString("SMA — GESTION INCENDIE FORÊT", 16, 35);

            // Jauge danger global
            int jaugeX = 400, jaugeW = 250, jaugeH = 18;
            g2.setColor(new Color(40, 40, 60));
            g2.fillRoundRect(jaugeX, 20, jaugeW, jaugeH, 6, 6);
            int rempli = (int)(jaugeW * dangerGlobal / 100.0);
            g2.setColor(couleurDanger(dangerGlobal));
            if (rempli > 0) g2.fillRoundRect(jaugeX, 20, rempli, jaugeH, 6, 6);
            g2.setColor(new Color(80, 80, 120));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(jaugeX, 20, jaugeW, jaugeH, 6, 6);
            g2.setFont(new Font("Consolas", Font.BOLD, 11));
            g2.setColor(Color.WHITE);
            g2.drawString("Danger global : " + dangerGlobal + "/100", jaugeX, 17);

            // Compteurs d'agents (affichage corrigé)
            dessinerCompteur(g2, 700,  "🚒 Pompiers",  pompiersDeployes + "/5", new Color(220, 80, 80));
            dessinerCompteur(g2, 830,  "🚛 Véhicules", vehiculesDeployes + "/3", new Color(255, 140, 0));
            dessinerCompteur(g2, 960,  "🚁 Hélicos",   helicosDeployes + "/2",  new Color(180, 0, 200));
            dessinerCompteur(g2, 1090, "📊 Alertes",   String.valueOf(nbAlertes), new Color(100, 180, 255));
            dessinerCompteur(g2, 1220, "📋 Ordres",    String.valueOf(nbOrdres),  new Color(148, 0, 211));

            // Heure
            g2.setFont(new Font("Consolas", Font.PLAIN, 11));
            g2.setColor(new Color(130, 130, 180));
            g2.drawString(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), w - 85, 35);

            g2.dispose();
        }

        private void dessinerCompteur(Graphics2D g2, int x, String label, String val, Color c) {
            g2.setColor(new Color(28, 28, 48));
            g2.fillRoundRect(x, 8, 115, 44, 8, 8);
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(x, 8, 115, 44, 8, 8);
            g2.setStroke(new BasicStroke(1f));
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.setColor(new Color(140, 140, 190));
            g2.drawString(label, x + 6, 22);
            g2.setFont(new Font("Consolas", Font.BOLD, 18));
            g2.setColor(c);
            g2.drawString(val, x + 6, 45);
        }
    }


    // ══════════════════════════════════════════════════════════════════════════
    // PANNEAU 3 : Météo (jauges)
    // ══════════════════════════════════════════════════════════════════════════

    class MeteoPanel extends JPanel {
        MeteoPanel() {
            setBackground(new Color(14, 14, 24));
            setBorder(titre("Conditions Météorologiques"));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int jw = (w - 50) / 4;

            dessinerJauge(g2, 10,          30, jw, h - 44, "Vent",    (int)vitesseVent, 80, "km/h", new Color(30, 144, 255));
            dessinerJauge(g2, 10 + jw + 10, 30, jw, h - 44, "Temp.",  (int)temperature, 50, "°C",   new Color(255, 100, 30));
            dessinerJauge(g2, 10 + 2*(jw+10), 30, jw, h - 44, "Hum.", (int)humidite, 100, "%",  new Color(0, 200, 200));
            dessinerJauge(g2, 10 + 3*(jw+10), 30, jw, h - 44, "Risque", risqueMeteo, 100, "/100", couleurDanger(risqueMeteo));

            g2.dispose();
        }

        private void dessinerJauge(Graphics2D g2, int x, int y, int w, int h,
                                    String label, int val, int max, String unite, Color c) {
            g2.setColor(new Color(28, 28, 48));
            g2.fillRoundRect(x, y, w, h, 8, 8);
            float ratio = Math.max(0, Math.min(1, val / (float)max));
            int rH = (int)(h * ratio);
            g2.setColor(c.darker());
            g2.fillRoundRect(x, y + h - rH, w, rH, 8, 8);
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(x, y, w, h, 8, 8);
            g2.setFont(new Font("Consolas", Font.BOLD, 13));
            g2.setColor(Color.WHITE);
            String v = val + unite;
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(v, x + (w - fm.stringWidth(v))/2, y + h/2 + 5);
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.setColor(new Color(170, 170, 210));
            g2.drawString(label, x + (w - g2.getFontMetrics().stringWidth(label))/2, y - 4);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PANNEAU 4 : État des agents
    // ══════════════════════════════════════════════════════════════════════════

    class AgentPanel extends JPanel {
        private final JPanel liste;

        AgentPanel() {
            setLayout(new BorderLayout());
            setBackground(new Color(18, 18, 30));
            setBorder(titre("État des Agents"));
            liste = new JPanel();
            liste.setLayout(new BoxLayout(liste, BoxLayout.Y_AXIS));
            liste.setBackground(new Color(18, 18, 30));
            JScrollPane sc = new JScrollPane(liste);
            sc.setBackground(new Color(18, 18, 30));
            sc.setBorder(null);
            sc.getViewport().setBackground(new Color(18, 18, 30));
            add(sc);
        }

        void actualiser() {
            liste.removeAll();
            synchronized (agents) {
                for (AgentInfo ai : agents.values()) {
                    liste.add(ligneAgent(ai));
                    liste.add(Box.createRigidArea(new Dimension(0, 2)));
                }
            }
            liste.revalidate();
            liste.repaint();
        }

        private JPanel ligneAgent(AgentInfo ai) {
            JPanel p = new JPanel(new BorderLayout(5, 0));
            p.setBackground(new Color(26, 26, 44));
            p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ai.couleur, 1),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
            ));
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

            JPanel dot = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.setColor(ai.couleur);
                    g.fillOval(2, 2, 10, 10);
                }
            };
            dot.setPreferredSize(new Dimension(16, 16));
            dot.setOpaque(false);

            JLabel nom  = new JLabel(ai.nom);
            nom.setFont(new Font("Consolas", Font.BOLD, 11));
            nom.setForeground(Color.WHITE);

            JLabel etat = new JLabel(ai.etat);
            etat.setFont(new Font("Consolas", Font.PLAIN, 11));
            etat.setForeground(ai.couleur);

            JLabel grp  = new JLabel(ai.groupe + " | " + ai.role);
            grp.setFont(new Font("Consolas", Font.PLAIN, 10));
            grp.setForeground(new Color(120, 120, 180));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            left.setOpaque(false);
            left.add(dot); left.add(nom);

            JPanel right = new JPanel(new BorderLayout());
            right.setOpaque(false);
            right.add(etat, BorderLayout.NORTH);
            right.add(grp,  BorderLayout.SOUTH);

            p.add(left, BorderLayout.WEST);
            p.add(right, BorderLayout.EAST);
            return p;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PANNEAU 5 : Journal des événements
    // ══════════════════════════════════════════════════════════════════════════

    class LogPanel extends JPanel {
        LogPanel() {
            setBackground(new Color(8, 8, 18));
            setBorder(titre("Journal des Événements"));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(8, 8, 18));
            g2.fillRect(0, 0, w, h);

            g2.setFont(new Font("Consolas", Font.PLAIN, 11));
            int y     = h - 8;
            int maxL  = (h - 30) / 16;

            List<LogEntry> snap;
            try {
                snap = new ArrayList<>(logs).subList(0, Math.min(maxL, logs.size()));
            } catch (Exception e) { return; }

            for (int i = snap.size() - 1; i >= 0; i--) {
                LogEntry e = snap.get(i);
                g2.setColor(e.couleur);
                g2.fillRoundRect(6, y - 12, 72, 14, 4, 4);
                g2.setFont(new Font("Consolas", Font.BOLD, 9));
                g2.setColor(Color.BLACK);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(e.type, 6 + (72 - fm.stringWidth(e.type))/2, y);
                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                g2.setColor(new Color(190, 190, 215));
                g2.drawString(e.horodatage + "  " + e.message, 84, y);
                y -= 16;
            }
            g2.dispose();
        }
    }

    // ── Utilitaire bordure ─────────────────────────────────────────────────────
    private TitledBorder titre(String t) {
        return BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(50, 50, 90), 1),
            t, TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Consolas", Font.BOLD, 11), new Color(160, 160, 220)
        );
    }

    // ── Classes de données internes ────────────────────────────────────────────

    static class ZoneInfo {
        String  nom;
        int     danger = 0;
        double  temperature = 22, humidite = 60;
        boolean enFeu = false;
        ZoneInfo(String n) { nom = n; }
    }

    static class AgentInfo {
        String nom, groupe, role, etat;
        Color  couleur;
        AgentInfo(String n, String g, String r, String e, Color c) {
            nom=n; groupe=g; role=r; etat=e; couleur=c;
        }
    }

    static class LogEntry {
        String type, message, horodatage;
        Color  couleur;
        LogEntry(String t, String m, Color c) {
            type=t; message=m; couleur=c;
            horodatage = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
    }
}
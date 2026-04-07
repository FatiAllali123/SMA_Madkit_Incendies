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
 * SimulationGUI v2 — Interface graphique pour la forêt unique.
 *
 * Panneaux :
 *   1. ForetPanel   — carte animée de la forêt (une seule zone, feu global)
 *   2. NiveauPanel  — jauge de danger global avec niveau d'intervention
 *   3. MeteoPanel   — conditions météo
 *   4. AgentPanel   — état de chaque agent en temps réel
 *   5. LogPanel     — journal des événements
 *
 * Compatible MadKit 5.3.1
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

    // ── Activation MadKit ─────────────────────────────────────────────────────
    @Override
    protected void activate() {
        getLogger().info("=== SimulationGUI v2 : Démarrage ===");

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
                zones.values().forEach(z -> { z.enFeu = false; z.danger = 0; });
                log("SYSTÈME", "=== FIN D'ALERTE OFFICIELLE ===", new Color(0, 200, 100));
            } else {
                // Compter les ressources déployées
                String action = o.getTypeAction();
                if ("EXTINCTION".equals(action)) {
                    pompiersDeployes  = extraireNombre(o.getInstructions(), pompiersDeployes);
                } else if ("SUPPORT_TRANSPORT".equals(action)) {
                    vehiculesDeployes = extraireNombre(o.getInstructions(), vehiculesDeployes);
                } else if ("ARROSAGE_AERIEN".equals(action)) {
                    helicosDeployes++;
                }
                log("ORDRE", "#" + o.getIdOrdre() + " → " + action + " | " + o.getPriorite(),
                    new Color(148, 0, 211));
            }

        } else if (msg instanceof RapportMessage) {
            RapportMessage r = (RapportMessage) msg;
            String nom    = r.getAgentEmetteur();
            String statut = r.getStatut();

            // Position sur la carte
            if ("SUR_ZONE".equals(statut) || "EXTINCTION".equals(statut)
                    || "ARROSAGE_EFFECTUE".equals(statut)) {
                agentsSurZone.add(nom);
            } else if ("TERMINE".equals(statut) || "RETOUR".equals(statut)) {
                agentsSurZone.remove(nom);
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
        frame = new JFrame("SMA — Gestion Incendie Forêt (v2)");
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

        ForetPanel() {
            setBackground(new Color(12, 35, 12));
            setBorder(titre("Forêt — Vue d'ensemble"));
            localTimer = new Timer(80, e -> { flammePhase = (flammePhase + 1) % 16; repaint(); });
            localTimer.start();
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
            dessinerZone(g2, pad,    pad,    mx-pad-4, my-pad-4, "Zone_Nord");
            dessinerZone(g2, mx+4,   pad,    mx-pad-4, my-pad-4, "Zone_Est");
            dessinerZone(g2, pad,    my+4,   mx-pad-4, my-pad-4, "Zone_Sud");
            dessinerZone(g2, mx+4,   my+4,   mx-pad-4, my-pad-4, "Zone_Ouest");

            // Agents sur zone (au centre de la forêt)
            dessinerAgentsSurZone(g2, w/2 - 60, h/2 - 30);

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

            // Arbres (points verts)
            Random rnd = new Random(nom.hashCode());
            g2.setColor(new Color(22, 100, 22, 140));
            for (int i = 0; i < 25; i++) {
                int tx = x + 12 + rnd.nextInt(Math.max(1, w - 24));
                int ty = y + 22 + rnd.nextInt(Math.max(1, h - 44));
                int ts = 4 + rnd.nextInt(4);
                // Triangle arbre
                int[] px = {tx, tx - ts, tx + ts};
                int[] py = {ty - ts*2, ty, ty};
                g2.fillPolygon(px, py, 3);
            }

            // Flammes si en feu
            if (zi.enFeu) {
                dessinerFlammes(g2, x, y, w, h, zi.danger);
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

 private void dessinerFlammes(Graphics2D g2, int x, int y, int w, int h, int danger) {
    int nb = Math.max(4, danger / 12);
    Random rnd = new Random(danger + flammePhase / 4);
    for (int i = 0; i < nb; i++) {
        int fx = x + 16 + rnd.nextInt(Math.max(1, w - 32));
        int fy = y + 20 + rnd.nextInt(Math.max(1, h - 50));
        int taille = 8 + (flammePhase + i * 3) % 8 + danger / 18;
        int[] px = {fx, fx - taille/2, fx + taille/2};
        int[] py = {fy - taille*2, fy, fy};

        // ✅ FIX : clamp alpha entre 0.0f et 1.0f
        float alpha = Math.max(0f, Math.min(1f,
            0.65f + 0.35f * (float) Math.sin(flammePhase * 0.4 + i)
        ));

        // ✅ FIX : clamp green entre 0.0f et 1.0f
        float green = Math.max(0f, Math.min(1f,
            0.25f + 0.3f * (float) Math.sin(flammePhase * 0.3f + i)
        ));

        g2.setColor(new Color(1f, green, 0f, alpha));
        g2.fillPolygon(px, py, 3);

        // ✅ FIX : alpha de 50/255 ≈ 0.196f — ok, mais on sécurise aussi
        g2.setColor(new Color(255, 200, 0, 50));
        g2.fillOval(fx - taille, fy - taille, taille * 2, taille * 2);
    }
}


        private void dessinerAgentsSurZone(Graphics2D g2, int cx, int cy) {
            List<String> surZone;
            synchronized (agentsSurZone) { surZone = new ArrayList<>(agentsSurZone); }
            if (surZone.isEmpty()) return;

            // Fond semi-transparent pour la zone d'intervention
            g2.setColor(new Color(0, 100, 255, 30));
            g2.fillOval(cx - 70, cy - 40, 140, 80);

            int startX = cx - (surZone.size() * 22) / 2;
            for (int i = 0; i < surZone.size(); i++) {
                String nom = surZone.get(i);
                int ax = startX + i * 24;
                int ay = cy;
                if (nom.contains("Pompier") || nom.contains("pompier"))
                    dessinerIconePompier(g2, ax, ay);
                else if (nom.contains("Helico") || nom.contains("helico"))
                    dessinerIconeHelico(g2, ax, ay - 20);
                else if (nom.contains("Citerne") || nom.contains("Vehicule"))
                    dessinerIconeVehicule(g2, ax, ay);
            }
        }

        private void dessinerIconePompier(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(220, 40, 40));
            g2.fillRoundRect(x, y, 16, 16, 4, 4);
            g2.setColor(new Color(255, 80, 0));
            g2.fillArc(x + 1, y - 4, 14, 10, 0, 180);
            g2.setColor(new Color(0, 150, 255));
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x + 15, y + 8, x + 20, y + 4);
            g2.setStroke(new BasicStroke(1f));
        }

        private void dessinerIconeVehicule(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(255, 140, 0));
            g2.fillRoundRect(x, y + 4, 20, 10, 3, 3);
            g2.setColor(new Color(200, 200, 220));
            g2.fillRoundRect(x + 10, y + 5, 10, 7, 3, 3);
            g2.setColor(Color.DARK_GRAY);
            g2.fillOval(x + 2, y + 12, 5, 5);
            g2.fillOval(x + 13, y + 12, 5, 5);
        }

        private void dessinerIconeHelico(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(180, 0, 200));
            g2.fillRoundRect(x + 3, y + 5, 14, 8, 4, 4);
            g2.setColor(new Color(220, 50, 240));
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x, y + 3, x + 20, y + 3);
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
            int w = getWidth(), h = getHeight();

            // Titre
            g2.setFont(new Font("Consolas", Font.BOLD, 20));
            g2.setColor(new Color(255, 160, 0));
            g2.drawString("SMA — GESTION INCENDIE FORÊT", 16, 40);

            // Badge niveau d'intervention
            Color niveauColor = switch (niveauIntervention) {
                case "EXTREME"   -> new Color(180, 0, 255);
                case "CRITIQUE"  -> new Color(220, 30, 30);
                case "ROUGE"     -> new Color(255, 80, 0);
                case "ORANGE"    -> new Color(255, 180, 0);
                case "FIN_ALERTE"-> new Color(0, 200, 100);
                default          -> new Color(40, 160, 60);
            };
            g2.setColor(niveauColor);
            g2.fillRoundRect(310, 14, 150, 42, 10, 10);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.setColor(Color.BLACK);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(niveauIntervention, 310 + (150 - fm.stringWidth(niveauIntervention))/2, 41);

            // Compteurs
            int ox = 490;
            dessinerCompteur(g2, ox,       "DANGER",    dangerGlobal + "/100", couleurDanger(dangerGlobal));
            dessinerCompteur(g2, ox + 130, "POMPIERS",  pompiersDeployes + "/5", new Color(220, 80, 80));
            dessinerCompteur(g2, ox + 260, "VÉHICULES", vehiculesDeployes + "/3", new Color(255, 140, 0));
            dessinerCompteur(g2, ox + 390, "HÉLICOS",   helicosDeployes + "/2",  new Color(180, 0, 200));
            dessinerCompteur(g2, ox + 520, "ALERTES",   String.valueOf(nbAlertes), new Color(100, 180, 255));
            dessinerCompteur(g2, ox + 650, "ORDRES",    String.valueOf(nbOrdres),  new Color(148, 0, 211));

            // Heure
            g2.setFont(new Font("Consolas", Font.PLAIN, 12));
            g2.setColor(new Color(130, 130, 180));
            g2.drawString(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), w - 72, 38);

            g2.dispose();
        }

        private void dessinerCompteur(Graphics2D g2, int x, String label, String val, Color c) {
            g2.setColor(new Color(28, 28, 48));
            g2.fillRoundRect(x, 8, 120, 54, 8, 8);
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(x, 8, 120, 54, 8, 8);
            g2.setStroke(new BasicStroke(1f));
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.setColor(new Color(140, 140, 190));
            g2.drawString(label, x + 6, 22);
            g2.setFont(new Font("Consolas", Font.BOLD, 20));
            g2.setColor(c);
            g2.drawString(val, x + 6, 50);
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
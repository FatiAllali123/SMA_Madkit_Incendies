package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.*;

/**
 * AgentCoordinateur — Cerveau du système (G1 + G2 + G3)
 *
 * Logique principale :
 * 1. Reçoit les alertes des capteurs → calcule un DANGER GLOBAL (max des capteurs)
 * 2. Selon le niveau de danger, détermine combien de ressources envoyer
 * 3. Envoie des renforts si la situation s'aggrave (dynamique)
 * 4. Retire des ressources si la situation s'améliore
 * 5. Déclare la fin d'alerte quand le danger global redescend à 0
 *
 * Niveaux :
 *   < 35  : NORMAL      → aucune intervention
 *   35-49 : ORANGE      → 2 pompiers + 1 véhicule
 *   50-69 : ROUGE       → 4 pompiers + 2 véhicules
 *   70-84 : CRITIQUE    → 5 pompiers + 3 véhicules
 *   ≥ 85  : EXTREME     → tout le monde + 2 hélicos
 */
public class AgentCoordinateur extends Agent {

    // ── État du feu ───────────────────────────────────────────────────────────
    // Map zone → dernier danger reçu
    private final Map<String, Integer> dangerParZone = new LinkedHashMap<>();
    private int dangerGlobal   = 0;
    private int dangerPrecedent = -1;   // pour détecter les changements de niveau

    // ── Niveau d'intervention actuel ──────────────────────────────────────────
    private String niveauActuel = "NORMAL";  // NORMAL, ORANGE, ROUGE, CRITIQUE, EXTREME

    // ── Ressources déployées ──────────────────────────────────────────────────
    private int pompiersDeployes  = 0;
    private int vehiculesDeployes = 0;
    private int helicosDeployes   = 0;

    // ── Totaux disponibles (taille du pool) ───────────────────────────────────
    private static final int MAX_POMPIERS  = 5;
    private static final int MAX_VEHICULES = 3;
    private static final int MAX_HELICOS   = 2;

    // ── Progression extinction rapportée ─────────────────────────────────────
    private final Map<String, RapportMessage> dernierRapport = new LinkedHashMap<>();
    private int progressionMoyenne = 0;

    private int  cycle        = 0;
    private boolean incendieEnCours = false;
    private boolean finDeclaree     = false;

    // Compteur de cycles à danger bas pour éviter fin prématurée
    private int cyclesDangerBas = 0;
    private static final int CYCLES_AVANT_FIN = 5;

    @Override
    protected void activate() {
        getLogger().info("=== AgentCoordinateur : Activation ===");

        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_COORD_SURV, null);

        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_DECIDEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_SUPERVISEUR, null);

        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_DECIDEUR, null);

        getLogger().info("Coordinateur opérationnel.");
    }

    @Override
    protected void live() {
        getLogger().info("Coordinateur : en écoute...");
        while (true) {
            cycle++;

            // Lire tous les messages disponibles
            Message msg;
            while ((msg = nextMessage()) != null) {
                traiterMessage(msg);
            }

            // Calculer le danger global (max de toutes les zones)
            dangerGlobal = dangerParZone.values().stream()
                .mapToInt(i -> i).max().orElse(0);

            // Évaluer et ajuster les ressources si changement significatif
            evaluerEtAjuster();

            // Tableau de bord toutes les 5 secondes environ
            if (cycle % 3 == 0) afficherTableauBord();

            try { Thread.sleep(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("Coordinateur arrêté. " + cycle + " cycles.");
    }

    // ── Traitement des messages ───────────────────────────────────────────────

    private void traiterMessage(Message msg) {
        if (msg instanceof AlerteMessage) {
            AlerteMessage a = (AlerteMessage) msg;
            dangerParZone.put(a.getZoneSource(), a.getIndiceDanger());

        } else if (msg instanceof RapportMessage) {
            RapportMessage r = (RapportMessage) msg;
            dernierRapport.put(r.getAgentEmetteur(), r);

            // Mettre à jour la progression moyenne
            double somme = dernierRapport.values().stream()
                .mapToInt(RapportMessage::getProgressionExtinction).average().orElse(0);
            progressionMoyenne = (int) somme;

            // Si tous les agents ont terminé et le danger est bas → fin d'alerte
            boolean tousTermines = dernierRapport.values().stream()
                .allMatch(RapportMessage::estTermine);
            if (tousTermines && dangerGlobal < AGRConstants.SEUIL_SURVEILLANCE
                    && incendieEnCours && !finDeclaree) {
                cyclesDangerBas++;
            }

            if (r.estTermine()) {
                getLogger().info("✓ " + r.getAgentEmetteur()
                    + " a terminé sa mission (progression=" + r.getProgressionExtinction() + "%)");
            }

        } else if (msg instanceof MeteoMessage) {
            // Intégrer le risque météo dans le danger global (bonus léger)
            MeteoMessage m = (MeteoMessage) msg;
            if (m.getIndiceRisque() > 70) {
                getLogger().warning("⚠ ALERTE MÉTÉO : Risque=" + m.getIndiceRisque()
                    + "/100 | Vent=" + String.format("%.0f", m.getVitesseVent()) + "km/h "
                    + m.getDirectionVent());
            }
        }
    }

    // ── Logique de décision ───────────────────────────────────────────────────

    private void evaluerEtAjuster() {
        String nouveauNiveau = calculerNiveau(dangerGlobal);

        // Détecter changement de niveau
        if (!nouveauNiveau.equals(niveauActuel)) {
            getLogger().warning("=== CHANGEMENT DE NIVEAU : " + niveauActuel
                + " → " + nouveauNiveau + " (Danger=" + dangerGlobal + "/100) ===");
            niveauActuel = nouveauNiveau;
            appliquerNiveau(nouveauNiveau);
        }

        // Gestion de la fin d'incendie
        if (incendieEnCours && dangerGlobal < AGRConstants.SEUIL_SURVEILLANCE) {
            cyclesDangerBas++;
            if (cyclesDangerBas >= CYCLES_AVANT_FIN && !finDeclaree) {
                declarerFinAlerte();
            }
        } else {
            cyclesDangerBas = 0;
        }
    }

    private String calculerNiveau(int danger) {
        if (danger >= AGRConstants.SEUIL_CRITIQUE)    return "EXTREME";
        if (danger >= AGRConstants.SEUIL_ROUGE)       return "CRITIQUE";
        if (danger >= AGRConstants.SEUIL_ORANGE)      return "ROUGE";
        if (danger >= AGRConstants.SEUIL_SURVEILLANCE) return "ORANGE";
        return "NORMAL";
    }

    /**
     * Applique le niveau d'intervention :
     * envoie des renforts si on monte, rappelle des agents si on descend.
     */
    private void appliquerNiveau(String niveau) {
        int ciblePompiers  = 0;
        int cibleVehicules = 0;
        int cibleHelicos   = 0;

        switch (niveau) {
            case "NORMAL":
                // Aucune intervention
                break;
            case "ORANGE":
                ciblePompiers  = 2;
                cibleVehicules = 1;
                break;
            case "ROUGE":
                ciblePompiers  = 4;
                cibleVehicules = 2;
                break;
            case "CRITIQUE":
                ciblePompiers  = MAX_POMPIERS;   // 5
                cibleVehicules = MAX_VEHICULES;  // 3
                break;
            case "EXTREME":
                ciblePompiers  = MAX_POMPIERS;
                cibleVehicules = MAX_VEHICULES;
                cibleHelicos   = MAX_HELICOS;    // 2
                break;
        }

        if ("NORMAL".equals(niveau)) {
            return; // La fin sera gérée par declarerFinAlerte()
        }

        incendieEnCours = true;

        // Envoyer renforts pompiers si nécessaire
        int pompiersSup = ciblePompiers - pompiersDeployes;
        if (pompiersSup > 0) {
            getLogger().warning(">>> DÉPLOIEMENT : +" + pompiersSup + " pompier(s) → Total=" + ciblePompiers);
            OrdreMessage ordre = new OrdreMessage(
                "EXTINCTION",
                niveau.equals("EXTREME") || niveau.equals("CRITIQUE") ? "URGENTE" : "HAUTE",
                "Extinction incendie. " + pompiersSup + " équipe(s) supplémentaire(s)."
            );
            // Broadcast : les pompiers libres prendront l'ordre (jusqu'à pompiersSup)
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
                AGRConstants.ROLE_POMPIER, ordre, AGRConstants.ROLE_DECIDEUR);
            pompiersDeployes = ciblePompiers;

            // Notifier les capteurs que l'extinction est en cours
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
                AGRConstants.ROLE_CAPTEUR, new SimpleMessage("EXTINCTION_EN_COURS"),
                AGRConstants.ROLE_COORD_SURV);
        }

        // Envoyer renforts véhicules si nécessaire
        int vehiculesSup = cibleVehicules - vehiculesDeployes;
        if (vehiculesSup > 0) {
            getLogger().warning(">>> DÉPLOIEMENT : +" + vehiculesSup + " véhicule(s) → Total=" + cibleVehicules);
            OrdreMessage ordre = new OrdreMessage(
                "SUPPORT_TRANSPORT",
                "HAUTE",
                "Transport équipes + ravitaillement eau. " + vehiculesSup + " camion(s)."
            );
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
                AGRConstants.ROLE_CONDUCTEUR, ordre, AGRConstants.ROLE_DECIDEUR);
            vehiculesDeployes = cibleVehicules;
        }

        // Envoyer hélicos si niveau EXTREME
        int helicosSup = cibleHelicos - helicosDeployes;
        if (helicosSup > 0) {
            getLogger().warning(">>> DÉPLOIEMENT AÉRIEN : +" + helicosSup + " hélico(s)");
            OrdreMessage ordre = new OrdreMessage(
                "ARROSAGE_AERIEN",
                "URGENTE",
                "Arrosage aérien immédiat. Situation extrême."
            );
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
                AGRConstants.ROLE_RENFORT, ordre, AGRConstants.ROLE_DECIDEUR);
            helicosDeployes = cibleHelicos;
        }
    }

    private void declarerFinAlerte() {
        if (finDeclaree) return;
        finDeclaree = true;

        getLogger().info("╔══════════════════════════════════════════╗");
        getLogger().info("║   INCENDIE MAÎTRISÉ — FIN D'ALERTE      ║");
        getLogger().info("║   Danger final : " + dangerGlobal + "/100              ║");
        getLogger().info("╚══════════════════════════════════════════╝");

        // Notifier tous les agents G3 de rentrer
        SimpleMessage fin = new SimpleMessage(SimpleMessage.FIN_ALERTE);
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_POMPIER, fin, AGRConstants.ROLE_DECIDEUR);
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_CONDUCTEUR, fin, AGRConstants.ROLE_DECIDEUR);
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_RENFORT, fin, AGRConstants.ROLE_DECIDEUR);

        // Notifier les capteurs : incendie maîtrisé
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_CAPTEUR, new SimpleMessage("INCENDIE_MAITRISE"),
            AGRConstants.ROLE_COORD_SURV);

        // Rapport final au chef des opérations
        OrdreMessage rapport = new OrdreMessage(
            "RAPPORT_FINAL", "NORMALE",
            "Incendie éteint. Pompiers=" + pompiersDeployes
            + " | Véhicules=" + vehiculesDeployes
            + " | Hélicos=" + helicosDeployes
        );
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_SUPERVISEUR, rapport, AGRConstants.ROLE_DECIDEUR);

        // Reset état
        pompiersDeployes  = 0;
        vehiculesDeployes = 0;
        helicosDeployes   = 0;
        niveauActuel      = "NORMAL";
        incendieEnCours   = false;
        dangerParZone.clear();
        dernierRapport.clear();
    }

    private void afficherTableauBord() {
        if (!incendieEnCours && dangerGlobal == 0) return;

        StringBuilder zones = new StringBuilder();
        dangerParZone.forEach((z, d) -> zones.append(z).append("=").append(d).append(" "));

        getLogger().info(String.format(
            "📊 [C%02d] Danger=%d/100 | Niveau=%s | Pompiers=%d | Véhicules=%d | Hélicos=%d | Progression=%d%%",
            cycle, dangerGlobal, niveauActuel,
            pompiersDeployes, vehiculesDeployes, helicosDeployes, progressionMoyenne
        ));
        if (!dangerParZone.isEmpty()) {
            getLogger().info("    Zones : " + zones);
        }
    }
}
package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentVehicule — G3 | Rôle : Conducteur
 *
 * Camion-citerne. Transporte les équipes et ravitaille en eau.
 * Reste sur zone jusqu'à FIN_ALERTE (ne repart pas seul).
 */
public class AgentVehicule extends Agent {

    private final String id;
    private int    eau           = 6000;  // litres
    private int    carburant     = 200;
    private String statut        = "EN_ATTENTE";
    private boolean enMission    = false;
    private boolean actif        = true;
    private int    ravitaillements = 0;

    private final Random rng = new Random();

    public AgentVehicule(String id) { this.id = id; }

    @Override
    protected void activate() {
        getLogger().info("=== Véhicule [" + id + "] Eau=" + eau + "L ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_CONDUCTEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_GESTIONNAIRE, null);
    }

    @Override
    protected void live() {
        getLogger().info("[" + id + "] En attente d'ordre...");
        while (actif) {
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof OrdreMessage) {
                    OrdreMessage o = (OrdreMessage) msg;
                    if (!enMission && "SUPPORT_TRANSPORT".equals(o.getTypeAction())) {
                        partirEnMission();
                    } else if (enMission) {
                        getLogger().info("[" + id + "] Déjà sur zone, ordre ignoré.");
                    }
                } else if (msg instanceof SimpleMessage) {
                    SimpleMessage sm = (SimpleMessage) msg;
                    if (sm.isFin()) {
                        getLogger().info("[" + id + "] FIN_ALERTE → Retour dépôt.");
                        envoyerRapport(100, "RETOUR", "Retour au dépôt");
                        statut   = "EN_ATTENTE";
                        enMission = false;
                        actif     = false;
                    } else if ("EXTINCTION_EN_COURS".equals(sm.getContenu()) && enMission) {
                        // Ravitaillement périodique
                        ravitailler();
                    }
                }
            }

            try { Thread.sleep(1500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("[" + id + "] Arrêté. Eau restante=" + eau + "L | Ravitaillements=" + ravitaillements);
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_CONDUCTEUR);
    }

    private void partirEnMission() {
        enMission = true;
        statut    = "EN_ROUTE";
        getLogger().info("[" + id + "] Départ vers la forêt !");
        envoyerRapport(0, "EN_ROUTE", "En route vers la zone d'incendie");

        try { Thread.sleep(AGRConstants.DEPLACEMENT_MS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (!actif) return;

        carburant -= 15 + rng.nextInt(10);
        statut = "SUR_ZONE";
        getLogger().info("[" + id + "] Arrivé sur zone. Ravitaillement des pompiers disponible.");

        // Premier ravitaillement à l'arrivée
        ravitailler();

        envoyerRapport(50, "SUR_ZONE", "Sur zone, ravitaillement en cours");
        // Le véhicule RESTE sur zone — attend FIN_ALERTE dans la boucle principale
    }

    private void ravitailler() {
        if (eau <= 0) {
            getLogger().warning("[" + id + "] Citerne vide ! Retour pour rechargement...");
            try { Thread.sleep(AGRConstants.DEPLACEMENT_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            eau = 6000;
            getLogger().info("[" + id + "] Rechargé. Retour sur zone.");
            return;
        }
        int quantite = Math.min(eau, 800 + rng.nextInt(400));
        eau -= quantite;
        ravitaillements++;
        getLogger().info("[" + id + "] Ravitaillement #" + ravitaillements
            + " : " + quantite + "L fournis aux pompiers | Réserve=" + eau + "L");
    }

    private void envoyerRapport(int progression, String stat, String obs) {
        RapportMessage r = new RapportMessage(id, progression, 0, stat, obs);
        broadcastMessageWithRole(
            AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_DECIDEUR, r, AGRConstants.ROLE_GESTIONNAIRE
        );
    }
}
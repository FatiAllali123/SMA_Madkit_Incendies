package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentVehicule v3
 *
 * CORRECTIONS :
 * 1. Reste sur zone jusqu'à FIN_ALERTE (ne repart jamais seul)
 * 2. Ravitaille périodiquement quand il est sur zone
 * 3. Envoie un rapport RETOUR seulement sur FIN_ALERTE
 */
public class AgentVehicule extends Agent {

    private final String id;
    private int    eau       = 6000;
    private int    carburant = 200;
    private String statut    = "EN_ATTENTE";
    private boolean surZone  = false;
    private boolean actif    = true;
    private int    ravitaillements = 0;
    private final Random rng = new Random();

    public AgentVehicule(String id) { this.id = id; }

    @Override
    protected void activate() {
        getLogger().info("=== Véhicule [" + id + "] Eau=" + eau + "L ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_CONDUCTEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_GESTIONNAIRE, null);
    }

    @Override
    protected void live() {
        getLogger().info("[" + id + "] En attente.");
        while (actif) {
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof OrdreMessage) {
                    OrdreMessage o = (OrdreMessage) msg;
                    if (!surZone && "SUPPORT_TRANSPORT".equals(o.getTypeAction())) {
                        allerSurZone();
                    }
                } else if (msg instanceof SimpleMessage) {
    SimpleMessage sm = (SimpleMessage) msg;
    if (sm.isFin() || SimpleMessage.RETOUR_BASE.equals(sm.getContenu())) {
        getLogger().info("[" + id + "] Retour dépôt.");
        envoyerRapport(100, "RETOUR", "Retour au dépôt.");
        surZone = false;
        statut  = "EN_ATTENTE";
        if (sm.isFin()) actif = false;
        // Si RETOUR_BASE, reste actif et disponible pour la prochaine intervention
    }
}
            }

            // Ravitaillement périodique automatique si sur zone (toutes les ~10s)
            if (surZone && rng.nextInt(5) == 0) {
                ravitailler();
            }

            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("[" + id + "] Arrêté. Eau=" + eau + "L | Ravitaillements=" + ravitaillements);
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_CONDUCTEUR);
    }

    private void allerSurZone() {
        statut  = "EN_ROUTE";
        getLogger().info("[" + id + "] Départ vers la forêt !");
        envoyerRapport(0, "EN_ROUTE", "En route.");

        try { Thread.sleep(AGRConstants.DEPLACEMENT_MS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (!actif) return;

        carburant -= 15 + rng.nextInt(10);
        surZone = true;
        statut  = "SUR_ZONE";
        getLogger().info("[" + id + "] Sur zone. Prêt au ravitaillement. Eau=" + eau + "L");

        // Premier ravitaillement à l'arrivée
        ravitailler();
        envoyerRapport(50, "SUR_ZONE", "Sur zone, ravitaillement actif.");
        // Le véhicule reste ici indéfiniment jusqu'à FIN_ALERTE
    }

    private void ravitailler() {
        if (eau <= 0) {
            getLogger().warning("[" + id + "] Citerne vide ! Retour pour rechargement...");
            statut = "RECHARGEMENT";
            try { Thread.sleep(AGRConstants.DEPLACEMENT_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            eau   = 6000;
            statut = "SUR_ZONE";
            getLogger().info("[" + id + "] Rechargé. Retour sur zone.");
            return;
        }
        int q = Math.min(eau, 700 + rng.nextInt(500));
        eau -= q;
        ravitaillements++;
        getLogger().info("[" + id + "] Ravitaillement #" + ravitaillements
            + " : " + q + "L | Réserve=" + eau + "L");
    }

    private void envoyerRapport(int progression, String stat, String obs) {
        RapportMessage r = new RapportMessage(id, progression, 0, stat, obs);
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_DECIDEUR, r, AGRConstants.ROLE_GESTIONNAIRE);
    }
}
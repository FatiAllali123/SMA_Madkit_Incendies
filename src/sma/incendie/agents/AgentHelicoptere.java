package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentHelicoptere — G3 | Rôle : Renfort_Ext
 * Déployé uniquement en situation EXTREME (danger ≥ 85).
 * Effectue des largages répétés jusqu'à FIN_ALERTE.
 */
public class AgentHelicoptere extends Agent {

    private final String id;
    private int    capacite   = 3000;  // litres
    private String statut     = "EN_BASE";
    private boolean enMission = false;
    private boolean actif     = true;
    private int    nbLargages = 0;
    private final Random rng  = new Random();

    public AgentHelicoptere(String id) { this.id = id; }

    @Override
    protected void activate() {
        getLogger().info("=== Hélicoptère [" + id + "] Capacité=" + capacite + "L ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_RENFORT, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_GESTIONNAIRE, null);
    }

    @Override
    protected void live() {
        getLogger().info("[" + id + "] En base. Attend ordre EXTREME.");
        while (actif) {
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof OrdreMessage) {
                    OrdreMessage o = (OrdreMessage) msg;
                    if (!enMission && "ARROSAGE_AERIEN".equals(o.getTypeAction())) {
                        enMission = true;
                        statut    = "EN_MISSION";
                        getLogger().warning("[" + id + "] 🚁 DÉPART ARROSAGE AÉRIEN !");
                        // Lancer les largages en boucle dans un thread séparé
                        Thread t = new Thread(this::boucleArrosage);
                        t.setDaemon(true);
                        t.start();
                    }
                } else if (msg instanceof SimpleMessage) {
    SimpleMessage sm = (SimpleMessage) msg;
    if (sm.isFin() || SimpleMessage.RETOUR_BASE.equals(sm.getContenu())) { // ← MODIFIER
        getLogger().info("[" + id + "] " + sm.getContenu() + " → Retour base.");
        enMission = false;
        statut    = "EN_BASE";
        
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_RENFORT);
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_GESTIONNAIRE);
    }
}
            }
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("[" + id + "] Arrêté. Largages=" + nbLargages);
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_RENFORT);
    }

    private void boucleArrosage() {
        while (actif && enMission) {
            // Trajet aller
            try { Thread.sleep(3000); }
            catch (InterruptedException e) { return; }

            if (!actif) return;

            // Largage
            int largage = Math.min(capacite, 1200 + rng.nextInt(800));
            capacite -= largage;
            nbLargages++;
            getLogger().warning(String.format("[%s] 💧 Largage #%d : %dL | Réserve=%dL",
                id, nbLargages, largage, capacite));

            // Rapport
            RapportMessage r = new RapportMessage(id,
                Math.min(100, nbLargages * 25),
                Math.max(0, 80 - nbLargages * 15),
                "ARROSAGE_EFFECTUE",
                "Largage #" + nbLargages + " : " + largage + "L déversés"
            );
            broadcastMessageWithRole(
                AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
                AGRConstants.ROLE_DECIDEUR, r, AGRConstants.ROLE_GESTIONNAIRE
            );

            // Rechargement si nécessaire
            if (capacite < 500) {
                getLogger().info("[" + id + "] Retour lac pour rechargement...");
                try { Thread.sleep(5000); }
                catch (InterruptedException e) { return; }
                capacite = 3000;
                getLogger().info("[" + id + "] Rechargé. De retour sur zone.");
            }
        }
    }
}
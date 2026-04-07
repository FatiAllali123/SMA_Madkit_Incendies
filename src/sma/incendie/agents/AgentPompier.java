package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentPompier — G3 | Rôle : Pompier (ou ChefEquipe)
 *
 * Logique simplifiée :
 * 1. Attend un ordre EXTINCTION du coordinateur
 * 2. Se déplace vers la forêt (EN_ROUTE)
 * 3. Travaille à éteindre le feu par incréments (EXTINCTION)
 * 4. Envoie un rapport après chaque action
 * 5. S'arrête sur FIN_ALERTE
 */
public class AgentPompier extends Agent {

    private final String  id;
    private final boolean estChef;

    private String statut        = "EN_ATTENTE";
    private int    progression   = 0;
    private int    eau           = 500;   // litres
    private boolean enMission    = false;
    private boolean actif        = true;

    private final Random rng = new Random();

    public AgentPompier(String id, boolean estChef) {
        this.id      = id;
        this.estChef = estChef;
    }

    @Override
    protected void activate() {
        getLogger().info("=== Pompier [" + id + "] Chef=" + estChef + " ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        if (estChef)
            requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
                AGRConstants.ROLE_CHEF_EQUIPE, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION,
            AGRConstants.ROLE_POMPIER, null);
        // Rejoindre G2 pour envoyer rapports au coordinateur
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_GESTIONNAIRE, null);
    }

    @Override
    protected void live() {
        getLogger().info("[" + id + "] En attente d'ordre...");

        while (actif) {
            // Traiter les messages
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof OrdreMessage) {
                    OrdreMessage o = (OrdreMessage) msg;
                    if (!enMission && "EXTINCTION".equals(o.getTypeAction())) {
                        accepterMission(o);
                    } else if (enMission) {
                        getLogger().info("[" + id + "] Déjà en mission, ordre ignoré.");
                    }
                } else if (msg instanceof SimpleMessage) {
                    SimpleMessage sm = (SimpleMessage) msg;
                    if (sm.isFin()) {
                        getLogger().info("[" + id + "] FIN_ALERTE reçu. Retour caserne.");
                        rentrerBase();
                        actif = false;
                    }
                }
            }

            // Si en mission, continuer le travail
            if (enMission) {
                travaillerExtinction();
            }

            try { Thread.sleep(AGRConstants.EXTINCTION_STEP_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("[" + id + "] Arrêté. Statut=" + statut + " | Eau=" + eau + "L");
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_POMPIER);
    }

    // ── Mission ───────────────────────────────────────────────────────────────

    private void accepterMission(OrdreMessage ordre) {
        enMission  = true;
        statut     = "EN_ROUTE";
        progression = 0;
        getLogger().warning("[" + id + "] MISSION ACCEPTÉE → Départ pour la forêt (Priorité: "
            + ordre.getPriorite() + ")");

        envoyerRapport(0, 90, "EN_ROUTE", "Départ en cours");

        // Déplacement simulé
        pause(AGRConstants.DEPLACEMENT_MS);
        if (!actif) return;

        statut = "SUR_ZONE";
        getLogger().info("[" + id + "] Arrivé en forêt. Début des opérations.");
        envoyerRapport(0, 80, "SUR_ZONE", "Arrivée sur zone, installation du matériel");

        statut = "EXTINCTION";
    }

    private void travaillerExtinction() {
        if (!"EXTINCTION".equals(statut)) return;

        // Chaque step : progrès de 10-20%
        int gain = 10 + rng.nextInt(11);
        progression = Math.min(100, progression + gain);
        eau = Math.max(0, eau - 40 - rng.nextInt(30));

        int dangerResiduel = Math.max(0, (int)((100 - progression) * 0.9));

        getLogger().info(String.format("[%s] Extinction : %d%% | Eau=%dL | DangerRés=%d",
            id, progression, eau, dangerResiduel));

        if (progression >= 100) {
            statut = "TERMINE";
            enMission = false;
            getLogger().warning("[" + id + "] ✓ MISSION ACCOMPLIE — Feu sous contrôle !");
            envoyerRapport(100, 0, "TERMINE", "Feu maîtrisé dans ma zone.");
        } else {
            envoyerRapport(progression, dangerResiduel, "EXTINCTION", "Extinction en cours");
        }
    }

    private void rentrerBase() {
        enMission = false;
        statut    = "RETOUR";
        envoyerRapport(progression, 0, "RETOUR", "Retour à la caserne");
    }

    private void envoyerRapport(int prog, int danger, String stat, String obs) {
        RapportMessage r = new RapportMessage(id, prog, danger, stat, obs);
        broadcastMessageWithRole(
            AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_DECIDEUR, r, AGRConstants.ROLE_GESTIONNAIRE
        );
        if (estChef) {
            // Le chef envoie aussi au superviseur
            broadcastMessageWithRole(
                AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
                AGRConstants.ROLE_SUPERVISEUR,
                new RapportMessage(id, prog, danger, stat, obs),
                AGRConstants.ROLE_GESTIONNAIRE
            );
        }
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
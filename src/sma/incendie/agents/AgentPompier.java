package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentPompier v3
 *
 * CORRECTIONS :
 * 1. Après TERMINE → retourne EN_ATTENTE et écoute de nouveaux ordres
 * 2. Accepte un nouvel ordre même après avoir terminé une mission
 * 3. Ne bloque pas la boucle live() pendant l'extinction (appels par étapes)
 */
public class AgentPompier extends Agent {

    private final String  id;
    private final boolean estChef;

    private String  statut      = "EN_ATTENTE";
    private int     progression = 0;      // progression personnelle de ce pompier
    private int     eau         = 500;
    private boolean actif       = true;
    private boolean enMission   = false;
    private int     dangerActuel = 0;     // ← NOUVEAU : danger reçu du coordinateur
    private int     cyclesSansDanger = 0; // ← NOUVEAU : cycles sans danger avant terminaison

    private final Random rng = new Random();

    public AgentPompier(String id, boolean estChef) {
        this.id = id; this.estChef = estChef;
    }

    @Override
    protected void activate() {
        getLogger().info("=== Pompier [" + id + "] Chef=" + estChef + " ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        if (estChef)
            requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_CHEF_EQUIPE, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_POMPIER, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_GESTIONNAIRE, null);
        
        // S'abonner aux mises à jour de danger
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_ANALYSTE_SIT, null);
    }

  



// Dans AgentPompier.java, modifier la méthode live() pour traiter les AlerteMessage

@Override
protected void live() {
    getLogger().info("[" + id + "] En attente d'ordre...");
    while (actif) {
        Message msg;
        while ((msg = nextMessage()) != null) {
            if (msg instanceof OrdreMessage) {
                OrdreMessage o = (OrdreMessage) msg;
                if ("EXTINCTION".equals(o.getTypeAction()) && !enMission) {
                    demarrerMission(o);
                }
            } else if (msg instanceof SimpleMessage) {
                SimpleMessage sm = (SimpleMessage) msg;
                if (sm.isFin()) {
                    getLogger().info("[" + id + "] FIN_ALERTE → Retour caserne.");
                    enMission = false;
                    statut = "EN_ATTENTE";
                    
                }
            } else if (msg instanceof AlerteMessage && enMission) {
                // ← NOUVEAU : Mettre à jour le danger réel
                AlerteMessage a = (AlerteMessage) msg;
                dangerActuel = a.getIndiceDanger();
                getLogger().fine("[" + id + "] Danger mis à jour: " + dangerActuel);
            }
        }

        if (enMission) avancerEtape();

        try { Thread.sleep(AGRConstants.EXTINCTION_STEP_MS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}


    private void demarrerMission(OrdreMessage o) {
        enMission   = true;
        statut      = "EN_ROUTE";
        progression = 0;
        eau         = 500;
        dangerActuel = 50; // valeur initiale par défaut
        cyclesSansDanger = 0;
        getLogger().warning("[" + id + "] MISSION ACCEPTÉE → Priorité: " + o.getPriorite());
        envoyerRapport(0, 90, "EN_ROUTE", "Départ en forêt.");
    }

    private void avancerEtape() {
        switch (statut) {
            case "EN_ROUTE":
                pause(AGRConstants.DEPLACEMENT_MS);
                if (!actif) return;
                statut = "SUR_ZONE";
                getLogger().info("[" + id + "] Arrivé en forêt. Début opérations.");
                envoyerRapport(0, 80, "SUR_ZONE", "Sur zone. Installation matériel.");
                statut = "EXTINCTION";
                break;

            case "EXTINCTION":
                // ← MODIFIÉ : progression liée au danger réel
                if (dangerActuel > 25) {
                    // Feu encore actif : progression possible mais lente si danger élevé
                    int gain;
                    if (dangerActuel > 80) {
                        gain = 3 + rng.nextInt(8);   // très lent si feu intense
                    } else if (dangerActuel > 50) {
                        gain = 6 + rng.nextInt(10);  // lent
                    } else {
                        gain = 10 + rng.nextInt(15); // normal
                    }
                    progression = Math.min(100, progression + gain);
                    cyclesSansDanger = 0;
                } else {
                    // Plus de danger : augmentation rapide vers 100%
                    cyclesSansDanger++;
                    progression = Math.min(100, progression + 15);
                }
                
                eau = Math.max(0, eau - 40 - rng.nextInt(30));
                
                // Danger résiduel basé sur le danger réel et la progression
                int dangerRes = Math.max(0, (int)(dangerActuel * (1.0 - progression/100.0)));
                
                getLogger().info(String.format("[%s] Extinction: %3d%% | DangerReel=%3d | Eau=%4dL | DangerRes=%3d",
                    id, progression, dangerActuel, eau, dangerRes));

                // ← MODIFIÉ : terminer seulement si danger réel < 25 ET progression >= 100
                if (progression >= 100 && dangerActuel < 25) {
                    statut    = "TERMINE";
                    enMission = false;
                    getLogger().warning("[" + id + "] ✓ MISSION ACCOMPLIE — Feu maîtrisé ! (Danger=" + dangerActuel + ")");
                    envoyerRapport(100, 0, "TERMINE", "Feu éteint. Danger résiduel nul.");
                    statut = "EN_ATTENTE";
                    getLogger().info("[" + id + "] De retour en attente. Prêt pour nouvelle mission.");
                } else if (progression >= 100 && dangerActuel >= 25) {
                    // Progression à 100% mais feu encore actif → continuer
                    progression = 85; // Reset partiel pour continuer
                    getLogger().warning("[" + id + "] Feu toujours actif (Danger=" + dangerActuel + "), continuation du travail.");
                    envoyerRapport(progression, dangerRes, "EXTINCTION", "Feu persistant, poursuite extinction.");
                } else {
                    envoyerRapport(progression, dangerRes, "EXTINCTION", "Extinction en cours.");
                }
                break;

            default:
                break;
        }
    }


 

    // Dans AgentPompier.java, modifier envoyerRapport() pour aussi envoyer au capteur

private void envoyerRapport(int prog, int danger, String stat, String obs) {
    RapportMessage r = new RapportMessage(id, prog, danger, stat, obs);
    
    // Envoyer au coordinateur (G2)
    broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
        AGRConstants.ROLE_DECIDEUR, r, AGRConstants.ROLE_GESTIONNAIRE);
    
    // ← NOUVEAU : Envoyer aussi au capteur de la zone pour qu'il sache que les pompiers agissent
    broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
        AGRConstants.ROLE_CAPTEUR, r, AGRConstants.ROLE_POMPIER);
    
    if (estChef)
        broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_SUPERVISEUR,
            new RapportMessage(id, prog, danger, stat, obs), AGRConstants.ROLE_GESTIONNAIRE);
}


    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

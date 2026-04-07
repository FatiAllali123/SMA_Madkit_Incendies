package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentChefOperations — G2 | Superviseur général
 * Reçoit les rapports finaux et les données de situation.
 * Rôle passif : archive, journalise, affiche le rapport final.
 */
public class AgentChefOperations extends Agent {

    private final List<String> journal = new ArrayList<>();
    private int nbRapports = 0;
    private String statut  = "VEILLE";

    @Override
    protected void activate() {
        getLogger().info("=== AgentChefOperations : Activation ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_SUPERVISEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_COMMANDANT, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
            AGRConstants.ROLE_ANALYSTE_SIT, null);
        journaliser("ChefOpérations opérationnel.");
    }

@Override
protected void live() {
    getLogger().info("ChefOpérations : En veille...");
    while (true) {
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Message msg;
        while ((msg = nextMessage()) != null) {  // ← boucle complète
            traiterMessage(msg);
        }
    }
}

    private void traiterMessage(Message msg) {
        nbRapports++;
        if (msg instanceof RapportMessage) {
            RapportMessage r = (RapportMessage) msg;
            getLogger().info("RAPPORT : " + r.getAgentEmetteur()
                + " | " + r.getProgressionExtinction() + "% | " + r.getStatut());
            journaliser(r.getAgentEmetteur() + " → " + r.getStatut()
                + " (" + r.getProgressionExtinction() + "%)");
            statut = "INTERVENTION";
        } else if (msg instanceof OrdreMessage) {
            OrdreMessage o = (OrdreMessage) msg;
            if ("RAPPORT_FINAL".equals(o.getTypeAction())) {
                statut = "TERMINEE";
                journaliser("FIN : " + o.getInstructions());
                afficherRapportFinal();
            }
        } else if (msg instanceof MeteoMessage) {
            MeteoMessage m = (MeteoMessage) msg;
            if (m.getIndiceRisque() > 60)
                journaliser("Météo risque=" + m.getIndiceRisque() + "/100");
        }
    }

    private void journaliser(String evt) {
        String entry = "[" + (System.currentTimeMillis() % 100000) + "ms] " + evt;
        journal.add(entry);
    }

    private void afficherRapportFinal() {
        getLogger().info("╔═════════════════════════════════════╗");
        getLogger().info("║     RAPPORT D'INTERVENTION FINAL    ║");
        getLogger().info("║  Statut    : " + statut);
        getLogger().info("║  Rapports  : " + nbRapports);
        getLogger().info("╠═════════════════════════════════════╣");
        for (String e : journal) getLogger().info("║ " + e);
        getLogger().info("╚═════════════════════════════════════╝");
    }
}
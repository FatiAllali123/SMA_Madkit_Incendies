package sma.incendie.messages;

import madkit.kernel.Message;

/**
 * Rapport terrain G3 → Coordinateur.
 */
public class RapportMessage extends Message {

    private static final long serialVersionUID = 1L;

    private final String agentEmetteur;
    private final int    progressionExtinction; // 0-100
    private final int    dangerResiduel;        // 0-100
    private final String statut;               // EN_ROUTE, SUR_ZONE, EXTINCTION, TERMINE, RETOUR
    private final String observations;

    public RapportMessage(String agentEmetteur, int progressionExtinction,
                          int dangerResiduel, String statut, String observations) {
        this.agentEmetteur         = agentEmetteur;
        this.progressionExtinction = progressionExtinction;
        this.dangerResiduel        = dangerResiduel;
        this.statut                = statut;
        this.observations          = observations;
    }

    public String getAgentEmetteur()         { return agentEmetteur; }
    public int    getProgressionExtinction() { return progressionExtinction; }
    public int    getDangerResiduel()        { return dangerResiduel; }
    public String getStatut()               { return statut; }
    public String getObservations()         { return observations; }
    public boolean estTermine()             { return "TERMINE".equals(statut); }

    @Override
    public String toString() {
        return String.format("[RAPPORT] %s | %d%% | DangerRésiduel=%d | %s",
            agentEmetteur, progressionExtinction, dangerResiduel, statut);
    }
}
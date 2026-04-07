package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.MeteoMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentMeteo — G1 | Rôle : Analyste_Meteo
 * Collecte et diffuse les conditions météorologiques de la forêt.
 */
public class AgentMeteo extends Agent {

    private double vitesseVent    = 15 + Math.random() * 20;
    private String directionVent  = "Sud-Ouest";
    private double temperature    = 22 + Math.random() * 10;
    private double humidite       = 45 + Math.random() * 25;
    private boolean actif         = true;
    private int cycle             = 0;

    private static final String[] DIRS = {
        "Nord","Nord-Est","Est","Sud-Est","Sud","Sud-Ouest","Ouest","Nord-Ouest"
    };
    private final Random rng = new Random();

    @Override
    protected void activate() {
        getLogger().info("=== AgentMeteo : Activation ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_METEO, null);
    }

    @Override
    protected void live() {
        while (actif) {
            cycle++;
            mettreAJour();

            int risque = calculerRisque();
            getLogger().info(String.format("[Météo C%02d] Vent=%.0fkm/h %s | T=%.1fC | H=%.1f%% | Risque=%d/100",
                cycle, vitesseVent, directionVent, temperature, humidite, risque));

            // Diffuser vers coordinateur G1 et analyste G2
            MeteoMessage m = new MeteoMessage(vitesseVent, directionVent, temperature, humidite, risque);
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
                AGRConstants.ROLE_COORD_SURV, m, AGRConstants.ROLE_METEO);
            broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
                AGRConstants.ROLE_ANALYSTE_SIT, new MeteoMessage(vitesseVent, directionVent, temperature, humidite, risque),
                AGRConstants.ROLE_METEO);

            if (risque > 65)
                getLogger().warning("⚠ RISQUE MÉTÉO ÉLEVÉ : " + risque + "/100 — Vent fort " + directionVent);

            Message msg = nextMessage();
            if (msg instanceof SimpleMessage && ((SimpleMessage) msg).isFin()) actif = false;

            try { Thread.sleep(AGRConstants.METEO_CYCLE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("AgentMeteo arrêté après " + cycle + " cycles.");
    }

    private void mettreAJour() {
        vitesseVent = Math.max(0, Math.min(80, vitesseVent + (rng.nextDouble()-0.5)*8));
        if (rng.nextInt(6) == 0) directionVent = DIRS[rng.nextInt(DIRS.length)];
        temperature = Math.max(10, Math.min(45, temperature + (rng.nextDouble()-0.5)*2));
        humidite    = Math.max(10, Math.min(90, humidite + (rng.nextDouble()-0.5)*5));
    }

    private int calculerRisque() {
        double cv = Math.min(100, vitesseVent / 80.0 * 100);
        double ch = (100 - humidite) / 90.0 * 100;
        double ct = Math.max(0, (temperature - 15) / 30.0 * 100);
        return (int) Math.min(100, 0.4*cv + 0.35*ch + 0.25*ct);
    }
}
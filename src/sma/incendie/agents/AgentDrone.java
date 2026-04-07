package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.AlerteMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

public class AgentDrone extends Agent {

    private final String id;
    private int     batterie            = 100;
    private boolean actif               = true;
    private int     dangerEstime        = 0;
    private boolean incendieVu          = false;
    private String  derniereZoneDetectee = "Zone_Nord"; // ← variable ajoutée avec valeur par défaut
    private final Random rng            = new Random();

    public AgentDrone(String id) { this.id = id; }

    @Override
    protected void activate() {
        getLogger().info("=== Drone [" + id + "] ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_DRONE, null);
    }

    @Override
    protected void live() {
        getLogger().info("[" + id + "] Patrouille démarrée.");
        while (actif && batterie > 10) {
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof AlerteMessage) {
                    AlerteMessage a = (AlerteMessage) msg;
                    if (a.getIndiceDanger() >= AGRConstants.SEUIL_ORANGE) {
                        incendieVu           = true;
                        derniereZoneDetectee = a.getZoneSource(); // ← on mémorise la vraie zone
                        dangerEstime         = a.getIndiceDanger() + rng.nextInt(10) - 5;
                        dangerEstime         = Math.max(0, Math.min(100, dangerEstime));
                        getLogger().warning("[" + id + "] Feu confirmé visuellement sur "
                            + derniereZoneDetectee + " ! Danger~" + dangerEstime);
                    }
                } else if (msg instanceof SimpleMessage && ((SimpleMessage) msg).isFin()) {
                    incendieVu = false;
                    actif      = false;
                }
            }

            if (incendieVu) {
                AlerteMessage confirmation = new AlerteMessage(
                    derniereZoneDetectee,              // ← vraie zone, pas "Vue_Aerienne_..."
                    75 + rng.nextDouble() * 15,
                    10 + rng.nextDouble() * 10,
                    dangerEstime,
                    getName(),
                    "Confirmation visuelle drone. Étendue estimée : "
                        + (5 + rng.nextInt(40)) + " ha."
                );
                broadcastMessageWithRole(
                    AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
                    AGRConstants.ROLE_COORD_SURV, confirmation, AGRConstants.ROLE_DRONE
                );
            } else {
                getLogger().info("[" + id + "] Patrouille normale. Batterie=" + batterie + "%");
            }

            batterie -= 2;
            try { Thread.sleep(AGRConstants.DRONE_CYCLE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (batterie <= 10)
            getLogger().warning("[" + id + "] Batterie critique. Retour base.");
    }

    @Override
    protected void end() {
        getLogger().info("[" + id + "] Arrêté. Batterie=" + batterie + "%");
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_DRONE);
    }
}
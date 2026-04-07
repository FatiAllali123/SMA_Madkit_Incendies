package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.AlerteMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentCapteur — G1 | Rôle : Capteur_Terrain
 *
 * Capteur dispersé dans la forêt. Surveille température et humidité
 * de sa zone locale et envoie des alertes au coordinateur.
 *
 * Logique simplifiée (pas de secteurs) :
 * - Un feu peut démarrer aléatoirement dans cette zone (prob faible)
 * - Quand le feu est actif, température monte et humidité descend
 * - FIN_ALERTE → reset complet
 */
public class AgentCapteur extends Agent {

    private final String zoneId;       // ex: "Zone_Nord", "Zone_Est"
    private double temperatureBase;
    private double humiditeBase;
    private boolean incendieActif = false;
    private int     cycle         = 0;
    private boolean actif         = true;
    // Cooldown après extinction pour éviter réallumage immédiat
    private int cooldown          = 0;
    private static final int COOLDOWN_CYCLES = 15;

    private final Random rng = new Random();

    public AgentCapteur(String zoneId) {
        this.zoneId          = zoneId;
        this.temperatureBase = 20.0 + rng.nextDouble() * 8.0;
        this.humiditeBase    = 50.0 + rng.nextDouble() * 25.0;
    }

    @Override
    protected void activate() {
        getLogger().info("=== Capteur [" + zoneId + "] : Activation ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
            AGRConstants.ROLE_CAPTEUR, null);
    }

    @Override
    protected void live() {
        while (actif) {
            cycle++;
            if (cooldown > 0) cooldown--;

            // Probabilité de départ de feu : seulement après cycle 6, hors cooldown
            if (!incendieActif && cycle >= 6 && cooldown == 0) {
                if (rng.nextInt(100) < 2) {   // 2% par cycle ≈ feu toutes ~5 minutes
                    incendieActif = true;
                    getLogger().warning("[" + zoneId + "] *** DÉPART DE FEU DÉTECTÉ ! ***");
                }
            }

            double temperature = mesurerTemperature();
            double humidite    = mesurerHumidite();
            int    danger      = calculerDanger(temperature, humidite);

            getLogger().info(String.format("[%s] C%02d | T=%.1fC | H=%.1f%% | Danger=%d/100%s",
                zoneId, cycle, temperature, humidite, danger,
                incendieActif ? " 🔥" : ""));

            // Envoyer alerte si danger dépasse le seuil
            if (danger >= AGRConstants.SEUIL_SURVEILLANCE) {
                AlerteMessage alerte = new AlerteMessage(
                    zoneId, temperature, humidite, danger, getName(),
                    incendieActif ? "Feu actif détecté" : "Conditions dangereuses"
                );
                broadcastMessageWithRole(
                    AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
                    AGRConstants.ROLE_COORD_SURV, alerte, AGRConstants.ROLE_CAPTEUR
                );
            }

            // Traiter les messages entrants
            Message msg;
            while ((msg = nextMessage()) != null) {
                if (msg instanceof SimpleMessage) {
                    SimpleMessage sm = (SimpleMessage) msg;
                    if (sm.isFin()) {
                        // Fin d'alerte globale → extinction et arrêt
                        incendieActif    = false;
                        temperatureBase  = 20.0 + rng.nextDouble() * 8.0;
                        humiditeBase     = 50.0 + rng.nextDouble() * 25.0;
                        cooldown         = COOLDOWN_CYCLES;
                        actif            = false;
                        getLogger().info("[" + zoneId + "] Feu éteint. Retour surveillance normale.");
                    } else if ("EXTINCTION_EN_COURS".equals(sm.getContenu())) {
                        // Les pompiers travaillent → le feu ralentit sa progression
                        if (incendieActif) {
                            temperatureBase = Math.max(temperatureBase - 5, 25);
                            humiditeBase    = Math.min(humiditeBase + 3, 60);
                        }
                    } else if ("INCENDIE_MAITRISE".equals(sm.getContenu())) {
                        incendieActif   = false;
                        cooldown        = COOLDOWN_CYCLES;
                        temperatureBase = 22.0 + rng.nextDouble() * 6.0;
                        humiditeBase    = 50.0 + rng.nextDouble() * 20.0;
                        getLogger().info("[" + zoneId + "] Incendie maîtrisé ! Retour à la normale.");
                    }
                }
            }

            try { Thread.sleep(AGRConstants.CAPTEUR_CYCLE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("[" + zoneId + "] Arrêté après " + cycle + " cycles.");
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_CAPTEUR);
    }

    // ── Mesures ──────────────────────────────────────────────────────────────

    private double mesurerTemperature() {
        if (incendieActif) {
            // Feu actif : température monte rapidement
            temperatureBase = Math.min(temperatureBase + 12 + rng.nextDouble() * 8, 95);
        } else {
            // Normal : petites variations
            temperatureBase += (rng.nextDouble() - 0.5) * 2;
            temperatureBase  = Math.max(15, Math.min(temperatureBase, 38));
        }
        return temperatureBase + (rng.nextDouble() - 0.5); // bruit de mesure
    }

    private double mesurerHumidite() {
        if (incendieActif) {
            humiditeBase = Math.max(humiditeBase - 8 - rng.nextDouble() * 5, 5);
        } else {
            humiditeBase += (rng.nextDouble() - 0.5) * 3;
            humiditeBase  = Math.max(35, Math.min(humiditeBase, 80));
        }
        return humiditeBase + (rng.nextDouble() - 0.5);
    }

    private int calculerDanger(double temp, double hum) {
        // Score température : 0% à 20°C, 100% à 95°C
        double ct = Math.max(0, Math.min(100, (temp - 20.0) / 75.0 * 100.0));
        // Score humidité : 0% à 80%, 100% à 5%
        double ch = Math.max(0, Math.min(100, (80.0 - hum) / 75.0 * 100.0));
        return (int)(0.65 * ct + 0.35 * ch);
    }
}
package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.AlerteMessage;
import sma.incendie.messages.RapportMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentCapteur v5 - Simulation Variée et Aléatoire
 * 
 * NOUVEAUTÉS :
 * 1. Intensité initiale aléatoire (permet d'avoir ORANGE, ROUGE, CRITIQUE ou EXTREME)
 * 2. Mémoire du dernier type de feu pour varier les simulations
 * 3. Propagation (MONTEE_NORMALE) ajustée selon la sévérité du feu
 */
public class AgentCapteur extends Agent {

    private final String zoneId;
    private double tempBase;
    private double humBase;
    private boolean feuActif = false;
    private int cycle = 0;
    private boolean actif = true;
    private int cooldown = 0;
    private boolean extinctionEnCours = false;

    private double intensiteFeu = 0.0;
    private static String zoneEnFeu = null;
    private static final Object lock = new Object();

    // Mémoire statique pour varier les types de feux entre les sessions
    private static String dernierTypeFeu = "AUCUN";

    private int pompiersActifs = 0;

    private static final int COOLDOWN_CYCLES = 25;
    private double monteeActuelle = 0.03;
    private static final double MONTEE_RALENTIE = 0.01;
    private static final double SEUIL_EXTINCTION = 0.02;

    private final Random rng = new Random();

    public AgentCapteur(String zoneId) {
        this.zoneId = zoneId;
        this.tempBase = 20.0 + rng.nextDouble() * 8.0;
        this.humBase = 52.0 + rng.nextDouble() * 22.0;
    }

    @Override
    protected void activate() {
        getLogger().info("=== Capteur [" + zoneId + "] : Activation ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_CAPTEUR, null);
    }

    @Override
    protected void live() {
        while (actif) {
            cycle++;
            if (cooldown > 0) cooldown--;

            // Départ de feu aléatoire et varié
            if (!feuActif && cycle >= 15 && cooldown == 0) {
                synchronized (lock) {
                    if (zoneEnFeu == null && rng.nextInt(100) < 2) {
                        genererFeuAleatoire();
                    }
                }
            }

            double temperature = mesurerTemperature();
            double humidite = mesurerHumidite();
            int danger = calculerDanger(temperature, humidite);

            String marqueur = feuActif ? String.format(" 🔥(%.0f%%)", intensiteFeu * 100) : "";
            getLogger().info(String.format("[%s] C%02d | T=%.1fC | H=%.1f%% | Danger=%d/100%s | Pompiers=%d",
                zoneId, cycle, temperature, humidite, danger, marqueur, pompiersActifs));

            if (danger >= AGRConstants.SEUIL_SURVEILLANCE) {
                AlerteMessage alerte = new AlerteMessage(
                    zoneId, temperature, humidite, danger, getName(),
                    feuActif ? "Feu détecté (intensité " + String.format("%.0f", intensiteFeu * 100) + "%)"
                            : "Conditions dangereuses"
                );
                broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE,
                    AGRConstants.ROLE_COORD_SURV, alerte, AGRConstants.ROLE_CAPTEUR);
            }

            Message msg;
            while ((msg = nextMessage()) != null) {
                traiterMessage(msg);
            }

            try {
                Thread.sleep(AGRConstants.CAPTEUR_CYCLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Génère un feu avec une intensité et une propagation aléatoire
     * en essayant de varier par rapport au dernier type de feu.
     */
    private void genererFeuAleatoire() {
        feuActif = true;
        zoneEnFeu = zoneId;
        pompiersActifs = 0;

        // Système de probabilités pour varier les types
        int tirage = rng.nextInt(100);
        String typeChoisi;

        // Si le dernier était EXTREME, on réduit les chances d'en avoir un autre
        if (dernierTypeFeu.equals("EXTREME") && tirage > 50) {
            tirage = rng.nextInt(50); // Force un type plus faible
        }

        if (tirage < 40) { // 40% chance ORANGE
            intensiteFeu = 0.05 + rng.nextDouble() * 0.10; // 5-15%
            monteeActuelle = 0.02 + rng.nextDouble() * 0.02;
            typeChoisi = "ORANGE";
        } else if (tirage < 70) { // 30% chance ROUGE
            intensiteFeu = 0.25 + rng.nextDouble() * 0.15; // 25-40%
            monteeActuelle = 0.04 + rng.nextDouble() * 0.02;
            typeChoisi = "ROUGE";
        } else if (tirage < 90) { // 20% chance CRITIQUE
            intensiteFeu = 0.50 + rng.nextDouble() * 0.15; // 50-65%
            monteeActuelle = 0.06 + rng.nextDouble() * 0.03;
            typeChoisi = "CRITIQUE";
        } else { // 10% chance EXTREME
            intensiteFeu = 0.75 + rng.nextDouble() * 0.15; // 75-90%
            monteeActuelle = 0.08 + rng.nextDouble() * 0.04;
            typeChoisi = "EXTREME";
        }

        dernierTypeFeu = typeChoisi;
        getLogger().warning(String.format("[%s] *** DÉPART DE FEU TYPE %s (Intensité: %.0f%%, Propagation: %.2f) ***", 
            zoneId, typeChoisi, intensiteFeu * 100, monteeActuelle));
    }

    private void traiterMessage(Message msg) {
        if (msg instanceof SimpleMessage) {
            SimpleMessage sm = (SimpleMessage) msg;
            if (sm.isFin()) {
                synchronized (lock) {
                    feuActif = false;
                    intensiteFeu = 0;
                    pompiersActifs = 0;
                    if (zoneEnFeu != null && zoneEnFeu.equals(zoneId)) {
                        zoneEnFeu = null;
                    }
                    cooldown = COOLDOWN_CYCLES;
                    actif = false;
                    getLogger().info("[" + zoneId + "] Système arrêté.");
                }
            } else if ("EXTINCTION_EN_COURS".equals(sm.getContenu())) {
                extinctionEnCours = true;
                if (feuActif) {
                    // Réduction équilibrée : 1.5% à 3% par cycle d'action
                    double reduction = 0.015 + rng.nextDouble() * 0.015;
                    intensiteFeu = Math.max(0, intensiteFeu - reduction);
                    
                    if (intensiteFeu < SEUIL_EXTINCTION && feuActif) {
                        feuActif = false;
                        synchronized (lock) {
                            if (zoneEnFeu != null && zoneEnFeu.equals(zoneId)) {
                                zoneEnFeu = null;
                            }
                        }
                        getLogger().warning("[" + zoneId + "] 🔥 FEU ÉTEINT PAR L'ACTION DES POMPIERS ! 🔥");
                    }
                }
            } else if ("INCENDIE_MAITRISE".equals(sm.getContenu())) {
                synchronized (lock) {
                    if (zoneEnFeu != null && zoneEnFeu.equals(zoneId)) {
                        feuActif = false;
                        intensiteFeu = 0;
                        pompiersActifs = 0;
                        zoneEnFeu = null;
                        extinctionEnCours = false;
                        cooldown = COOLDOWN_CYCLES;
                        tempBase = 22 + rng.nextDouble() * 6;
                        humBase = 50 + rng.nextDouble() * 20;
                        getLogger().info("[" + zoneId + "] Incendie maîtrisé. Retour normale.");
                    }
                }
            }
        }
    }

    private double mesurerTemperature() {
        if (feuActif) {
            double augmentation = (extinctionEnCours ? MONTEE_RALENTIE : monteeActuelle) 
                                  + rng.nextDouble() * 0.02;
            
            double reduction = extinctionEnCours ? 0.015 : 0.0;
            intensiteFeu = Math.min(1.0, Math.max(0, intensiteFeu + augmentation - reduction));
            
            double tempCible = 22 + intensiteFeu * 73;
            tempBase = tempBase + (tempCible - tempBase) * 0.3;
            tempBase = Math.min(95, Math.max(15, tempBase));
        } else {
            if (tempBase > 22) {
                tempBase = Math.max(22, tempBase - 0.5);
            }
            tempBase += (rng.nextDouble() - 0.5) * 2;
            tempBase = Math.max(15, Math.min(tempBase, 38));
        }
        return tempBase + (rng.nextDouble() - 0.5);
    }

    private double mesurerHumidite() {
        if (feuActif) {
            double humCible = 55 - intensiteFeu * 50;
            humBase = humBase + (humCible - humBase) * 0.2;
            humBase = Math.max(5, Math.min(80, humBase));
        } else {
            humBase += (rng.nextDouble() - 0.5) * 3;
            humBase = Math.max(35, Math.min(80, humBase));
        }
        return humBase + (rng.nextDouble() - 0.5);
    }

    private int calculerDanger(double temp, double hum) {
        double ct = Math.max(0, Math.min(100, (temp - 20.0) / 75.0 * 100.0));
        double ch = Math.max(0, Math.min(100, (80.0 - hum) / 75.0 * 100.0));
        return (int) (0.65 * ct + 0.35 * ch);
    }

    @Override
    protected void end() {
        getLogger().info("[" + zoneId + "] Arrêté après " + cycle + " cycles.");
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_CAPTEUR);
    }
}
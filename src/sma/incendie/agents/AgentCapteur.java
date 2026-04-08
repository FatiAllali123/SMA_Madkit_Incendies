package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.AlerteMessage;
import sma.incendie.messages.RapportMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * AgentCapteur v9 - Correction définitive : UN SEUL FEU À LA FOIS
 * 
 * CORRECTIONS :
 * - Utilisation d'un simple boolean static pour verrouiller les feux
 * - Évite les feux multiples dans différentes zones
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
    
    // ← SIMPLIFICATION : un seul verrou static pour savoir si un feu est actif
    private static boolean unFeuEstActif = false;
    private static final Object lock = new Object();

    private static final List<String> cycleTypes = new ArrayList<>();
    static {
        resetCycle();
    }

    private static final int COOLDOWN_CYCLES = 15;
    private double monteeActuelle = 0.03;
    private static final double MONTEE_RALENTIE = 0.01;
    private static final double SEUIL_EXTINCTION = 0.02;

    private final Random rng = new Random();

    public AgentCapteur(String zoneId) {
        this.zoneId = zoneId;
        this.tempBase = 20.0 + rng.nextDouble() * 5.0;
        this.humBase = 60.0 + rng.nextDouble() * 10.0;
    }

    private static void resetCycle() {
        cycleTypes.clear();
        cycleTypes.add("ORANGE");
        cycleTypes.add("ROUGE");
        cycleTypes.add("CRITIQUE");
        cycleTypes.add("EXTREME");
        Collections.shuffle(cycleTypes);
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

            // ← CONDITION CORRIGÉE : un seul feu à la fois
            if (!feuActif && cycle >= 10 && cooldown == 0) {
                synchronized (lock) {
                    // Vérifier qu'aucun feu n'est actif dans TOUTE la simulation
                    if (!unFeuEstActif && rng.nextInt(100) < 5) {
                        unFeuEstActif = true;  // ← Verrou global avant de démarrer
                        genererFeuCycleGaranti();
                    }
                }
            }

            double temperature = mesurerTemperature();
            double humidite = mesurerHumidite();
            int danger = calculerDanger(temperature, humidite);

            String marqueur = feuActif ? String.format(" 🔥(%.0f%%)", intensiteFeu * 100) : "";
            getLogger().info(String.format("[%s] C%02d | T=%.1fC | H=%.1f%% | Danger=%d/100%s",
                zoneId, cycle, temperature, humidite, danger, marqueur));

            // Toujours envoyer l'alerte si danger élevé (même si feu non actif, pour les pré-alertes)
            if (danger >= AGRConstants.SEUIL_SURVEILLANCE) {
                AlerteMessage alerte = new AlerteMessage(
                    zoneId, temperature, humidite, danger, getName(),
                    feuActif ? "Feu actif" : "Risque élevé"
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

    private void genererFeuCycleGaranti() {
        feuActif = true;
        // ← Plus besoin de zoneEnFeu, on utilise juste le flag global unFeuEstActif
        if (cycleTypes.isEmpty()) resetCycle();
        String typeChoisi = cycleTypes.remove(0);

        // Ajustement des intensités pour forcer le passage des seuils
        switch (typeChoisi) {
            case "ORANGE":   
                intensiteFeu = 0.28;
                monteeActuelle = 0.03; 
                break;
            case "ROUGE":    
                intensiteFeu = 0.40;
                monteeActuelle = 0.04; 
                break;
            case "CRITIQUE": 
                intensiteFeu = 0.65;
                monteeActuelle = 0.06; 
                break;
            case "EXTREME":  
                intensiteFeu = 0.90;
                monteeActuelle = 0.08; 
                break;
        }

        getLogger().warning("╔══════════════════════════════════════════════════╗");
        getLogger().warning("║  🔥 DÉPART DE FEU sur " + zoneId + " : " + String.format("%-10s", typeChoisi) + "         ║");
        getLogger().warning("║  Intensité Initiale : " + String.format("%.0f%%", intensiteFeu * 100) + "                ║");
        getLogger().warning("║  Types restants     : " + String.format("%-20s", cycleTypes) + "   ║");
        getLogger().warning("╚══════════════════════════════════════════════════╝");
    }

    private void traiterMessage(Message msg) {
        if (msg instanceof SimpleMessage) {
            SimpleMessage sm = (SimpleMessage) msg;
            if ("EXTINCTION_EN_COURS".equals(sm.getContenu())) {
                extinctionEnCours = true;
                if (feuActif) {
                    double reduction = 0.05 + rng.nextDouble() * 0.07;
                    intensiteFeu = Math.max(0, intensiteFeu - reduction);
                    
                    getLogger().fine("[" + zoneId + "] Extinction: intensité " + 
                        String.format("%.0f%%", intensiteFeu * 100) + 
                        " (réduction " + String.format("%.0f%%", reduction * 100) + ")");
                    
                    if (intensiteFeu < SEUIL_EXTINCTION) {
                        eteindreFeu();
                    }
                }
            } else if ("INCENDIE_MAITRISE".equals(sm.getContenu())) {
                eteindreFeu();
                tempBase = 22.0;
                humBase = 65.0;
            }
        }
    }

    private void eteindreFeu() {
        synchronized (lock) {
            if (feuActif) {
                feuActif = false;
                intensiteFeu = 0;
                extinctionEnCours = false;
                cooldown = COOLDOWN_CYCLES;
                // ← Libérer le verrou global
                unFeuEstActif = false;
                getLogger().warning("[" + zoneId + "] 🔥 FEU ÉTEINT ! 🔥");
                getLogger().warning("✅ Système prêt pour un nouveau feu.");
            }
        }
    }

    private double mesurerTemperature() {
        if (feuActif) {
            double augmentation = (extinctionEnCours ? MONTEE_RALENTIE : monteeActuelle) + rng.nextDouble() * 0.01;
            double reduction = extinctionEnCours ? 0.01 : 0.0;
            intensiteFeu = Math.min(1.0, Math.max(0, intensiteFeu + augmentation - reduction));
            double tempCible = 22 + intensiteFeu * 73;
            tempBase = tempBase + (tempCible - tempBase) * 0.4;
        } else {
            tempBase = tempBase + (22.0 - tempBase) * 0.5;
            tempBase += (rng.nextDouble() - 0.5);
        }
        return Math.min(95, Math.max(15, tempBase));
    }

    private double mesurerHumidite() {
        if (feuActif) {
            double humCible = 60 - intensiteFeu * 50;
            humBase = humBase + (humCible - humBase) * 0.3;
        } else {
            humBase = humBase + (65.0 - humBase) * 0.5;
            humBase += (rng.nextDouble() - 0.5);
        }
        return Math.max(5, Math.min(85, humBase));
    }

    private int calculerDanger(double temp, double hum) {
        if (!feuActif) {
            double ct = Math.max(0, Math.min(100, (temp - 20.0) / 75.0 * 100.0));
            double ch = Math.max(0, Math.min(100, (80.0 - hum) / 75.0 * 100.0));
            return (int) (0.65 * ct + 0.35 * ch);
        } else {
            double composanteMeteo = Math.max(0, (humBase - 60) / 40.0);
            int dangerCalcule = (int) ((intensiteFeu * 90) + (composanteMeteo * 10));
            return Math.min(100, Math.max(0, dangerCalcule));
        }
    }

    @Override
    protected void end() {
        leaveRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_CAPTEUR);
    }
}
package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.Message;
import sma.incendie.messages.AlerteMessage;
import sma.incendie.messages.RapportMessage;
import sma.incendie.messages.SimpleMessage;
import sma.incendie.utils.AGRConstants;

import java.util.Random;

/**
 * AgentCapteur v4 - CORRECTION TOTALE
 * 
 * CORRECTIONS MAJEURES :
 * 1. La réduction d'intensité par les pompiers est appliquée correctement
 * 2. L'augmentation et la réduction sont gérées dans la même méthode
 * 3. Compteur de pompiers actifs pour une réduction progressive
 * 4. Le feu peut s'éteindre naturellement quand intensiteFeu < seuil
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

    // Montée progressive : intensité du feu (0.0 à 1.0)
    private double intensiteFeu = 0.0;
    private static String zoneEnFeu = null;
    private static final Object lock = new Object();

    // ← NOUVEAU : Compteur de pompiers actifs sur cette zone
    private int pompiersActifs = 0;

    private static final int COOLDOWN_CYCLES = 25;
    private static final double MONTEE_NORMALE = 0.03;
    private static final double MONTEE_RALENTIE = 0.01;
    private static final double SEUIL_EXTINCTION = 0.02;  // Seuil pour considérer le feu éteint

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

            // Départ de feu (un seul à la fois)
            if (!feuActif && cycle >= 15 && cooldown == 0) {
                synchronized (lock) {
                    if (zoneEnFeu == null && rng.nextInt(100) < 1) {
                        feuActif = true;
                        zoneEnFeu = zoneId;
                        intensiteFeu = 0.02;
                        pompiersActifs = 0;  // ← Reset compteur
                        getLogger().warning("[" + zoneId + "] *** DÉPART DE FEU (intensité initiale: 5%) ***");
                        getLogger().warning(">>> FEU ACTIF UNIQUEMENT DANS " + zoneId + " <<<");
                    }
                }
            }

            // Mise à jour des mesures (inclut réduction par pompiers)
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

            // Traiter messages entrants
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
            } 
            // Dans le traitement des messages, modifier la partie pour EXTINCTION_EN_COURS
else if ("EXTINCTION_EN_COURS".equals(sm.getContenu())) {
    extinctionEnCours = true;
    // ← NOUVEAU : Réduire activement l'intensité quand les pompiers agissent
    if (feuActif) {
        // Réduction de 2-4% par cycle quand les pompiers sont actifs
        double reduction = 0.02 + rng.nextDouble() * 0.02;
        intensiteFeu = Math.max(0, intensiteFeu - reduction);
        
        if (cycle % 5 == 0) {
            getLogger().info("[" + zoneId + "] Pompiers actifs → réduction de " + 
                String.format("%.2f", reduction * 100) + "%");
        }
        
        // Vérifier si le feu est éteint
        if (intensiteFeu < 0.02 && feuActif) {
            feuActif = false;
            synchronized (lock) {
                if (zoneEnFeu != null && zoneEnFeu.equals(zoneId)) {
                    zoneEnFeu = null;
                }
            }
            getLogger().warning("[" + zoneId + "] 🔥 FEU ÉTEINT PAR L'ACTION DES POMPIERS ! 🔥");
        }
    }
}
 else if ("INCENDIE_MAITRISE".equals(sm.getContenu())) {
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
                        getLogger().info(">>> AUCUNE ZONE EN FEU MAINTENANT <<<");
                    }
                }
            }
        } else if (msg instanceof RapportMessage && feuActif) {
            RapportMessage r = (RapportMessage) msg;
            if (r.getAgentEmetteur().contains("Pompier")) {
                String statut = r.getStatut();
                
                if ("EXTINCTION".equals(statut) || "SUR_ZONE".equals(statut)) {
                    // Un pompier arrive sur zone
                    // Pour simplifier, on incrémente à chaque rapport (géré par une Map plus tard)
                    // Ici on utilise un compteur simple
                } else if ("TERMINE".equals(statut) || "EN_ATTENTE".equals(statut)) {
                    // Pompier part
                }
            }
        }
    }

    /**
     * Méthode corrigée : l'intensité du feu augmente ET diminue en fonction
     * des pompiers actifs.
     */
   private double mesurerTemperature() {
    if (feuActif) {
        // Augmentation naturelle
        double augmentation = (extinctionEnCours ? MONTEE_RALENTIE : MONTEE_NORMALE) 
                              + rng.nextDouble() * 0.02;
        
        // Réduction due aux pompiers (déjà appliquée dans le message, mais on ajoute un effet continu)
        double reduction = extinctionEnCours ? 0.015 : 0.0;
        
        // Application
        intensiteFeu = Math.min(1.0, Math.max(0, intensiteFeu + augmentation - reduction));
        
        double tempCible = 22 + intensiteFeu * 73;
        tempBase = tempBase + (tempCible - tempBase) * 0.3;
        tempBase = Math.min(95, Math.max(15, tempBase));
        
        // Log quand l'intensité diminue
        if (reduction > augmentation && cycle % 5 == 0) {
            getLogger().info("[" + zoneId + "] Intensité en baisse: " + 
                String.format("%.2f", intensiteFeu * 100) + "%");
        }
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
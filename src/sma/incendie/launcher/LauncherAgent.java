package sma.incendie.launcher;

import madkit.kernel.AbstractAgent;
import sma.incendie.agents.*;
import sma.incendie.gui.SimulationGUI;
import sma.incendie.utils.AGRConstants;


/**
 * LauncherAgent v2 — Forêt unique, agents simples et clairs.
 *
 * Ordre de lancement :
 *   0. Interface graphique (SimulationGUI)
 *   1. AgentChefOperations (G2 superviseur)
 *   2. AgentCoordinateur   (cerveau G1+G2+G3)
 *   3. 4× AgentCapteur dispersés dans la forêt (G1)
 *   4. 2× AgentDrone (G1)
 *   5. AgentMeteo (G1)
 *   6. 5× AgentPompier : 2 chefs + 3 normaux (G3)
 *   7. 3× AgentVehicule (G3)
 *   8. 2× AgentHelicoptere (G3)
 */
public class LauncherAgent extends AbstractAgent {

    @Override
    protected void activate() {
        log("╔══════════════════════════════════════════════════╗");
        log("║  SMA — Gestion Incendie Forêt  (v2 simplifiée) ║");
        log("║  1 forêt | Danger global | Ressources dynamiques║");
        log("╚══════════════════════════════════════════════════╝");

        
        // ── Etape 0 : Interface graphique ──
    getLogger().info("-- Etape 0 : Lancement de l'interface graphique --");
    lancer(new SimulationGUI(), "SimulationGUI");
    pause(1000);

        // ── 1. Commandement G2 ──────────────────────────────
        getLogger().info("-- Etape 1 : Commandement Central (G2) --");
        lancer(new AgentChefOperations(), "ChefOperations");
        pause(400);

        // ── 2. Coordinateur passerelle ──────────────────────
         getLogger().info("-- Etape 2 : Agent Coordinateur (Passerelle) --");
        lancer(new AgentCoordinateur(), "Coordinateur");
        pause(400);

        // ── 3. Capteurs G1 dispersés dans la forêt ──────────
        getLogger().info("-- Etape 3 : Agents de Surveillance (G1) --");
        lancer(new AgentCapteur("Zone_Nord"),  "Capteur_Nord");
        pause(150);
        lancer(new AgentCapteur("Zone_Sud"),   "Capteur_Sud");
        pause(150);
        lancer(new AgentCapteur("Zone_Est"),   "Capteur_Est");
        pause(150);
        lancer(new AgentCapteur("Zone_Ouest"), "Capteur_Ouest");
        pause(300);

        // ── 4. Drones ────────────────────────────────────────
        lancer(new AgentDrone("Drone_01"), "Drone_01");
        pause(150);
        lancer(new AgentDrone("Drone_02"), "Drone_02");
        pause(300);

        // ── 5. Météo ─────────────────────────────────────────
        lancer(new AgentMeteo(), "Meteo");
        pause(500);

        // ── 6. Pompiers G3 ───────────────────────────────────
        lancer(new AgentPompier("Pompier_01", true),  "Pompier_01_Chef");
        pause(150);
        lancer(new AgentPompier("Pompier_02", true),  "Pompier_02_Chef");
        pause(150);
        lancer(new AgentPompier("Pompier_03", false), "Pompier_03");
        pause(150);
        lancer(new AgentPompier("Pompier_04", false), "Pompier_04");
        pause(150);
        lancer(new AgentPompier("Pompier_05", false), "Pompier_05");
        pause(300);

        // ── 7. Véhicules G3 ──────────────────────────────────
        lancer(new AgentVehicule("Citerne_01"), "Citerne_01");
        pause(150);
        lancer(new AgentVehicule("Citerne_02"), "Citerne_02");
        pause(150);
        lancer(new AgentVehicule("Citerne_03"), "Citerne_03");
        pause(300);

        // ── 8. Hélicoptères G3 ───────────────────────────────
        lancer(new AgentHelicoptere("Helico_01"), "Helico_01");
        pause(150);
        lancer(new AgentHelicoptere("Helico_02"), "Helico_02");

        log("╔══════════════════════════════════════════════════╗");
        log("║  SYSTÈME OPÉRATIONNEL                           ║");
        log("║  G1 : 4 Capteurs + 2 Drones + 1 Météo          ║");
        log("║  G2 : Coordinateur + ChefOpérations             ║");
        log("║  G3 : 5 Pompiers + 3 Citernes + 2 Hélicos      ║");
        log("╚══════════════════════════════════════════════════╝");
    }

   protected void live() {
    while (true) {
        pause(1000); // garder l'agent vivant
    }
}

    // ── Helpers ─────────────────────────────────────────────────────────

    private void lancer(AbstractAgent agent, String nom) {
        ReturnCode rc = launchAgent(agent, 10);
        if (rc == ReturnCode.SUCCESS) {
            getLogger().info("  OK  " + nom);
        } else {
            getLogger().severe("  ECHEC  " + nom + " : " + rc);
        }
    }
    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void log(String s) { getLogger().info(s); }
}

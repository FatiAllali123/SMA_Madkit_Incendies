package sma.incendie.agents;

import madkit.kernel.Agent;
import madkit.kernel.AgentAddress;
import madkit.kernel.Message;
import sma.incendie.messages.*;
import sma.incendie.utils.AGRConstants;

import java.util.*;

/**
 * AgentCoordinateur v4 — Cerveau du système
 *
 * CORRECTIONS v4 :
 * 1. Déploiement PROGRESSIF : max 1 agent/cycle en ORANGE/ROUGE, max 2 en CRITIQUE/EXTREME
 * 2. Véhicules rappelés automatiquement quand pompiersDeployes == 0 (via RETOUR_BASE)
 * 3. Hélicos rappelés de même dès que le niveau redescend sous EXTREME
 * 4. Montée de niveau graduelle : le coordinateur attend un 2e cycle de confirmation
 *    avant d'escalader (évite les faux positifs d'un seul pic de capteur)
 * 5. Logs enrichis pour visualiser clairement chaque décision de déploiement
 */
public class AgentCoordinateur extends Agent {

    private final Map<String, Integer> dangerParZone = new LinkedHashMap<>();
    private int    dangerGlobal = 0;
    private String niveauActuel = "NORMAL";
    private String niveauCandidat = "NORMAL";   // niveau "en attente de confirmation"
    private int    cyclesConfirmation = 0;       // nb de cycles consécutifs sur le même niveau candidat
    private static final int CYCLES_CONFIRMATION = 2; // cycles nécessaires pour monter de niveau

    // Agents occupés (par AgentAddress MadKit)
    private final Set<AgentAddress> pompiersOccupes  = new HashSet<>();
    private final Set<AgentAddress> vehiculesOccupes = new HashSet<>();
    private final Set<AgentAddress> helicosOccupes   = new HashSet<>();

    private int pompiersDeployes  = 0;
    private int vehiculesDeployes = 0;
    private int helicosDeployes   = 0;

    private final Map<String, RapportMessage> dernierRapport = new LinkedHashMap<>();
    private int progressionMoyenne = 0;

    private int     cycle           = 0;
    private boolean incendieEnCours = false;
    private boolean finDeclaree     = false;
    private int     cyclesDangerBas = 0;
    private static final int CYCLES_AVANT_FIN = 4;

    @Override
    protected void activate() {
        getLogger().info("=== AgentCoordinateur v4 ===");
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, AGRConstants.ROLE_COORD_SURV, null);
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_DECIDEUR, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT, AGRConstants.ROLE_SUPERVISEUR, null);
        createGroup(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, false, null);
        requestRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_DECIDEUR, null);
    }

    @Override
    protected void live() {
        try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        getLogger().info("Coordinateur actif.");

        while (true) {
            cycle++;
            Message msg;
            while ((msg = nextMessage()) != null) traiterMessage(msg);

            dangerGlobal = dangerParZone.values().stream().mapToInt(i -> i).max().orElse(0);
            evaluerEtAjuster();
            diffuserDangerAuxPompiers();
            if (cycle % 3 == 0) afficherTableauBord();

            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    protected void end() {
        getLogger().info("Coordinateur arrêté. Cycles=" + cycle);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Traitement des messages entrants
    // ─────────────────────────────────────────────────────────────────────────

    private void traiterMessage(Message msg) {
        if (msg instanceof AlerteMessage) {
            AlerteMessage a = (AlerteMessage) msg;
            dangerParZone.put(a.getZoneSource(), a.getIndiceDanger());

        } else if (msg instanceof RapportMessage) {
            RapportMessage r = (RapportMessage) msg;
            dernierRapport.put(r.getAgentEmetteur(), r);

            // Progression moyenne des pompiers actifs
            OptionalDouble moy = dernierRapport.values().stream()
                .filter(rr -> rr.getAgentEmetteur().contains("Pompier") && !rr.estTermine())
                .mapToInt(RapportMessage::getProgressionExtinction).average();
            if (moy.isPresent()) progressionMoyenne = (int) moy.getAsDouble();

            // Pompier terminé → libérer pour réaffectation
            if (r.estTermine() && r.getAgentEmetteur().contains("Pompier")) {
                AgentAddress adr = trouverParNom(pompiersOccupes, r.getAgentEmetteur());
                if (adr != null) {
                    pompiersOccupes.remove(adr);
                    pompiersDeployes = Math.max(0, pompiersDeployes - 1);
                    getLogger().info("✓ " + r.getAgentEmetteur() + " terminé et libéré → disponible.");
                }

                // ── CORRECTION : NE PAS rappeler les véhicules quand un pompier termine.
                // Ils restent disponibles pour le prochain feu jusqu'à declarerFinAlerte().
                // Rappeler ici privait le 2ème incendie de ravitaillement en eau.
            }

        } else if (msg instanceof MeteoMessage) {
            MeteoMessage m = (MeteoMessage) msg;
            if (m.getIndiceRisque() > 65)
                getLogger().warning("⚠ MÉTÉO Risque=" + m.getIndiceRisque()
                    + " Vent=" + String.format("%.0f", m.getVitesseVent()) + "km/h " + m.getDirectionVent());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logique de décision et déploiement progressif
    // ─────────────────────────────────────────────────────────────────────────

  private void evaluerEtAjuster() {
    String niveauCalcule = calculerNiveau(dangerGlobal);

    // ── Confirmation de montée de niveau (évite réaction sur un seul pic) ──
    String niveauEffectif;
    int ordreActuel  = ordreNiveau(niveauActuel);
    int ordreCalcule = ordreNiveau(niveauCalcule);

    if (ordreCalcule > ordreActuel) {
        // Montée de niveau : on attend confirmation
        if (niveauCalcule.equals(niveauCandidat)) {
            cyclesConfirmation++;
        } else {
            niveauCandidat     = niveauCalcule;
            cyclesConfirmation = 1;
        }
        niveauEffectif = (cyclesConfirmation >= CYCLES_CONFIRMATION) ? niveauCalcule : niveauActuel;
    } else {
        // Descente ou stabilité : immédiat
        niveauCandidat     = niveauCalcule;
        cyclesConfirmation = 0;
        niveauEffectif     = niveauCalcule;
    }

    if (!niveauEffectif.equals(niveauActuel)) {
        getLogger().warning("=== NIVEAU : " + niveauActuel + " → " + niveauEffectif
            + " (Danger=" + dangerGlobal + "/100) ===");
        niveauActuel = niveauEffectif;
    }


     // ═══════════════════════════════════════════════════════════════════════
    // ← NOUVEAU : Informer les capteurs de l'état de l'extinction
    // ═══════════════════════════════════════════════════════════════════════
    if (pompiersDeployes > 0) {
        informerCapteursExtinction();
    } else if (incendieEnCours && pompiersDeployes == 0) {
        // Plus de pompiers mais feu encore là ? Problème - on rappelle
        getLogger().warning("⚠ Feu actif mais aucun pompier déployé !");
    }
    
    // Diffuser le danger aux pompiers
    diffuserDangerAuxPompiers();


    // ═══════════════════════════════════════════════════════════════════════
    // ← NOUVEAU : Diffuser le danger actuel à tous les pompiers en mission
    // ═══════════════════════════════════════════════════════════════════════
    diffuserDangerAuxPompiers();

    // ── Gestion fin d'alerte ──
    if ("NORMAL".equals(niveauActuel)) {
        if (incendieEnCours) {
            cyclesDangerBas++;
            if (cyclesDangerBas >= CYCLES_AVANT_FIN && !finDeclaree) declarerFinAlerte();
        }
        return;
    }

    cyclesDangerBas = 0;
    incendieEnCours = true;

    // ── Cibles selon niveau ──
    int cibleP = 0, cibleV = 0, cibleH = 0;
    switch (niveauActuel) {
        case "ORANGE":   cibleP = 2; cibleV = 1; cibleH = 0; break;
        case "ROUGE":    cibleP = 3; cibleV = 2; cibleH = 0; break;
        case "CRITIQUE": cibleP = 5; cibleV = 3; cibleH = 0; break;
        case "EXTREME":  cibleP = 5; cibleV = 3; cibleH = 2; break;
    }

    // ── Déploiement progressif — max agents par cycle selon urgence ──
    boolean urgence = "EXTREME".equals(niveauActuel) || "CRITIQUE".equals(niveauActuel);
    int maxParCycle = urgence ? 2 : 1;

    String prio = urgence ? "URGENTE" : "HAUTE";

    int mpq = Math.max(0, cibleP - pompiersDeployes);
    int mvq = Math.max(0, cibleV - vehiculesDeployes);
    int mhq = Math.max(0, cibleH - helicosDeployes);

    // Pompiers : envoyer au plus maxParCycle ce cycle
    if (mpq > 0) {
        int aEnvoyer = Math.min(mpq, maxParCycle);
        int n = envoyerCibles(aEnvoyer, AGRConstants.ROLE_POMPIER, pompiersOccupes,
            new OrdreMessage("EXTINCTION", prio, "Intervention feu actif en forêt."));
        pompiersDeployes += n;
        if (n > 0) getLogger().warning(">>> +" + n + " pompier(s) envoyé(s) [cible=" + cibleP
            + ", actifs=" + pompiersDeployes + "] (manque encore " + Math.max(0, mpq - n) + ")");
    }

    // Véhicules : envoyer au plus 1 par cycle
    if (mvq > 0) {
        int aEnvoyer = Math.min(mvq, 1);
        int n = envoyerCibles(aEnvoyer, AGRConstants.ROLE_CONDUCTEUR, vehiculesOccupes,
            new OrdreMessage("SUPPORT_TRANSPORT", "HAUTE", "Transport et ravitaillement eau."));
        vehiculesDeployes += n;
        if (n > 0) getLogger().warning(">>> +" + n + " véhicule(s) envoyé(s) [cible=" + cibleV
            + ", actifs=" + vehiculesDeployes + "]");
    }

    // Hélicos : uniquement en EXTREME, 1 par cycle max
    if (mhq > 0) {
        int n = envoyerCibles(1, AGRConstants.ROLE_RENFORT, helicosOccupes,
            new OrdreMessage("ARROSAGE_AERIEN", "URGENTE", "Arrosage aérien immédiat."));
        helicosDeployes += n;
        if (n > 0) getLogger().warning(">>> +" + n + " hélico(s) envoyé(s) [cible=" + cibleH
            + ", actifs=" + helicosDeployes + "]");
    }

    // Si niveau redescend sous EXTREME, rappeler les hélicos
    if (!"EXTREME".equals(niveauActuel) && helicosDeployes > 0) {
        getLogger().info(">> Niveau < EXTREME → rappel hélicoptères.");
        rappelerHelicos();
    }
}

    // ─────────────────────────────────────────────────────────────────────────
    // Rappel des ressources
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rappelle tous les véhicules sur zone.
     * Leur envoie RETOUR_BASE (pas FIN_ALERTE, ils restent actifs pour future mission).
     */
    private void rappelerVehicules() {
        if (vehiculesOccupes.isEmpty()) return;
        SimpleMessage retour = new SimpleMessage(SimpleMessage.RETOUR_BASE);
        for (AgentAddress addr : new HashSet<>(vehiculesOccupes)) {
            sendMessage(addr, retour);
        }
        getLogger().info(">> " + vehiculesDeployes + " véhicule(s) rappelé(s) au dépôt.");
        vehiculesOccupes.clear();
        vehiculesDeployes = 0;
    }

    /**
     * Rappelle tous les hélicoptères.
     * Leur envoie RETOUR_BASE (ils restent actifs pour nouvelle mission EXTREME).
     */
    private void rappelerHelicos() {
        if (helicosOccupes.isEmpty()) return;
        SimpleMessage retour = new SimpleMessage(SimpleMessage.RETOUR_BASE);
        for (AgentAddress addr : new HashSet<>(helicosOccupes)) {
            sendMessage(addr, retour);
        }
        getLogger().info(">> " + helicosDeployes + " hélico(s) rappelé(s) à la base.");
        helicosOccupes.clear();
        helicosDeployes = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envoi ciblé
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envoie le message à exactement N agents libres du rôle donné.
     * Retourne le nombre réellement envoyés.
     */
    private int envoyerCibles(int n, String role, Set<AgentAddress> occupes, OrdreMessage template) {
        List<AgentAddress> liste = getAgentsWithRole(
            AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, role);
        if (liste == null || liste.isEmpty()) return 0;

        int envoyes = 0;
        for (AgentAddress addr : liste) {
            if (envoyes >= n) break;
            if (occupes.contains(addr)) continue;
            OrdreMessage ordre = new OrdreMessage(template.getTypeAction(),
                template.getPriorite(), template.getInstructions());
            sendMessage(addr, ordre);
            occupes.add(addr);
            envoyes++;
        }
        return envoyes;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fin d'alerte
    // ─────────────────────────────────────────────────────────────────────────

 private void declarerFinAlerte() {
    if (finDeclaree) return;
    finDeclaree = true;
    getLogger().info("╔════════════════════════════════════╗");
    getLogger().info("║  INCENDIE MAÎTRISÉ — FIN D'ALERTE ║");
    getLogger().info("╚════════════════════════════════════╝");
    
    // Créer le message une seule fois
    SimpleMessage fin = new SimpleMessage(SimpleMessage.FIN_ALERTE);
    
    // 1. Envoyer FIN_ALERTE à TOUS les pompiers
    List<AgentAddress> tousPompiers = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_POMPIER);
    if (tousPompiers != null) {
        for (AgentAddress addr : tousPompiers) {
            sendMessage(addr, fin);
        }
    }
    
    // 2. Envoyer aux véhicules
    List<AgentAddress> tousVehicules = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_CONDUCTEUR);
    if (tousVehicules != null) {
        for (AgentAddress addr : tousVehicules) {
            sendMessage(addr, fin);
        }
    }
    
    // 3. Envoyer aux hélicoptères
    List<AgentAddress> tousHelicos = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_RENFORT);
    if (tousHelicos != null) {
        for (AgentAddress addr : tousHelicos) {
            sendMessage(addr, fin);
        }
    }
    
    // 4. Informer les capteurs
    informerCapteursFinExtinction();
    
    // 5. Envoyer rapport final au superviseur
    broadcastMessageWithRole(AGRConstants.COMMUNITY, AGRConstants.GROUP_COMMANDEMENT,
        AGRConstants.ROLE_SUPERVISEUR,
        new OrdreMessage("RAPPORT_FINAL", "NORMALE",
            "P=" + pompiersDeployes + " V=" + vehiculesDeployes + " H=" + helicosDeployes),
        AGRConstants.ROLE_DECIDEUR);

    // 6. Attendre un peu que les messages soient traités
    try { Thread.sleep(500); } catch (InterruptedException e) {}
    
    // 7. Puis vider les ensembles
    pompiersOccupes.clear();
    vehiculesOccupes.clear();
    helicosOccupes.clear();
    pompiersDeployes = 0;
    vehiculesDeployes = 0;
    helicosDeployes = 0;
    niveauActuel = "NORMAL";
    niveauCandidat = "NORMAL";
    cyclesConfirmation = 0;
    incendieEnCours = false;
    cyclesDangerBas = 0;
    finDeclaree = false;  // ← CORRECTION CRITIQUE : permettre la détection du prochain incendie
    dangerParZone.clear();
    dernierRapport.clear();
    progressionMoyenne = 0;
    getLogger().info("✅ Système réinitialisé — Prêt pour prochain incendie.");
}

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaires
    // ─────────────────────────────────────────────────────────────────────────

    private String calculerNiveau(int d) {
        if (d >= 85)                              return "EXTREME";
        if (d >= AGRConstants.SEUIL_CRITIQUE)     return "CRITIQUE";
        if (d >= AGRConstants.SEUIL_ROUGE)        return "ROUGE";
        if (d >= AGRConstants.SEUIL_ORANGE)       return "ORANGE";
        return "NORMAL";
    }

    /** Retourne un entier pour comparer les niveaux entre eux. */
    private int ordreNiveau(String niveau) {
        switch (niveau) {
            case "EXTREME":  return 4;
            case "CRITIQUE": return 3;
            case "ROUGE":    return 2;
            case "ORANGE":   return 1;
            default:         return 0;
        }
    }

    private AgentAddress trouverParNom(Set<AgentAddress> set, String nom) {
        for (AgentAddress a : set) {
            if (a.toString().contains(nom) || nom.contains(a.toString())) return a;
        }
        return null;
    }

    private void afficherTableauBord() {
        if (!incendieEnCours && dangerGlobal == 0) return;
        StringBuilder zones = new StringBuilder();
        dangerParZone.forEach((z, d) -> {
            if (d >= AGRConstants.SEUIL_SURVEILLANCE) zones.append(z).append("=").append(d).append(" ");
        });
        getLogger().info(String.format(
            "📊 [C%02d] Danger=%d | %-8s | P=%d/%d V=%d H=%d | Prog=%d%%",
            cycle, dangerGlobal, niveauActuel,
            pompiersDeployes, pompiersOccupes.size(),
            vehiculesDeployes, helicosDeployes,
            progressionMoyenne));
        if (zones.length() > 0) getLogger().info("    Zones actives: " + zones);
        if (!niveauCandidat.equals(niveauActuel))
            getLogger().info("    (Niveau candidat: " + niveauCandidat
                + " — confirmation " + cyclesConfirmation + "/" + CYCLES_CONFIRMATION + ")");
    }



    // Dans AgentCoordinateur.java - ajouter cette méthode

/**
 * Diffuse le danger actuel à tous les pompiers en mission
 * pour qu'ils sachent si le feu est toujours actif.
 */
private void diffuserDangerAuxPompiers() {
    if (dangerGlobal == 0) return;
    
    // Créer un message d'alerte avec le danger actuel
    AlerteMessage dangerMsg = new AlerteMessage(
        "Global",           // zone source
        0,                  // température (non utilisé)
        0,                  // humidité (non utilisé)
        dangerGlobal,       // indice de danger
        "Coordinateur",     // source
        "Mise à jour danger global: " + dangerGlobal + "/100"
    );
    
    // Diffuser à tous les pompiers occupés (en mission)
    int nbEnvoyes = 0;
    for (AgentAddress addr : pompiersOccupes) {
        sendMessage(addr, dangerMsg);
        nbEnvoyes++;
    }
    
    // Diffuser aussi aux pompiers disponibles pour info (optionnel)
    List<AgentAddress> tousPompiers = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_INTERVENTION, AGRConstants.ROLE_POMPIER);
    if (tousPompiers != null) {
        for (AgentAddress addr : tousPompiers) {
            if (!pompiersOccupes.contains(addr)) {
                sendMessage(addr, dangerMsg);
            }
        }
    }
    
    if (nbEnvoyes > 0 && cycle % 5 == 0) {
        getLogger().fine("Danger=" + dangerGlobal + " diffusé à " + nbEnvoyes + " pompier(s)");
    }
}

private void informerCapteursExtinction() {
    if (pompiersDeployes == 0) return;
    
    SimpleMessage msg = new SimpleMessage("EXTINCTION_EN_COURS");
    
    // Envoyer à tous les capteurs
    List<AgentAddress> capteurs = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, 
        AGRConstants.ROLE_CAPTEUR);
    
    if (capteurs != null) {
        for (AgentAddress capteur : capteurs) {
            sendMessage(capteur, msg);
        }
        if (cycle % 5 == 0) {
            getLogger().info("📢 " + pompiersDeployes + " pompier(s) actif(s) → notification envoyée aux capteurs");
        }
    }
}

private void informerCapteursFinExtinction() {
    SimpleMessage msg = new SimpleMessage("INCENDIE_MAITRISE");
    
    List<AgentAddress> capteurs = getAgentsWithRole(
        AGRConstants.COMMUNITY, AGRConstants.GROUP_SURVEILLANCE, 
        AGRConstants.ROLE_CAPTEUR);
    
    if (capteurs != null) {
        for (AgentAddress capteur : capteurs) {
            sendMessage(capteur, msg);
        }
    }
    getLogger().info("📢 FIN D'EXTINCTION notifiée aux capteurs");
}

}
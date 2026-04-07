package sma.incendie.utils;

/**
 * Constantes AGR — version simplifiée : une seule forêt.
 */
public final class AGRConstants {

    public static final String COMMUNITY          = "GestionIncendieForet";

    // Groupes
    public static final String GROUP_SURVEILLANCE = "SurveillanceForet";
    public static final String GROUP_COMMANDEMENT = "CommandementCentral";
    public static final String GROUP_INTERVENTION = "EquipeIntervention";

    // Rôles G1
    public static final String ROLE_CAPTEUR       = "Capteur_Terrain";
    public static final String ROLE_DRONE         = "Drone_Surveillance";
    public static final String ROLE_METEO         = "Analyste_Meteo";
    public static final String ROLE_COORD_SURV    = "Coord_Surveillance";

    // Rôles G2
    public static final String ROLE_DECIDEUR      = "DecideurStrategique";
    public static final String ROLE_SUPERVISEUR   = "Superviseur_General";
    public static final String ROLE_GESTIONNAIRE  = "GestionnaireRessources";
    public static final String ROLE_ANALYSTE_SIT  = "AnalysteSituation";
    public static final String ROLE_COMMANDANT    = "CommandantTerrain";

    // Rôles G3
    public static final String ROLE_POMPIER       = "Pompier";
    public static final String ROLE_CHEF_EQUIPE   = "ChefEquipe";
    public static final String ROLE_CONDUCTEUR    = "Conducteur";
    public static final String ROLE_RENFORT       = "Renfort_Ext";
    public static final String ROLE_OBSERVATEUR   = "Observateur_Terrain";

    // Niveaux de danger (0-100)
    public static final int SEUIL_SURVEILLANCE    = 35;  // capteur envoie alerte
    public static final int SEUIL_ORANGE          = 50;  // 2 pompiers + 1 véhicule
    public static final int SEUIL_ROUGE           = 70;  // 4 pompiers + 2 véhicules
    public static final int SEUIL_CRITIQUE        = 85;  // tout le monde + hélicos

    // Intervalles (ms)
    public static final long CAPTEUR_CYCLE_MS     = 3000;
    public static final long DRONE_CYCLE_MS       = 4000;
    public static final long METEO_CYCLE_MS       = 5000;
    public static final long DEPLACEMENT_MS       = 4000;
    public static final long EXTINCTION_STEP_MS   = 2000;

    private AGRConstants() {}
}
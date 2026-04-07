package sma.incendie.messages;

import madkit.kernel.Message;

/**
 * Message d'alerte — version simplifiée sans notion de secteur.
 * Chaque capteur signale sa mesure locale avec son identifiant de zone.
 */
public class AlerteMessage extends Message {

    private static final long serialVersionUID = 1L;

    private final String zoneSource;   // nom du capteur / zone (ex: "Zone_Nord")
    private final double temperature;
    private final double humidite;
    private final int    indiceDanger; // 0-100
    private final String source;       // nom de l'agent émetteur
    private final String description;

    public AlerteMessage(String zoneSource, double temperature, double humidite,
                         int indiceDanger, String source, String description) {
        this.zoneSource   = zoneSource;
        this.temperature  = temperature;
        this.humidite     = humidite;
        this.indiceDanger = indiceDanger;
        this.source       = source;
        this.description  = description;
    }

    public String getZoneSource()   { return zoneSource; }
    public double getTemperature()  { return temperature; }
    public double getHumidite()     { return humidite; }
    public int    getIndiceDanger() { return indiceDanger; }
    public String getSource()       { return source; }
    public String getDescription()  { return description; }

    public String getNiveauAlerte() {
        if (indiceDanger < 35) return "NORMAL";
        if (indiceDanger < 50) return "ORANGE";
        if (indiceDanger < 70) return "ROUGE";
        if (indiceDanger < 85) return "CRITIQUE";
        return "EXTREME";
    }

    @Override
    public String toString() {
        return String.format("[ALERTE] %s | T=%.1fC | H=%.1f%% | Danger=%d/100 | %s",
            zoneSource, temperature, humidite, indiceDanger, getNiveauAlerte());
    }
}
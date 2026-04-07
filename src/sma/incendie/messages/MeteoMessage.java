package sma.incendie.messages;

import madkit.kernel.Message;

public class MeteoMessage extends Message {

    private static final long serialVersionUID = 1L;

    private final double vitesseVent;
    private final String directionVent;
    private final double temperature;
    private final double humidite;
    private final int    indiceRisque;

    public MeteoMessage(double vitesseVent, String directionVent,
                        double temperature, double humidite, int indiceRisque) {
        this.vitesseVent   = vitesseVent;
        this.directionVent = directionVent;
        this.temperature   = temperature;
        this.humidite      = humidite;
        this.indiceRisque  = indiceRisque;
    }

    public double getVitesseVent()   { return vitesseVent; }
    public String getDirectionVent() { return directionVent; }
    public double getTemperature()   { return temperature; }
    public double getHumidite()      { return humidite; }
    public int    getIndiceRisque()  { return indiceRisque; }

    @Override
    public String toString() {
        return String.format("[METEO] Vent=%.1fkm/h %s | T=%.1fC | H=%.1f%% | Risque=%d/100",
            vitesseVent, directionVent, temperature, humidite, indiceRisque);
    }
}
package sma.incendie.messages;

import madkit.kernel.Message;

/**
 * Ordre du coordinateur vers G3.
 * Contient le type de mission et la priorité.
 */
public class OrdreMessage extends Message {

    private static final long serialVersionUID = 1L;
    private static int compteur = 0;

    private final int    idOrdre;
    private final String typeAction;   // EXTINCTION, ARROSAGE_AERIEN, RETOUR_BASE, RENFORT
    private final String priorite;    // FAIBLE, MOYENNE, HAUTE, URGENTE
    private final String instructions;

    
    public OrdreMessage(String typeAction, String priorite, String instructions) {
        this.idOrdre      = ++compteur;
        this.typeAction   = typeAction;
        this.priorite     = priorite;
        this.instructions = instructions;
    }

    public int    getIdOrdre()    { return idOrdre; }
    public String getTypeAction() { return typeAction; }
    public String getPriorite()   { return priorite; }
    public String getInstructions(){ return instructions; }

    @Override
    public String toString() {
        return String.format("[ORDRE #%03d] %s | Priorité=%s", idOrdre, typeAction, priorite);
    }
}
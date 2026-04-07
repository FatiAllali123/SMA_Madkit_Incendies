package sma.incendie.messages;

import madkit.kernel.Message;

public class SimpleMessage extends Message {

    private static final long serialVersionUID = 1L;

    public static final String FIN_ALERTE  = "FIN_ALERTE";
    public static final String ACK         = "ACK";
    public static final String RETOUR_BASE = "RETOUR_BASE";

    private final String contenu;

    public SimpleMessage(String contenu) { this.contenu = contenu; }

    public String  getContenu() { return contenu; }
    public boolean isFin()      { return FIN_ALERTE.equals(contenu); }
    public boolean isAck()      { return ACK.equals(contenu); }

    @Override
    public String toString() { return "[MSG] " + contenu; }
}
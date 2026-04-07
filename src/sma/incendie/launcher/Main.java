package sma.incendie.launcher;

import madkit.kernel.Madkit;

/**
 * ============================================================
 * Main.java — Point d'entree Java unique
 * ============================================================
 * Demarre MadKit 5.3.1 et lance le LauncherAgent.
 *
 * Execution Windows :
 *   java -cp "lib\madkit-5.3.1.jar;out" sma.incendie.launcher.Main
 *
 * Execution Linux/macOS :
 *   java -cp "lib/madkit-5.3.1.jar:out" sma.incendie.launcher.Main
 *
 * Compatible MadKit 5.3.1
 * ============================================================
 */
public class Main {

    public static void main(String[] args) {
        /*
         * Arguments MadKit importants :
         *   --launchAgents   : classe de l'agent de demarrage
         *   --agentLogLevel  : niveau de log (ALL / INFO / WARNING)
         *   --desktop        : ouvre le bureau MadKit (Kool GUI)
         *   --noDesktop      : sans bureau MadKit (juste notre GUI)
         */
        new Madkit(
            "--launchAgents",  LauncherAgent.class.getName(),
            "--agentLogLevel", "INFO",
            "--desktop"        // Commenter cette ligne pour desactiver la GUI MadKit
        );
    }
}

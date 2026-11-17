# Lecteur d'écran (Android)

Lit à voix haute le texte affiché à l'écran, même quand il est dans une image (comics, BD, captures...).

## Installer
1. Ouvre ce dossier dans **Android Studio** (File > Open), laisse la synchro Gradle se faire.
2. Branche ton téléphone (débogage USB activé) puis **Run ▶**, ou **Build > Build APK(s)** pour obtenir un .apk à installer.

## Utiliser
1. Ouvre l'appli, choisis la langue et la vitesse, appuie sur **Activer** (3 autorisations : affichage par-dessus les autres applis, notifications, capture d'écran).
2. Va dans ton navigateur / lecteur de comics. Une bulle 🔊 flotte (déplaçable).
3. Touche la bulle : le texte détecté s'entoure en bleu.
4. Touche un bloc : il est lu (il passe en vert). Re-touche pour arrêter. Glisse le doigt pour entourer une zone précise.
5. **Tout lire** / **Stop** / **Fermer** en bas. Arrêt complet : bouton « Arrêter » dans la notification.

## Notes
- OCR 100 % hors ligne (ML Kit, alphabet latin). Il faut une voix installée pour la langue choisie (Paramètres > Synthèse vocale).
- Android 8+ (testé sur le papier pour Android 14 : la MediaProjection n'est créée qu'une fois).

## Obtenir l'APK sans Android Studio
Crée un dépôt GitHub (privé si tu veux), envoie-y tout ce dossier, puis onglet **Actions > Build APK > Run workflow**.
Au bout de ~3 minutes, télécharge l'artefact **LecteurEcran-apk** (zip contenant `app-debug.apk`) et installe-le sur ton téléphone
(autorise « sources inconnues » pour ton navigateur ou ton gestionnaire de fichiers).

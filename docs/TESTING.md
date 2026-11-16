# Tests et validation

## Vérifications automatisées

Le workflow `.github/workflows/ci.yml` déclenche sur push et pull request :

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Il publie temporairement un APK `evidencevault-debug-apk` dans les artefacts GitHub Actions. Une réussite CI **ne certifie pas** le bon fonctionnement sur appareil, et le jeu de tests unitaires est encore minimal.

## Scénarios manuels indispensables

- **Permissions** : premier lancement ; caméra refusée ; micro refusé puis autorisé ; vidéo sans micro.
- **Capture** : audio court et long ; vidéo caméra avant/arrière ; démarrage/arrêt rapides ; appels entrants ; rotations ; fermeture de l'activité.
- **Cycle de vie** : écran verrouillé ; bascule en arrière-plan ; batterie faible ; interruption du service et redémarrage du téléphone.
- **Coffre** : preuve présente après capture ; renommage ; recherche ; pause/reprise/seek audio ; export ZIP ; aperçu des messages d'intégrité.
- **Sécurité** : altération d'un octet d'un `.enc`, retrait d'un fichier, manifeste corrompu, échec du stockage, absence d'espace libre ; vérifier l'absence de fichier temporaire en clair après erreur.
- **Compatibilité** : tester au minimum sur Android 8 et sur une version récente d'Android, idéalement sur plusieurs fabricants.

Ne réalisez pas ces tests avec des preuves personnelles sensibles ou irremplaçables.

## Critères avant publication stable

- CI et tests instrumentés Android verts.
- Signatures release persistantes et mise à jour d'une version à l'autre démontrée.
- Analyse de sécurité et des sauvegardes terminée.
- Tests réels audio/vidéo y compris interruptions Android.
- Procédure de restauration des données et des clés documentée, ou limitation explicitement acceptée.

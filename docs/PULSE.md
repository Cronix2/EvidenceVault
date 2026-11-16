# Pulse — sauvegarde chiffrée en ajout uniquement

Pulse expédie automatiquement **les originaux chiffrés** du coffre EvidenceVault et les versions de son manifeste vers un serveur HTTPS préconfiguré. Les transferts sont désactivés par défaut ; un consentement explicite dans **Sécurité → Pulse** est obligatoire.

## Déployer le serveur

Le serveur de référence est dans `pulse-server/`, avec une API FastAPI et un stockage local sur volume Docker.

```bash
cd pulse-server
cp .env.example .env
# Remplacer PULSE_UPLOAD_TOKEN par un secret unique :
openssl rand -hex 48
# Copier ce secret dans .env, puis :
docker compose up -d --build
```

Le serveur écoute **uniquement sur 127.0.0.1:8767** côté hôte. Publier le service derrière un reverse proxy HTTPS (ou un tunnel HTTPS) avec authentification et un certificat TLS valide. Ne pas exposer directement le port Docker en clair sur Internet.

Configurer ensuite dans EvidenceVault : **Sécurité → Pulse → Configurer l'adresse et le jeton**, avec l'URL complète `https://votre-domaine/v1/evidence` et le même jeton que dans le serveur. **Activer Pulse** et approuver explicitement les uploads automatiques.

Les données sont transférées lors d'une capture réussie et par WorkManager dès qu'une connexion réseau répond aux contraintes. Android peut différer les tâches pour économiser la batterie ; ce n'est pas une garantie d'envoi instantané.

## Protocole

`POST /v1/evidence` avec le corps binaire du fichier `.enc` :

- `Authorization: Bearer <token>` — jeton *upload-only* ;
- `X-Device-ID` — UUID aléatoire de l'installation ;
- `X-Evidence-Name` — nom en base64url ;
- `X-Evidence-SHA256` — empreinte hexadécimale du fichier chiffré.

Le serveur vérifie le SHA-256, crée un objet à nom de contenu (hash), puis une ligne de version sous une clé unique `device_id + filename + sha256`. Rejouer le même upload est idempotent. Réémettre le même nom avec un nouveau contenu crée **une nouvelle version** sans remplacer l'ancienne.

Aucun endpoint GET des preuves, PATCH, PUT ou DELETE n'est exposé avec ce jeton. Toutefois, un administrateur du serveur ou du système de fichiers peut supprimer les copies : pour une rétention WORM stricte, envisager un stockage externe avec verrouillage d'objets ou snapshots immuables.

## Confidentialité et limites

- Le serveur reçoit les preuves et manifestes **déjà chiffrés**, jamais le PIN ni la clé Keystore.
- Une sauvegarde exportée sur un autre appareil n'est **pas déchiffrable** par celui-ci sans solution de gestion de clés ou de restauration distincte. Pulse n'est donc pas encore un système de reprise après perte de téléphone.
- Les fichiers chiffrés AES-GCM sont pratiquement incompressibles. Pour un vrai mode compression vidéo, une dérivée transcodée et chiffrée devra être créée **en plus** de l'original conservé.
- Le jeton est chiffré dans le stockage privé de l'application, mais les opérations de configuration et les caches temporaires restent à auditer.
- Le serveur exemple ne gère pas encore les uploads multipart/résumables, les quotas par utilisateur, les métadonnées signées, la rotation de jetons ni l'authentification mutuelle.
- La purge locale de l'application **ne supprime pas les versions distantes**.
- Les tests de charge, la sécurité du reverse proxy et les essais de restauration doivent être réalisés avant usage avec des preuves critiques.

## Vérifications recommandées

1. Activer Pulse sur un serveur de test HTTPS, puis enregistrer un audio et une vidéo.
2. Couper Internet avant l'arrêt de la capture ; vérifier que les transferts reprennent après reconnexion.
3. Rejouer une requête avec le même SHA-256 et vérifier qu'il n'y a pas de doublon.
4. Modifier un fichier source et vérifier qu'une **nouvelle version** est créée et que l'ancien objet reste présent.
5. Vérifier qu'aucune route DELETE ou PUT n'est disponible avec le jeton de l'application.
6. Tester un upload interrompu, fichier long, jeton invalide, certificat TLS invalide et batterie faible.

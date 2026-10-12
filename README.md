# Pont Balance — balance Minebea Puro → MES Power Apps

Petite application Android qui lit la balance branchée en USB sur la tablette
et envoie chaque pesée au MES (via un flow Power Automate et une liste SharePoint).

```
Balance Puro ──USB-C──► Tablette Android (app Pont Balance, en arrière-plan)
                              │  HTTPS
                              ▼
                 Flow « PontBalance_Pesee » ──► liste SharePoint PESEES_BALANCE
                                                        │
                                       MES Power Apps ◄─┘ (bouton LIRE LA PESÉE)
```

---

## Étape 1 — Créer la liste SharePoint `PESEES_BALANCE`

Sur le site SaniMarcEurope, créer la liste **PESEES_BALANCE** avec ces colonnes
(noms **sans accents**, pour que les noms internes correspondent au flow) :

| Colonne      | Type                | Contenu                                   |
|--------------|---------------------|-------------------------------------------|
| Titre        | (existante)         | nom de la balance, ex. `BAL 30`           |
| Poids        | Nombre (décimales)  | poids lu                                  |
| Unite        | Une ligne de texte  | `g`, `kg`…                                |
| Quantite     | Nombre              | nombre de pièces, si la balance l'envoie  |
| Trame        | Une ligne de texte  | texte brut reçu (traçabilité)             |
| Horodatage   | Date et heure       | heure de la pesée                         |
| Tablette     | Une ligne de texte  | modèle de la tablette                     |

## Étape 2 — Importer le flow

1. Power Automate → **Mes flux → Importer → Importer un package (hérité)** →
   `PontBalance_Pesee_flow.zip`.
2. Connexion SharePoint : choisir la tienne → **Importer**.
3. Ouvrir le flow, action **Créer un élément** : vérifier l'**adresse du site**
   (mise par défaut à `https://sanimarc.sharepoint.com/sites/SaniMarcEurope`)
   et choisir la liste **PESEES_BALANCE** dans la liste déroulante.
   Vérifier que les champs Poids, Unite… sont toujours remplis.
4. Déclencheur **Lors de la réception d'une requête HTTP** : mettre
   « Qui peut déclencher le flux » sur **N'importe qui**.
5. **Enregistrer**, puis copier l'**URL HTTP POST** du déclencheur.
   ⚠️ Cette URL est une clé : ne la diffuse pas.

## Étape 3 — Obtenir l'application (APK)

Le plus simple, sans rien installer :

1. Créer un compte gratuit sur github.com, puis un **nouveau dépôt privé**.
2. **Add file → Upload files** : glisser **tout le contenu** du dossier
   `PontBalance` (y compris le dossier caché `.github`). Valider.
3. Onglet **Actions** : la compilation « Construire l'APK » se lance (≈ 5 min).
4. Une fois verte, ouvrir l'exécution → section **Artifacts** →
   télécharger **PontBalance-apk** (zip contenant `app-debug.apk`).

Variante : ouvrir le dossier dans **Android Studio** → *Build → Build APK*.

## Étape 4 — Installer sur la tablette

1. Copier `app-debug.apk` sur la tablette et l'ouvrir
   (autoriser « Sources inconnues » quand Android le demande).
2. Ouvrir **Pont Balance** : saisir le **nom de la balance** (celui de
   l'étiquette, ex. `BAL 30`) et coller l'**URL du flow**.
3. Brancher la balance : câble **USB-C ↔ USB-C** (ou USB-C de la balance ↔
   adaptateur OTG de la tablette).
4. Android demande l'autorisation USB : cocher **« Toujours ouvrir »** → OK.
5. **Enregistrer et connecter la balance.** Le statut doit passer au vert.

## Étape 5 — Tester

1. Poser un objet, appuyer sur **Print** sur la balance.
2. Le poids s'affiche en grand dans l'app, puis « Envoyé à hh:mm:ss ».
3. Vérifier la ligne dans PESEES_BALANCE.

Si rien n'arrive, regarder le cadre **DIAGNOSTIC** :

| Ce que tu vois                         | Que faire                                                                 |
|----------------------------------------|---------------------------------------------------------------------------|
| « Appareils USB vus : aucun »          | câble/adaptateur OTG ; certains câbles USB-C ne font que la charge        |
| Balance connectée mais rien sur Print  | menu de la balance : activer la sortie de données sur l'interface USB     |
| Caractères bizarres reçus              | changer **Vitesse** / **Format** pour ceux réglés dans la balance         |
| « Trame sans poids reconnu »           | m'envoyer une capture du diagnostic, j'adapte la lecture                  |
| « ENVOI REFUSÉ 401/403 »               | étape 2.4 : « N'importe qui »                                             |

Le bouton **Demander le poids à la balance** envoie la commande standard
Minebea « ESC P » : si la balance l'accepte, plus besoin d'appuyer sur Print.

## Étape 6 — Dans le MES

Coller `BLOC_BALANCE_SCR_QUALITE.yaml` sur SCR_QUALITE (ajouter d'abord la
source de données PESEES_BALANCE). Le bouton **LIRE LA PESÉE** récupère la
dernière pesée de la balance choisie dans `VarPesee`.

Pour remplir un champ du formulaire qualité avec la pesée :
`Default` du contrôle = `VarPesee.Poids`.

Si Power Apps signale une erreur de séparateur au collage, remplacer les `,`
des formules par `;`.

---

### Bon à savoir

- L'app tourne **en arrière-plan** (notification « Pont balance actif ») : le
  MES peut rester au premier plan.
- **Wi-Fi coupé** : les pesées sont gardées sur la tablette et renvoyées
  automatiquement au retour du réseau.
- Une tablette = une balance. Le nom saisi dans l'app doit être identique à
  celui tapé dans le MES.

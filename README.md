# AurexaSumo - Sumo pour Aurexalium (Spigot 1.8.8)

Tournoi a elimination directe. Les joueurs attendent dans un lobby, puis se
battent 1 contre 1 sur une plateforme, chaque match se jouant en plusieurs rounds (au meilleur de 5 par defaut). Pas de degats : on se repousse
seulement. Celui qui tombe (sous la plateforme ou dans l'eau / la lave) est
elimine. Le dernier joueur en vie gagne.

Coins donnes a la fin : gagnant, finaliste, participation (config.yml).

## Compiler
Avec GitHub : le fichier `.github/workflows/build.yml` compile le plugin a
chaque commit. Le jar est dans Actions > derniere execution > Artifacts.
Sinon, en local : `mvn clean package` -> `target/AurexaSumo.jar`.

## Creer une map
1. `/sumo create sumo1`
2. Dans la salle d'attente : `/sumo setlobby sumo1`
3. Sur la plateforme, d'un cote : `/sumo setspawn sumo1 1`, de l'autre : `/sumo setspawn sumo1 2`
4. Ou iront les spectateurs et les elimines : `/sumo setspec sumo1`
5. Zone de jeu : `/sumo pos1` et `/sumo pos2` (deux coins opposes, le bas doit
   etre EN DESSOUS de la plateforme), puis `/sumo setregion sumo1`.
   Tomber sous le bas de cette zone elimine le joueur.
6. `/sumo info sumo1` : tout doit etre vert.
7. Panneau : pose un panneau vierge, `/sumo sign sumo1`, clic droit dessus.

## Jouer
`/sumo join <map>`, `/sumo leave`, `/sumo list`, ou clic sur un panneau.
Objet "Quitter" (lit) dans la derniere case de la barre.

## Permission
`aurexasumo.admin` : configurer les arenes (op par defaut).
